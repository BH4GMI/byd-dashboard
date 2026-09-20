package com.byd.dashcast;

import android.content.Context;
import android.util.Log;

import com.byd.dashcast.adb.AdbClient;
import com.byd.dashcast.adb.AdbKeyStore;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长连接的 ADB shell 通道。
 *
 * <h3>它现在的位置：兜底 + 只有它能做的事</h3>
 * 触摸与画面预览已改由 {@link PrivilegedClient} 的 Binder 通道承担（见
 * {@code docs/PREVIEW_MECHANISM_ZH.md} §六）。本类仍然独占下面这些能力，
 * 它们要么没有 Binder 等价路径，要么本来就是"跑一条命令看输出"的形态：
 *
 * <pre>
 *   launch          → am start-activity --display N -n pkg/act
 *   touch / tap     → input -d N tap|motionevent|swipe   ← 仅当特权进程不可用时降级使用
 *   key             → input -d N keyevent
 *   listApps        → cmd package query-activities --brief
 *   taskDisplay     → dumpsys activity activities（解析 Task{...} 归属）
 *   moveToDisplay   → am display move-stack &lt;rootTaskId&gt; &lt;display&gt;   ← 看门靠它
 *   screencap       → exec: 取 PNG 字节（判页补点闭环读画面；预览降级时也用它）
 * </pre>
 *
 * <h3>为什么当初以为可以不要常驻代理</h3>
 * 那一轮的判断是"shell 命令足以覆盖全部需求"，理由是代理的**句柄分发**有隐藏状态：
 * Binder 句柄只能靠广播送回应用，而广播只在"最近没人调用过"时才发（旧 Agent 用全局
 * {@code lastContact} 判断），于是先连上的客户端把代理钉住 → 后启动的客户端永远收不到
 * 广播 → 界面永远停在"注入代理未连接"。实测踩过：车机重启后 netease 分支先自启占住代理，
 * 母工程晚了 18 分钟启动就再也连不上。
 *
 * <p>**句柄分发那个坑是真的，但结论下错了** —— 真正走不通的是另外两条腿：
 * {@code input} 每个事件 fork 一个 ART（实测 40~140 ms/次），{@code screencap} 只有
 * 3.2~3.5 fps。所以代理回来了，只是把句柄分发换成了显式广播 + 拉起前 {@code pkill} 清场，
 * 那个隐藏状态从结构上不再存在。
 *
 * <h3>线程安全</h3>
 * {@link AdbClient} 的 socket 不能并发读写，所以每条连接上的命令都在自己的锁上串行化。
 * **交互命令与预览抓帧分属两条独立连接**：抓一帧要传 765 KB、耗时 0.3s 量级，
 * 若与触摸共用一条连接，每次触摸平均要多等半帧（实测口径下约 0.15s）。
 * 两条连接在 adbd 侧互不影响。
 */
public final class ShellChannel {

    private static final String TAG = "dashcast";

    /** 连接超时：adbd 在回环上，正常是毫秒级。 */
    private static final long CONNECT_TIMEOUT_MS = 8000L;

    /** 普通命令超时。触摸走这条路，不能太长。 */
    private static final long SHELL_TIMEOUT_MS = 10000L;

    /** 输出可能很大的命令（dumpsys / query-activities）。 */
    private static final long BIG_SHELL_TIMEOUT_MS = 20000L;

    /**
     * 抓一帧的超时。实测 1920x720 的 PNG 约 765 KB、耗时 0.29~0.31s，
     * 给足余量但不能太长——判页是轮询调用，超时太久会把补点闭环拖垮。
     */
    private static final long SCREENCAP_TIMEOUT_MS = 4000L;

    /** 解析 query-activities 输出里的组件行：`    pkg/activity`。 */
    private static final Pattern COMPONENT_LINE =
            Pattern.compile("^\\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\s*$");

    /** 解析 dumpsys activity activities 里的 Task 行。 */
    private static final Pattern TASK_LINE =
            Pattern.compile("Task\\{[0-9a-f]+ #(\\d+) [^}]*A=\\d+:(\\S+?)[\\s}]");

    private static final ShellChannel INSTANCE = new ShellChannel();

    private final Object lock = new Object();

