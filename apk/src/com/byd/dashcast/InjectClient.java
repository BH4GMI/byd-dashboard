package com.byd.dashcast;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.byd.dashcast.ShellChannel.AppEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 特权能力客户端（**降级路径**）。
 *
 * <h3>2026-09-20：实现从「Binder 调代理」改成「长连接跑 shell 命令」</h3>
 * 公开方法签名保持不变，所以界面层几乎不用动。换掉的只有内部实现：
 *
 * <pre>
 *   旧：广播收 Binder → transact(TRANSACT_*) → uid 2000 代理进程
 *   新：ShellChannel.run("am ..." / "input ..." / "dumpsys ...")
 * </pre>
 *
 * <p>换掉的**理由**是旧路有一条不可观测的隐藏状态：代理用全局 {@code lastContact}
 * 决定"还要不要广播句柄"，于是先连上的客户端把代理钉住、后启动的客户端永远拿不到句柄
 * （实测：车机重启后 netease 分支先自启占住代理，母工程晚 18 分钟启动就再也连不上，
 * 界面永远停在"注入代理未连接"）。shell 命令没有这类状态，每条都有自己的返回。
 *
 * <h3>看门挪到了本端</h3>
 * 代理不在了，没人替我们巡检"任务是否被应用自己拽回主屏"，所以改由 {@link #ping()}
 * 承担：它在心跳里被周期调用，发现跑掉就用 {@code am display move-stack} 搬回。
 * 实测 {@code dumpsys} 在设备端过滤后只要 0.03s，每秒一次不构成负担。
 *
 * <h3>2026-09-20 晚：它变成**降级路径**</h3>
 * "去代理"的前提错了 —— 有两条腿 shell 走不动：触摸（{@code input} 每个事件 fork 一个
 * ART，本车实测 40~140 ms/次）和预览（{@code screencap} 只有 3.2~3.5 fps）。两条的
 * 正解都是 uid 2000 里跑代码，也就是反编译原版 Just Dashboard 看到的做法。
 * 于是 {@link PrivilegedClient} 接管了触摸与预览，本类退回兜底：
 * 特权进程起不来时，触摸走 {@code input motionevent}、预览走 {@code screencap} 抓帧。
 *
 * <p>仍然由本类独占的能力：**看门**（{@code am display move-stack}）、
 * **判页抓帧**（{@link DashboardEye} 补点闭环要读画面）、以及应用/任务清单（{@code dumpsys}）。
 */
public final class InjectClient {

    private static final String TAG = "dashcast";

    /**
     * 看门状态。界面状态栏据此显示 —— 看门若静默失效，用户只会看到"投屏又跑回主屏了"
     * 却不知道为什么。
     */
    public static final class WatchState {
        public final boolean watching;
        public final int moves;
        public final String note;
        /** 正在被看门的包名；空串表示没有。 */
        public final String packageName;

        WatchState(boolean watching, int moves, String note, String packageName) {
            this.watching = watching;
            this.moves = moves;
            this.note = note == null ? "" : note;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    /** 由调用方在后台线程上收结果；onResult 也在该后台线程上回调。 */
    public interface LaunchCallback {
        void onResult(boolean success, String message);
    }

    /** 应用清单同样是后台线程回调。 */
    public interface AppsCallback {
        void onApps(List<AppRepo.Entry> apps);
    }

    /**
     * 预览帧回调。**全部在预览线程上回调**：实现方可以直接在这条线程上解码
     * （PNG 解码是纯 CPU 活，放 UI 线程会直接卡住界面），解完再自己切回 UI 线程。
     */
    public interface FrameCallback {
        /** 一帧 PNG 字节。 */
        void onFrame(byte[] png);

        /**
         * 预览不可用。连续失败只在**首次**回调一次，恢复后再失败会重新报：
         * 既不刷屏，也不让"预览断了"这件事静默过去。
         */
        void onFailed(String message);
    }

    private final ShellChannel shell = ShellChannel.get();
    private final Context context;

    private volatile WatchState lastWatch;

    /**
     * 看门窗口：只覆盖"启动之后应用可能自己跳走"的自适应期。
     *
     * <p>为什么必须**有界**：看门无法区分"应用自己跑掉"和"用户手动搬走"。
     * 无限期看门的后果是用户在桌面上点该应用、从最近任务里拉它都会被 1 秒内搬回副屏，
     * 表现为"按了回不到前台" —— 这是实测踩过的坑，也是看门最容易被误解成"补丁"的地方。
     */
    private static final long WATCH_WINDOW_MS = 20000L;

    /** 窗口内又发生了一次搬回（说明应用还在乱跳）就顺延这么多。 */
    private static final long WATCH_EXTEND_MS = 8000L;

    /** 看门总时长硬上限：无论怎么顺延都不越过它。 */
    private static final long WATCH_MAX_TOTAL_MS = 45000L;

    // ---- 看门状态（本端维护，不再有代理回执）--------------------------------
    private volatile String watchedPackage;
    private volatile int watchedDisplay = -1;
    private volatile int watchMoves;
    private volatile String watchNote = "";
    private volatile long watchStartedAt;
    private volatile long watchDeadline;

    /** 每条命令都要带 display，touch 这条老接口没有 display 参数，所以记住它。 */
    private volatile int currentDisplay = -1;

    /**
     * 仪表盘**实际显示内容**的那块屏（主投影屏），由界面解析完 display 拓扑后告知。
     *
     * <p>它是 {@link #inputDisplay()} 的兜底：连"窗口挂在哪块屏"都解析不出来时，
     * 至少指仪表盘，而不是指必然全黑的投屏槽位。未告知时为 -1。
     */
    private volatile int projectionDisplay = -1;

    /**
     * 告知主投影屏。界面在 {@code DashboardSession.resolve()} 之后调用。
     *
     * <p>值变了才清输入屏缓存：会话换了，上一个包"窗口挂在哪块屏"的结论不再成立；
     * 没变就保持缓存，否则每次 resume 都要重新 dumpsys 一次。
     */
    public void setProjectionDisplay(int displayId) {
        if (projectionDisplay == displayId) {
            return;
        }
        projectionDisplay = displayId;
        shell.forgetInputDisplay();
    }

    public InjectClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public WatchState lastWatch() {
        return lastWatch;
    }

    /** 通道是否可用。旧语义是"代理句柄是否在手"，现在是"shell 连接是否活着"。 */
    public boolean isAttached() {
        return shell.isReady();
    }

    /**
     * 心跳：顺带做一次看门巡检。
     *
     * <p>发现被看门的包已经不在目标屏上，就搬回去；**窗口一到就自动松开**，
     * 把控制权还给用户。跑在调用线程上（界面侧的心跳线程），内部是一条
     * {@code dumpsys}（设备端过滤后 ~50 行）+ 可能的 {@code move-stack}。
     */
    public WatchState ping() {
        String pkg = watchedPackage;
        if (pkg == null) {
            lastWatch = new WatchState(false, watchMoves, watchNote, "");
            return lastWatch;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now > watchDeadline || now - watchStartedAt > WATCH_MAX_TOTAL_MS) {
            // 窗口结束：交还控制权。这一步不能省——它才是"用户能拿回前台"的保证。
            Log.i(TAG, "看门窗口结束，松开 " + pkg + "（共搬回 " + watchMoves + " 次）");
            watchedPackage = null;
            watchedDisplay = -1;
            watchNote = "看门已松开";
            lastWatch = new WatchState(false, watchMoves, watchNote, "");
            return lastWatch;
        }
        int cur = shell.taskDisplay(pkg);
        if (cur >= 0 && cur != watchedDisplay) {
            boolean ok = shell.moveToDisplay(pkg, watchedDisplay);
            if (ok) {
                watchMoves++;
                // 还在乱跳 → 顺延，但绝不越过硬上限
                watchDeadline = Math.min(now + WATCH_EXTEND_MS,
                        watchStartedAt + WATCH_MAX_TOTAL_MS);
                watchNote = "已搬回 " + watchMoves + " 次";
                Log.i(TAG, "看门：把 " + pkg + " 从 display " + cur
                        + " 搬回 display " + watchedDisplay + "（第 " + watchMoves + " 次）");
            } else {
                watchNote = "搬回失败";
                Log.w(TAG, "看门：搬回 " + pkg + " 失败");
            }
        }
        lastWatch = new WatchState(true, watchMoves, watchNote, pkg);
        return lastWatch;
    }

    /** 记住当前目标屏。旧实现用它给代理设默认 display；现在每条命令自带 display。 */
    public void setDisplay(int displayId) {
        this.currentDisplay = displayId;
    }

    /**
     * 开始看门：该包一旦离开 displayId 就搬回，**但有时间上限**。
     *
     * <p>重新 watch 另一个包会立即松开前一个（这是"投屏导航"等切换操作的正常路径）。
     */
    public void watch(String packageName, int displayId, boolean persistent) {
        long now = android.os.SystemClock.uptimeMillis();
        // 换了投屏对象就必须丢掉上一个包的输入屏缓存，否则触摸会打到旧应用上。
        if (!packageName.equals(watchedPackage)) {
            shell.forgetInputDisplay();
        }
        this.watchedPackage = packageName;
        this.watchedDisplay = displayId;
        this.watchMoves = 0;
        this.watchNote = "";
        this.watchStartedAt = now;
        this.watchDeadline = now + WATCH_WINDOW_MS;
        Log.i(TAG, "开始看门：" + packageName + " 必须留在 display " + displayId
                + "（窗口 " + (WATCH_WINDOW_MS / 1000) + "s）");
    }

    public void unwatch() {
        if (watchedPackage != null) {
            Log.i(TAG, "停止看门：" + watchedPackage);
        }
        this.watchedPackage = null;
        this.watchedDisplay = -1;
    }

    // ---- 任务 --------------------------------------------------------------

    public int taskDisplay(String packageName) {
        return shell.taskDisplay(packageName);
    }

    public int[] moveToDisplay(String packageName, int displayId) {
        int from = shell.taskDisplay(packageName);
        if (from < 0) {
            return new int[]{-1, 0};
        }
        boolean ok = shell.moveToDisplay(packageName, displayId);
        return new int[]{from, ok ? 1 : 0};
    }

    // ---- 投屏 --------------------------------------------------------------

    /**
     * 把目标应用送上网守 display。
     *
     * <p>注意：这条命令**只负责启动**。有些应用（实测 B 站）的 exported 入口是闪屏，
     * 它自己再拉主界面时那一次启动不带 display，AMS 会把整条 root task 挪回默认屏；
     * 兜底靠看门（{@link #watch} + {@link #ping()}）。
     *
     * <p>另一条真实约束：只有 **exported** 的 Activity 能被 uid 2000 启动。
     * 传错会拿到 {@code SecurityException: not exported from uid}，所以这里把原始输出
     * 原样交给调用方，不假装成功。
     */
    public void launch(final int displayId, final String packageName, final String activityName,
                       final LaunchCallback callback) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!shell.isReady()) {
                    callback.onResult(false, "shell 通道未就绪");
                    return;
                }
                String out = shell.launchOn(displayId, packageName, activityName);
                if (out == null) {
                    callback.onResult(false, "启动命令执行失败（连接已丢弃，将自动重连）");
                    return;
                }
                String text = out.trim();
                boolean denied = text.contains("SecurityException")
                        || text.contains("Permission Denial");
                boolean error = text.contains("Error:") || text.contains("Exception");
                if (denied) {
                    Log.w(TAG, "启动被拒（Activity 未 exported）：" + text);
                    callback.onResult(false, "该 Activity 不允许外部启动：" + firstLine(text));
                    return;
                }
                if (error) {
                    Log.w(TAG, "启动失败：" + text);
                    callback.onResult(false, firstLine(text));
                    return;
                }
                callback.onResult(true, firstLine(text));
            }
        }, "dashcast-launch");
        worker.setDaemon(true);
        worker.start();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    // ---- 输入 --------------------------------------------------------------

    /**
     * 抓一帧画面（PNG 字节），失败返回 null。
     *
     * <p>抓的是 {@link #inputDisplay()} —— 也就是**镜像屏**而不是投屏屏。
     * 投屏屏（共享 3/4）是黑的中转屏，抓它只会得到全黑帧，判页必然失败。
     */
    public byte[] captureFrame() {
        int display = inputDisplay();
        if (display < 0) {
            Log.w(TAG, "抓帧被丢弃：还没设定目标 display");
            return null;
        }
        return shell.screencap(display);
    }

    /**
     * 触摸该发到哪个 display。
     *
     * <p>**投屏目标屏不是输入屏**：共享屏（display 3/4）只是个中转，容器服务
     * {@code AutoSharedDisplay} 把它镜像回主虚拟屏（display 2）时，**输入窗口也一并
     * 注册到了 display 2**。往投屏屏注入会被 InputDispatcher 直接丢掉：
     * {@code "no touched foreground window in display 3"}。
     *
     * <p>所以这里让 shell 从 {@code dumpsys input} 里读出窗口真实挂在哪个 display，
     * 而不是拿投屏屏硬发。没在看门（没有已知包）时无法按包解析，就兜底到主投影屏
     * —— 也就是仪表盘本身。
     */
    private int inputDisplay() {
        String pkg = watchedPackage;
        if (pkg != null) {
            // 兜底传 -1 而不是 currentDisplay：这里必须区分"真解析出来了"与"没解析出来"，
            // 解析不出来时绝不能顺手发到投屏槽位（那块屏上根本没有输入窗口）。
            int resolved = shell.inputDisplayFor(pkg, -1);
            if (resolved >= 0) {
                return resolved;
            }
        }
        // **绝不回退到投屏槽位**：槽位是黑的，往它注入会被 InputDispatcher 丢掉，
        // 抓它只会得到全黑帧 —— 那正是"主屏上看不到仪表盘画面"的成因。
        return projectionDisplay;
    }

    public void tap(float x, float y) {
        int display = inputDisplay();
        if (display < 0) {
            Log.w(TAG, "tap 被丢弃：还没设定目标 display");
            return;
        }
        shell.tap(display, x, y);
    }

    /**
     * 触摸。action 是 MotionEvent 的动作常量（0=DOWN, 1=UP, 2=MOVE…）。
     * 目标屏 touch=NONE，所有输入都得注入。
     */
    public void touch(int action, float x, float y, long downTime, long eventTime) {
        int display = inputDisplay();
        if (display < 0) {
            Log.w(TAG, "touch 被丢弃：还没设定目标 display");
            return;
        }
        String name;
        switch (action) {
            case 0:
                name = "DOWN";
                break;
            case 1:
                name = "UP";
                break;
            case 2:
                name = "MOVE";
                break;
            case 3:
                name = "CANCEL";
                break;
            default:
                name = "MOVE";
                break;
        }
        shell.touch(display, name, x, y);
    }

    public void key(int keyCode) {
        shell.key(keyCode);
    }

    // ---- 应用清单 ----------------------------------------------------------

    /**
     * 枚举可投屏应用。
     *
     * <p>清单必须在 uid 2000 里查（Android 11+ 包可见性按 uid 过滤，App 侧只有 24 个，
     * 实测 uid 2000 能拿到 126 条）。但 **label 拿不到** —— {@code dumpsys package}
     * 不含 label 文本，解析 resources.arsc 又要 aapt；所以这里用本进程的
     * {@code PackageManager} 尽力补，补不到就显示包名（这是已知的体验降级）。
     */
    public void listApps(final AppsCallback callback) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppEntry> raw = shell.apps();
                PackageManager pm = context.getPackageManager();
                List<AppRepo.Entry> out = new ArrayList<AppRepo.Entry>(raw.size());
                for (AppEntry e : raw) {
                    out.add(new AppRepo.Entry(labelOf(pm, e.packageName),
                            e.packageName, e.activityName));
                }
                Log.i(TAG, "代理下发可投屏应用 " + out.size() + " 个（shell 通道）");
                callback.onApps(out);
            }
        }, "dashcast-apps");
        worker.setDaemon(true);
        worker.start();
    }

    /** 尽力取应用名；受包可见性限制时退回包名。 */
    private static String labelOf(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
            // 包不可见：Android 11+ 的正常结果，不是错误
        }
        return packageName;
    }

    // ---- 界面预览 ----------------------------------------------------------

    /**
     * 预览代次：每次 start/stop 都自增，预览线程只认自己那一代。
     *
     * <p>必须用代次而不是一个布尔量：{@code startPreview} 会先 {@code stopPreview}，
     * 若两者只是翻转同一个布尔量，**上一代线程会读到刚被置回的新值继续跑**，
     * 结果是新旧两组线程同时抓帧、画面互相追赶。代次让旧线程必然看到"我不是当前代"。
     */
    private final java.util.concurrent.atomic.AtomicLong previewGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * **降级**预览：周期抓帧，把仪表盘画面送到界面。
     *
     * <p>正路是 {@link PrivilegedClient} + {@code SurfaceControl} 的 GPU 直通（原版
     * Just Dashboard 的做法），画面由 SurfaceFlinger 直接合成进界面，满帧且几乎不耗 CPU
     * （实测 1.5%）。抓帧只在直通不可用时启用：本车实测 3.2~3.5 fps、CPU 29%。
     *
     * <p>为什么还要留这条路：直通依赖"跨进程把 Surface 交给 uid 2000 进程"，
     * 任何一环被系统拒绝就只剩黑屏；抓帧只依赖 shell 通道，独立得多。
     * {@code screencap} 是**只读**操作，不改变设备任何状态。
     *
     * <p>为什么要**多条通道**：抓一帧的耗时几乎全在设备端 PNG 编码，实测单条串行是
     * 422 ms/帧（2.4 fps），而这一步在多核上可以并行 —— 3 条并发 177 ms/帧（5.6 fps）、
     * 6 条 131 ms/帧（7.6 fps）。所以这里起一组线程各自全速抓帧，谁先抓到谁就送界面，
     * **不做节拍控制**：限流交给界面的"忙则丢帧"，那里才是真正决定上屏速率的地方。
     *
     * <p>抓的屏取自 {@link #inputDisplay()}，**与触控注入的屏完全一致**。这一条必须
     * 守住：否则会出现"预览显示 A 屏、点击落在 B 屏"，那比没有预览更糟。
     */
    public void startPreview(final FrameCallback callback) {
        stopPreview();
        final long generation = previewGeneration.incrementAndGet();
        for (int i = 0; i < ShellChannel.PREVIEW_CHANNELS; i++) {
            final int channel = i;
            new Thread("dashcast-preview-" + channel) {
                @Override
                public void run() {
                    previewLoop(callback, generation, channel);
                }
            }.start();
        }
    }

    /**
     * 停止预览，并把预览连接全部还回去。
     *
     * <p>**不 join，也不 interrupt**：单次抓帧最长 4s（{@code SCREENCAP_TIMEOUT_MS}），
     * 等它会卡住调用方（通常是 UI 线程的 onPause）；而 interrupt 对阻塞在 socket 读上的
     * 线程本来也无效。真正的推进力是 {@link ShellChannel#closePreview()}：socket 一关，
     * 正在读帧的通道立刻抛错返回，代次自增让它们在下一轮检查时退出。
     *
     * <p>关连接是"后台不留资源"的关键一步：只停线程的话，界面已经不可见了，
     * 却还占着 {@code PREVIEW_CHANNELS} 个 adbd 会话。
     */
    public void stopPreview() {
        previewGeneration.incrementAndGet();
        shell.closePreview();
    }

    /**
     * 一条预览通道的抓帧循环：**全速跑到被停**，不 sleep。
     *
     * <p>{@code channel} 决定用连接组里的哪一条；每条连接在 adbd 侧是独立会话，
     * 所以它们是真的并行，而不是共享一把锁排队。
     */
    private void previewLoop(FrameCallback callback, long generation, int channel) {
        try {
            if (!shell.ensurePreview(context, channel)) {
                // 一条通道连不上不报错：其余通道可能正常，帧率低一点好过没有画面。
                Log.w(TAG, "预览通道 " + channel + " 未建立");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "预览通道 " + channel + " 建立失败", t);
            return;
        }
        boolean reported = false;
        while (previewGeneration.get() == generation) {
            int display = inputDisplay();
            if (display < 0) {
                callback.onFailed("还没定位到仪表盘，先投屏再看预览");
                return;
            }
            byte[] png = shell.screencapPreview(channel, display);
            if (png == null) {
                if (!reported) {
                    reported = true;
                    callback.onFailed("抓不到仪表盘画面");
                }
            } else {
                reported = false;
                callback.onFrame(png);
            }
        }
    }
}