    /**
     * 预览连接数。
     *
     * <p>为什么要**多条**：抓一帧的耗时几乎全在设备端 PNG 编码（实测 1920x720 约 422 ms），
     * 而这一步在多核 SoC 上可以并行 —— 实测 3 条并发等效 177 ms/帧、6 条 131 ms/帧，
     * 单条只有 422 ms/帧（2.4 fps）。所以预览用一组连接做流水线抓帧，而不是一条连接串行。
     */
    public static final int PREVIEW_CHANNELS = 4;

    /**
     * 每条预览连接一把锁，与 {@link #lock} 完全独立。
     *
     * <p>嵌套方向只能是 {@code previewLock → lock}（重连路径要先 {@link #ensure} 备好密钥）；
     * **任何已持有 {@link #lock} 的代码都不许再取预览锁**。当前没有这样的路径，
     * 将来往通道里加命令时也要守住这一条。
     */
    private final Object[] previewLocks = new Object[PREVIEW_CHANNELS];

    private AdbClient adb;

    /**
     * 预览专用连接组。生命周期与 {@link #adb} 独立：它们坏了只影响画面刷新，
     * 交互命令照常走主连接，不需要跟着一起重连。
     */
    private final AdbClient[] previewAdb = new AdbClient[PREVIEW_CHANNELS];

    private PrivateKey privateKey;
    private RSAPublicKey publicKey;

    /**
     * 建连用的 Context。**必须留着**：连接在命令失败时会被丢弃，
     * 没有它就再也重连不回来，整条通道会一直空转到应用重启。
     */
    private Context appContext;

    private ShellChannel() {
        for (int i = 0; i < PREVIEW_CHANNELS; i++) {
            previewLocks[i] = new Object();
        }
    }

    public static ShellChannel get() {
        return INSTANCE;
    }

    // ---- 连接 --------------------------------------------------------------

    /**
     * 建立或复用连接。**必须在非主线程调用**（要握手，首次还可能现场生成 RSA-2048）。
     *
     * @return true 表示连接可用
     */
    public boolean ensure(Context context) {
        synchronized (lock) {
            if (context != null) {
                appContext = context.getApplicationContext();
            }
            if (adb != null) {
                return true;
            }
            if (appContext == null) {
                return false;
            }
            try {
                AdbKeyStore keys = AdbKeyStore.loadOrCreate(appContext);
                privateKey = keys.privateKey();
                publicKey = keys.publicKey();
                AdbClient.State[] state = new AdbClient.State[1];
                // requestAuthorization=false：这里绝不弹授权框。没授权就连不上，
                // 由调用方把用户交给引导页，避免留下收不到输入的孤儿对话框。
                adb = AdbClient.connect(privateKey, publicKey, CONNECT_TIMEOUT_MS, false, state);
                if (adb == null) {
                    AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
                    Log.w(TAG, "shell 通道连接失败：" + s);
                    return false;
                }
                Log.i(TAG, "shell 通道已建立（uid 2000）");
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "建立 shell 通道失败", t);
                adb = null;
                return false;
            }
        }
    }

    public boolean isReady() {
        synchronized (lock) {
            return adb != null;
        }
    }

    /**
     * 接管一条已经建立好的连接（由 {@link AdbBootstrap#provision} 建立，
     * 因为那条路径允许弹授权框，而 {@link #ensure} 不允许）。
     *
     * <p>旧连接若存在会被关闭 —— 保证任一时刻只有一条，避免两个 socket 抢同一个 adbd 会话。
     */
    public void adopt(AdbClient client, PrivateKey priv, RSAPublicKey pub) {
        synchronized (lock) {
            AdbClient old = adb;
            adb = client;
            privateKey = priv;
            publicKey = pub;
            if (old != null && old != client) {
                try {
                    old.close();
                } catch (Throwable t) {
                    Log.w(TAG, "替换 shell 通道时关闭旧连接失败", t);
                }
            }
        }
    }

    public void close() {
        synchronized (lock) {
            AdbClient c = adb;
            adb = null;
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable t) {
                    Log.w(TAG, "关闭 shell 通道失败", t);
                }
            }
        }
    }

    // ---- 预览连接组 --------------------------------------------------------

    /**
     * 建立或复用第 {@code channel} 条**预览专用**连接。必须在非主线程调用。
     *
     * <p>为什么不与交互命令共用连接：抓一帧是一条 170 KB 的 {@code exec:} 流，在
     * {@link #lock} 上要独占 0.4 s 量级；触摸命令只有几毫秒，却必须排在它后面 ——
     * 表现为"点了要顿一下"。预览连接组与主连接在 adbd 侧是各自独立的会话。
     *
     * <p>身份必须与主连接**同一把密钥**：adbd 按公钥认身份，另生成一把会走到授权
     * 流程（那会弹框，而本工程一律不弹）。所以先 {@link #ensure} 把密钥备好再连。
     */
    public boolean ensurePreview(Context context, int channel) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        if (!ensure(appContext)) {
            return false;
        }
        synchronized (previewLocks[channel]) {
            if (previewAdb[channel] != null) {
                return true;
            }
            try {
                AdbClient.State[] state = new AdbClient.State[1];
                AdbClient c = AdbClient.connect(privateKey, publicKey, CONNECT_TIMEOUT_MS, false, state);
                if (c == null) {
                    AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
                    Log.w(TAG, "预览通道 " + channel + " 连接失败：" + s);
                    return false;
                }
                previewAdb[channel] = c;
                Log.i(TAG, "预览通道 " + channel + " 已建立（独立连接）");
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "建立预览通道 " + channel + " 失败", t);
                previewAdb[channel] = null;
                return false;
            }
        }
    }

    /**
     * 断开**全部**预览连接。界面不可见时调用，把 adbd 会话还回去 ——
     * 留着空闲连接只会占住设备侧的会话槽位。
     */
    public void closePreview() {
        for (int i = 0; i < PREVIEW_CHANNELS; i++) {
            closePreviewChannel(i);
        }
    }

    /**
     * 关掉一条预览连接。
     *
     * <p>**置空在锁内、{@code close} 在锁外**：抓帧线程可能正持着这把锁读一帧（最长一个
     * screencap 超时），在锁内 close 会把调用方（通常是 UI 线程的 onPause）卡住；
     * 而 {@code Socket.close()} 本身立即返回，不需要靠锁保护。
     */
    private void closePreviewChannel(int channel) {
        AdbClient c;
        synchronized (previewLocks[channel]) {
            c = previewAdb[channel];
            previewAdb[channel] = null;
        }
        if (c != null) {
            try {
                c.close();
            } catch (Throwable t) {
                Log.w(TAG, "关闭预览通道 " + channel + " 失败", t);
            }
        }
    }

    /** 复用已记住的 Context 重连某条预览连接。调用方必须已持有该通道的锁。 */
    private boolean reconnectPreviewLocked(int channel) {
        if (appContext == null) {
            return false;
        }
        Log.i(TAG, "预览通道 " + channel + " 已断开，尝试重连");
        return ensurePreview(appContext, channel);
    }

    private void closePreviewQuietly(int channel) {
        AdbClient c = previewAdb[channel];
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 已经坏了，关不掉也无所谓
            }
            previewAdb[channel] = null;
        }
    }

    // ---- 底层 --------------------------------------------------------------

    /**
     * 跑一条命令，返回 stdout。连接坏了就丢弃连接并返回 null ——
     * 下次 {@link #ensure} 会重建。返回 null 与"命令失败"是两件事：
     * 命令失败仍有 stdout/stderr 可看。
     */
    public String run(String command) {
        return run(command, SHELL_TIMEOUT_MS);
    }

    public String run(String command, long timeoutMs) {
        synchronized (lock) {
            if (adb == null && !reconnectLocked()) {
                return null;
            }
            try {
                return adb.shell(command, timeoutMs);
            } catch (Throwable t) {
                Log.w(TAG, "shell 命令失败，丢弃连接：" + command, t);
                closeQuietly();
                return null;
            }
        }
    }

    /**
     * 复用已记住的 Context 重连。调用方必须已持有 {@code lock}（synchronized 可重入）。
     *
     * <p>没有这一步，任何一次命令失败都会让通道**永久**变哑：adb 被置空之后
     * 只有 provision() 会重新建连，而那是引导页才走的路。
     */
    private boolean reconnectLocked() {
        if (appContext == null) {
            return false;
        }
        Log.i(TAG, "shell 通道已断开，尝试重连");
        return ensure(appContext);
    }

    private void closeQuietly() {
        if (adb != null) {
            try {
                adb.close();
            } catch (Throwable ignored) {
                // 已经坏了，关不掉也无所谓
            }
            adb = null;
        }
    }

    /** 把失败响应缩成一行可读文本，用来判断远端到底回了什么。 */
    private static String preview(byte[] data) {
        if (data == null || data.length == 0) {
            return "<空>";
        }
        int n = Math.min(data.length, 160);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xFF;
            sb.append(b >= 32 && b < 127 ? (char) b : '.');
        }
        return sb.toString();
    }

    /** 需要大输出的命令。 */
    public String runBig(String command) {
        return run(command, BIG_SHELL_TIMEOUT_MS);
    }

    /**
     * 抓某个 display 的一帧，返回 PNG 字节。失败返回 null。
     *
     * <p>为什么要抓**镜像屏**而不是投屏屏：共享屏（display 3/4）只是中转，
     * {@code screencap -d 3} 拿到的是全黑（实测非黑像素 0%），而内容其实在
     * 主虚拟屏 display 2 上（同一时刻 97.5% 非黑）。判页要判的是应用画面，
     * 所以调用方应当传 {@link #inputDisplayFor} 解析出的那个 display。
     *
     * <p>为什么走 {@code exec:} 而不是 {@code shell:}：**这条 shell 通道拿不回完整的
     * 二进制**。{@code screencap -p} 的 PNG 只回来 6 个字节（正好是魔数
     * {@code 89 50 4E 47 0D 0A}），改用 base64 绕行同样被截断（只回 6 个字符）。
     * 服务名早已是 {@code shell,v2,raw:}，说明这个 adbd 的 raw 并没有真正绕开文本处理。
     * {@code exec:} 是 {@code adb exec-out} 走的那条路，不经过 shell/pty，实测能拿到完整 PNG；
     * 代价是不能带管道，所以这里就是一条 {@code screencap}。
     */
    public byte[] screencap(int display) {
        synchronized (lock) {
            if (adb == null && !reconnectLocked()) {
                return null;
            }
            try {
                return grabPng(adb, display);
            } catch (Throwable t) {
                Log.w(TAG, "screencap -d " + display + " 失败，丢弃连接", t);
                closeQuietly();
                return null;
            }
        }
    }

    /**
     * 抓一帧供界面预览，走第 {@code channel} 条**预览专用连接**（见 {@link #ensurePreview}）。
     *
     * <p>与 {@link #screencap} 的唯一区别是"走哪条连接、坏了关哪一条"，抓帧本身共用
     * {@link #grabPng}。预览是持续高频调用，所以它**绝不允许**占用交互命令的 {@link #lock} ——
     * 否则用户每点一下都要排在半帧画面后面。
     *
     * <p>多通道是为了并发：单条连接串行时吞吐受制于设备端 PNG 编码（实测 422 ms/帧），
     * 多条连接同时抓才能把它摊开（见 {@link #PREVIEW_CHANNELS}）。
     */
    public byte[] screencapPreview(int channel, int display) {
        synchronized (previewLocks[channel]) {
            if (previewAdb[channel] == null && !reconnectPreviewLocked(channel)) {
                return null;
            }
            try {
                return grabPng(previewAdb[channel], display);
            } catch (Throwable t) {
                Log.w(TAG, "预览通道 " + channel + " 抓帧 -d " + display + " 失败，丢弃该连接", t);
                closePreviewQuietly(channel);
                return null;
            }
        }
    }

    /**
     * 抓帧的共用实现。
     *
     * <p>返回 null 表示"远端回的字节不是 PNG"（例如把错误文本混进了同一路输出），
     * 这**不是**连接坏了，所以调用方不应因此丢弃连接；连接层面的故障直接抛出，
     * 由调用方决定丢哪条连接。
     */
    private static byte[] grabPng(AdbClient client, int display) throws IOException {
        byte[] png = client.exec("screencap -d " + display + " -p", SCREENCAP_TIMEOUT_MS);
        // shell 会把错误文本混进同一路输出，PNG 的魔数能一眼区分开。
        if (png == null || png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P') {
            Log.w(TAG, "screencap -d " + display + " 没有返回 PNG（"
                    + (png == null ? "null" : png.length + " 字节")
                    + "），内容：" + preview(png));
            return null;
        }
        return png;
    }

    // ---- 投屏 --------------------------------------------------------------

    /** 在指定 display 上启动 Activity。activity 必须是 exported 的。 */
    public String launchOn(int display, String packageName, String activityName) {
        return run("am start-activity --display " + display + " -n "
                + packageName + "/" + activityName);
    }

    // ---- 输入注入 ----------------------------------------------------------

    // 输入屏解析缓存：同一个包的输入窗口在一次投屏会话里不会变，
    // 而解析要跑一条 dumpsys（实测 ~105ms），绝不能每条触摸都查。
    private volatile String inputDisplayPkg;
    private volatile int inputDisplayValue = -1;

    /**
     * 找出某个包**当前真正接受输入**的 display。
     *
     * <p>为什么不能直接把投屏目标屏当输入屏：共享屏（display 3/4）只是"中转"。
     * 容器服务 {@code AutoSharedDisplay} 的做法是
     * {@code createVirtualDisplay("shared_...", w, h, 320, new Surface(容器层), 11)}
     * 再用 {@code IWindowManager.mirrorDisplay(0 - displayId, ...)} 把内容镜像回主虚拟屏
     * （display 2）。**输入窗口跟着镜像一起注册到了 display 2**，于是往投屏屏注入
     * 会被 InputDispatcher 直接丢掉：
     *
     * <pre>Dropping event because there is no touched foreground window in display 3
     * or gesture monitor to receive it.</pre>
     *
     * <p>所以这里从 {@code dumpsys input} 里读窗口真正挂在哪个 display 上，而不是猜。
     * 查不到就回退到投屏屏（至少语义明确，不会静默发到错的地方）。
     */
    public int inputDisplayFor(String pkg, int fallback) {
        if (pkg == null || pkg.isEmpty()) {
            return fallback;
        }
        if (pkg.equals(inputDisplayPkg) && inputDisplayValue >= 0) {
            return inputDisplayValue;
        }
        int found = queryInputDisplay(pkg);
        if (found >= 0) {
            inputDisplayPkg = pkg;
            inputDisplayValue = found;
            Log.i(TAG, "输入屏解析：" + pkg + " 的输入窗口在 display " + found
                    + "（投屏目标 display " + fallback + "）");
            return found;
        }
        return fallback;
    }

    /** 投屏会话变了要清掉缓存，否则会拿着上一个包的输入屏去注入。 */
    public void forgetInputDisplay() {
        inputDisplayPkg = null;
        inputDisplayValue = -1;
    }

    private int queryInputDisplay(String pkg) {
        // 设备端过滤到一行再回传：整个 dumpsys input 有几十 KB，拉回来解析既慢又占内存。
        String cmd = "dumpsys input | grep 'applicationInfo.name=ActivityRecord{.*" + pkg
                + "' | head -1 | sed 's/.*displayId=\\([0-9]*\\).*/\\1/'";
        try {
            String out = run(cmd, 5000);
            Log.i(TAG, "输入屏查询 [" + pkg + "] 原始输出：["
                    + (out == null ? "null" : out.trim()) + "]");
            if (out == null) {
                return -1;
            }
            for (String line : out.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    // 不是数字（grep 无匹配时可能是空或错误文本），继续看下一行。
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "查询输入屏失败：" + pkg, e);
        }
        return -1;
    }

    public String tap(int display, float x, float y) {
        return run("input -d " + display + " tap " + Math.round(x) + " " + Math.round(y));
    }

    /**
     * 触摸动作，用于需要轨迹的拖动。action 取 DOWN / MOVE / UP / CANCEL。
     * 与 tap 不同，这条路能表达连续轨迹 —— 车机副屏 touch=NONE，所有输入都得注入。
     */
    public String touch(int display, String action, float x, float y) {
        return run("input -d " + display + " motionevent " + action + " "
                + Math.round(x) + " " + Math.round(y));
    }

    /** 按键。key 事件由 focused window 接收，不需要 display。 */
    public String key(int keyCode) {
        return run("input keyevent " + keyCode);
    }

    // ---- 应用清单 ----------------------------------------------------------

    /**
     * 枚举可投屏的应用（MAIN action）。
     *
     * <p>必须在 uid 2000 里查：Android 11+ 的包可见性过滤**按 uid 生效**，
     * 应用侧 {@code queryIntentActivities} 实测只有 24 个，而这里能拿到 126 条。
     *
     * <p>label 这里拿不到 —— {@code dumpsys package} 不含 label 文本，而解析
     * resources.arsc 需要 aapt。所以 label 由界面侧用 {@code PackageManager}
     * 尽力补全（受包可见性限制，补不到就显示包名）。
     */
    public List<AppEntry> apps() {
        String out = runBig("cmd package query-activities --brief -a android.intent.action.MAIN");
        List<AppEntry> list = new ArrayList<AppEntry>();
        if (out == null) {
            return list;
        }
        Map<String, AppEntry> seen = new LinkedHashMap<String, AppEntry>();
        for (String raw : out.split("\n")) {
            Matcher m = COMPONENT_LINE.matcher(raw);
            if (!m.matches()) {
                continue;
            }
            String pkg = m.group(1);
            String act = m.group(2);
            if (seen.containsKey(pkg)) {
                continue;
            }
            // 跳过系统界面本身，它们投到副屏没有意义
            if (pkg.startsWith("com.android.") || pkg.equals("android")) {
                continue;
            }
            AppEntry e = new AppEntry(pkg, act);
            seen.put(pkg, e);
            list.add(e);
        }
        return list;
    }

    // ---- 任务与看门 --------------------------------------------------------

    /**
     * 某包的 root task 当前在哪个 display。找不到返回 -1。
     *
     * <p>解析 {@code dumpsys activity activities}：Task 行带 {@code #taskId} 与
     * {@code A=uid:pkg}，而它归属哪个 display 由所在 {@code Display #N} 段落决定。
     *
     * <p>**在设备端先 grep 再回传**：全量输出约 12000 行，过滤后只剩 ~50 行。
     * 实测设备端耗时 0.03s，所以看门每秒巡检一次也不构成负担。
     */
    public int taskDisplay(String packageName) {
        String out = runBig(TASK_QUERY);
        if (out == null) {
            return -1;
        }
        int display = -1;
        for (String line : out.split("\n")) {
            int d = parseDisplayHeader(line);
            if (d >= 0) {
                display = d;
                continue;
            }
            Matcher m = TASK_LINE.matcher(line);
            if (m.find() && m.group(2).equals(packageName)) {
                return display;
            }
        }
        return -1;
    }

    /** 该包 root task 的 id；找不到返回 -1。 */
    public int taskId(String packageName) {
        String out = runBig(TASK_QUERY);
        if (out == null) {
            return -1;
        }
        for (String line : out.split("\n")) {
            Matcher m = TASK_LINE.matcher(line);
            if (m.find() && m.group(2).equals(packageName)) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    /** 设备端先过滤再回传，避免把 12000 行 dumpsys 拉回来。 */
    private static final String TASK_QUERY =
            "dumpsys activity activities | grep -E 'Display #|Task\\{'";

    /**
     * 把某包的 root task 搬到指定 display（看门原语）。
     *
     * <p>实测 {@code am display move-stack 52 4} 有效：应用自己发起的 Activity 启动
     * 不带 display，AMS 会把整条 root task 挪回默认屏 —— 这是应用侧行为，无法预防，
     * 只能事后搬回。
     *
     * <p>**搬完必须回查屏位才算成功。** {@code am display move-stack} 失败时也会正常回话
     * （甚至只回一句 "Nothing to do"），所以"命令有输出"根本不能当成功判据 ——
     * 早期的实现就是拿它当判据，于是搬屏没生效时上层会一路当成投屏成功。
     * "不知道"不能算达成，这里多跑一次 {@link #taskDisplay} 把它问清楚。
     */
    public boolean moveToDisplay(String packageName, int display) {
        int id = taskId(packageName);
        if (id < 0) {
            return false;
        }
        String out = run("am display move-stack " + id + " " + display);
        if (out == null) {
            return false;
        }
        return taskDisplay(packageName) == display;
    }

    private static int parseDisplayHeader(String line) {
        // 形如：  Display #3 (activities from top to bottom):
        int at = line.indexOf("Display #");
        if (at < 0 || line.indexOf("(activities from top to bottom)") < 0) {
            return -1;
        }
        int i = at + "Display #".length();
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == start) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 一条可投屏的应用记录。 */
    public static final class AppEntry {
        public final String packageName;
        public final String activityName;
        /** 由界面侧尽力补全，补不到就是 null。 */
        public String label;

        AppEntry(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }

        public String display() {
            return label != null && label.length() > 0 ? label : packageName;
        }
    }
}
