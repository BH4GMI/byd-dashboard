package com.byd.dashcast.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * uid 2000 注入代理：整个方案里**唯一**必须由特权 uid 承担的部分。
 *
 * 为什么必须有它：把触摸送到虚拟屏要调 InputManager.injectInputEvent()，那需要
 * android.permission.INJECT_EVENTS，而它是 signature|privileged，普通应用拿不到。
 * uid 2000 (com.android.shell) 在 DiLink 5 上持有该权限。
 * 其他路径都走不通：AccessibilityService.dispatchGesture 无法指定 displayId；
 * UiAutomation 只对 instrumentation 开放；input shell 命令同样要 uid 2000 才能跑。
 *
 * 为什么它这么小：它不建窗口、不建 VirtualDisplay、不碰 ContentResolver —— 那些都由
 * 已安装的 App 以自己的真实进程完成（那里字体、provider、ProcessRecord 一应俱全）。
 * 代理只做四件事：收坐标注入、用 am 把应用送上副屏、接管显示做镜像、把跑掉的任务搬回副屏。
 *
 * 进程间通道为什么是 Binder 而不是 LocalSocket：原版用的就是这条路
 * （b.smali:2403-2460 的 sendBroadcast(intent.putExtra(IBinder))，动作名
 * ACTION_communication_process_started；原版里的 LocalServerSocket 只当单实例锁用）。
 * LocalSocket 从 untrusted_app 连 shell 域是 SELinux 敏感路径，而 Binder 由内核驱动
 * 直接裁决，并且能顺便通过 Binder.getCallingUid() 把调用方钉死在本应用上。
 *
 * 句柄分发：代理每 3 秒广播一次自己的 Binder，直到 App 发来 PING 为止；
 * 若超过 5 秒没有收到任何调用（App 重启或被杀），自动恢复广播。
 * 这样不依赖"谁先启动"。
 */
public final class Agent {

    public static final String ACTION_AGENT_READY = "com.byd.dashcast.action.AGENT_READY";
    public static final String EXTRA_BINDER = "com.byd.dashcast.extra.AGENT_BINDER";
    public static final String DESCRIPTOR = "com.byd.dashcast.agent.AgentBinder";

    public static final int TRANSACT_PING = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACT_SET_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TRANSACT_TOUCH = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TRANSACT_KEY = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int TRANSACT_LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 4;
    public static final int TRANSACT_LIST_APPS = IBinder.FIRST_CALL_TRANSACTION + 5;
    public static final int TRANSACT_START_MIRROR = IBinder.FIRST_CALL_TRANSACTION + 6;
    public static final int TRANSACT_STOP_MIRROR = IBinder.FIRST_CALL_TRANSACTION + 7;
    public static final int TRANSACT_WATCH = IBinder.FIRST_CALL_TRANSACTION + 8;
    public static final int TRANSACT_UNWATCH = IBinder.FIRST_CALL_TRANSACTION + 9;
    public static final int TRANSACT_TASK_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 10;
    public static final int TRANSACT_MOVE_TO_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 11;

    /**
     * 信任锚：母工程 dashcast 的包名。
     *
     * 代理只服务「与锚点同签名的应用」，而不是只服务锚点本身。这样派生的共存版
     * （不同包名、同一个签名）装上就能用，不需要再改代理、也不需要重启代理。
     * 信任边界仍然是签名：换签名就等于换信任域，这正是想要的行为。
     */
    private static final String ANCHOR_PACKAGE = "com.byd.dashcast";

    /** 信任的客户端包名，启动时解析一次；同一签名的应用都算。 */
    private static Set<String> trustedPackages = Collections.emptySet();

    /** 信任的客户端 uid 集合，由 {@link #trustedPackages} 推出，调用裁决查这里。 */
    private static Set<Integer> trustedUids = Collections.emptySet();
    private static final long CONTACT_TIMEOUT_MS = 5000;
    /**
     * 看门巡检间隔。主循环本来就是轮询节拍（广播 Binder 的兜底），把巡检挂在同一节拍上，
     * 不额外引入线程或定时器。1 秒是"点视频后多久回到仪表屏"的直接延迟。
     */
    private static final long WATCH_INTERVAL_MS = 1000;

    /**
     * 搬完之后的静默期。防止"应用自己搬走 → 本程序搬回 → 应用再搬走"打成每秒一次的拉锯，
     * 那会让仪表屏持续闪烁。静默期内只记日志不动手，所以拉锯仍然看得见。
     */
    private static final long WATCH_SETTLE_MS = 3000;

    /**
     * 看门的自动松开时间：目标在目标屏上连续稳定这么久，就认为"这次投放引起的搬屏已经了结"，
     * 撤销看门，把控制权还给用户。
     *
     * 为什么必须有：看门会一直把目标搬回目标屏。用户在主屏点该应用图标时 AMS 会把任务切到
     * 主屏，看门会在 1 秒内再把它搬回来——表现就是"按了回不到前台"。看门只该修补
     * "本程序那次启动引起的搬屏"，没有理由永久生效。
     */
    private static final long WATCH_STABLE_MS = 5000;

    /**
     * 看门在客户端失联后多久撤销。比镜像回收短得多：看门是"用户正在操作"的临时状态，
     * 客户端不在场就该立刻松手——否则被系统杀掉的界面会在后台继续把应用钉在目标屏上。
     */
    private static final long WATCH_IDLE_TIMEOUT_MS = 5000;

    /**
     * 客户端失联超过这个时间就回收镜像显示，避免 App 崩溃时泄漏在 SurfaceFlinger 里。
     * 不能设短：App 一切到后台心跳就停，10 秒会把正常后台的镜像也回收掉。
     * 正常情况下 App 在 onPause 就会主动 STOP_MIRROR，这里只是崩溃兜底。
     */
    private static final long MIRROR_IDLE_TIMEOUT_MS = 60000;

    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method motionSetDisplayId;
    private static Method keySetDisplayId;

    private static volatile int displayId = -1;
    private static volatile long lastContact;

    // ---- 看门状态 ----------------------------------------------------------
    /** 要钉在仪表屏上的包名；null 表示不看门。 */
    private static volatile String watchedPackage;
    private static volatile int watchedDisplay = -1;
    private static volatile long lastMoveUptime;
    private static volatile int watchMoves;
    private static volatile String watchNote = "";
    /** 目标在目标屏上连续稳定的起点；0 表示还没开始计。 */
    private static volatile long watchStableSince;

    private static Method amGetService;
    private static Method getAllRootTaskInfos;
    private static Method atmGetService;
    private static Method moveRootTaskToDisplay;
    private static Method componentGetPackageName;
    private static boolean taskPrimitivesReady;

    public static void main(String[] args) throws Exception {
        bypassHiddenApi();
        L.i("启动，uid=" + Process.myUid() + " pid=" + Process.myPid());

        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        systemMain.invoke(null);
        Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
        currentApplication.setAccessible(true);
        Context context = (Context) currentApplication.invoke(null);

        trustedPackages = resolveTrustedPackages(context);
        if (trustedPackages.isEmpty()) {
            L.e("找不到 " + ANCHOR_PACKAGE + "，拒绝为任何客户端服务", null);
            return;
        }
        L.i("信任的客户端包（与 " + ANCHOR_PACKAGE + " 同签名）：" + trustedPackages);
        trustedUids = uidsOf(context, trustedPackages);

        initInput();
        initMirrorPrimitives();
        initTaskPrimitives();

        final AgentBinder binder = new AgentBinder(context);
        L.i("开始广播 Binder，动作 " + ACTION_AGENT_READY);

        while (true) {
            long now = SystemClock.uptimeMillis();
            long idle = now - lastContact;
            if (idle > CONTACT_TIMEOUT_MS) {
                broadcast(context, binder);
            }
            if (mirrorToken != null && idle > MIRROR_IDLE_TIMEOUT_MS) {
                L.i("客户端已失联 " + idle + "ms，回收镜像显示");
                stopMirror();
            }
            // 客户端不在了就停止看门：否则用户手动把任务搬回主屏后，
            // 会被一个没有主人在场的代理反复拽回仪表屏。
            if (watchedPackage != null && idle > WATCH_IDLE_TIMEOUT_MS) {
                L.i("客户端已失联 " + idle + "ms，停止看门（" + watchedPackage + "）");
                watchedPackage = null;
                watchNote = "已停止看门";
            }
            checkWatch(now);
            Thread.sleep(WATCH_INTERVAL_MS);
        }
    }

    private static void broadcast(Context context, IBinder binder) {
        // 逐个点名发给信任的客户端：不用隐式广播，避免设备上其它注册了同名 action 的
        // 应用白拿一个 Binder 句柄（虽然它们调不动，但没必要给）。
        for (String client : trustedPackages) {
            try {
                Bundle extras = new Bundle();
                extras.putBinder(EXTRA_BINDER, binder);
                Intent intent = new Intent(ACTION_AGENT_READY);
                intent.setPackage(client);
                intent.putExtras(extras);
                context.sendBroadcast(intent);
            } catch (Throwable t) {
                L.e("向 " + client + " 广播 Binder 失败", t);
            }
        }
    }

    /**
     * 解析信任的客户端包：枚举已安装应用，取签名与锚点相同的那些。
     *
     * 为什么不写死包名清单：分支会越加越多，写死清单意味着每加一个分支都要重新编译并重启
     * 代理；而"同签名"是 Android 自己的信任模型，装上即生效。解析不出来时退化成只信任
     * 锚点本身——宁可少服务，不可多服务。
     */
    private static Set<String> resolveTrustedPackages(Context context) {
        Set<String> out = new LinkedHashSet<String>();
        try {
            PackageManager packageManager = context.getPackageManager();
            Signature[] anchor = signaturesOf(packageManager, ANCHOR_PACKAGE);
            if (anchor == null) {
                return out;
            }
            out.add(ANCHOR_PACKAGE);
            for (PackageInfo info : packageManager.getInstalledPackages(
                    PackageManager.GET_SIGNING_CERTIFICATES)) {
                if (info == null || info.packageName == null
                        || ANCHOR_PACKAGE.equals(info.packageName)) {
                    continue;
                }
                if (sameSignatures(anchor, info.signingInfo == null
                        ? null : info.signingInfo.getApkContentsSigners())) {
                    out.add(info.packageName);
                }
            }
        } catch (Throwable t) {
            L.e("解析信任客户端失败，只信任锚点 " + ANCHOR_PACKAGE, t);
        }
        if (out.isEmpty()) {
            out.add(ANCHOR_PACKAGE);
        }
        return out;
    }

    private static Signature[] signaturesOf(PackageManager packageManager, String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } catch (Throwable t) {
            L.e("读取 " + packageName + " 的签名失败", t);
            return null;
        }
    }

    private static boolean sameSignatures(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!Arrays.equals(a[i].toByteArray(), b[i].toByteArray())) {
                return false;
            }
        }
        return true;
    }

    /** 信任包的 uid 集合；调用裁决按 uid 做，比每次比对签名便宜。 */
    private static Set<Integer> uidsOf(Context context, Set<String> packages) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        PackageManager packageManager = context.getPackageManager();
        for (String name : packages) {
            try {
                out.add(packageManager.getApplicationInfo(name, 0).uid);
            } catch (Throwable t) {
                L.e("解析 " + name + " 的 uid 失败", t);
            }
        }
        return out;
    }

    /** 调用方 uid 是否属于信任的客户端。 */
    private static boolean isTrustedCaller(int uid) {
        return trustedUids.contains(uid);
    }

    /** 只认信任客户端的 uid；任何其它 uid 的调用直接拒绝。 */
    private static final class AgentBinder extends Binder {

        private final Context context;

        AgentBinder(Context context) {
            this.context = context;
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            int callingUid = Binder.getCallingUid();
            if (!isTrustedCaller(callingUid)) {
                L.e("拒绝 uid=" + callingUid + " 的调用 code=" + code, null);
                return false;
            }
            lastContact = SystemClock.uptimeMillis();
            data.enforceInterface(DESCRIPTOR);

            switch (code) {
                case TRANSACT_PING: {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(Process.myUid());
                        reply.writeInt(displayId);
                        // 看门状态，供 App 状态栏显示；App 与代理必须成对升级。
                        reply.writeInt(watchedPackage != null ? 1 : 0);
                        reply.writeInt(watchMoves);
                        reply.writeString(watchNote);
                        // 正在看门的包名：界面据此认领代理侧残留的看门，并撤销它。
                        reply.writeString(watchedPackage);
                    }
                    return true;
                }
                case TRANSACT_SET_DISPLAY: {
                    displayId = data.readInt();
                    L.i("目标 display 设为 " + displayId);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACT_TOUCH: {
                    int action = data.readInt();
                    float x = data.readFloat();
                    float y = data.readFloat();
                    long downTime = data.readLong();
                    long eventTime = data.readLong();
                    injectTouch(action, x, y, downTime, eventTime);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACT_KEY: {
                    injectKey(data.readInt());
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACT_LAUNCH: {
                    int target = data.readInt();
                    String packageName = data.readString();
                    String activityName = data.readString();
                    String[] result = launch(target, packageName, activityName);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(Integer.parseInt(result[0]));
                        reply.writeString(result[1]);
                    }
                    return true;
                }
                case TRANSACT_LIST_APPS: {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStringList(listApps(context));
                    }
                    return true;
                }
                case TRANSACT_TASK_DISPLAY: {
                    String pkg = data.readString();
                    int found = taskDisplayOf(pkg);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(found);
                    }
                    return true;
                }
                case TRANSACT_MOVE_TO_DISPLAY: {
                    String pkg = data.readString();
                    int target = data.readInt();
                    int[] moved = moveTasksOf(pkg, target);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(moved[0]);
                        reply.writeInt(moved[1]);
                    }
                    return true;
                }
                case TRANSACT_START_MIRROR: {
                    Surface surface = data.readParcelable(Surface.class.getClassLoader());
                    String error = startMirror(surface);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(error);
                    }
                    return true;
                }
                case TRANSACT_STOP_MIRROR: {
                    stopMirror();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACT_WATCH: {
                    String pkg = data.readString();
                    int target = data.readInt();
                    watchedPackage = pkg;
                    watchedDisplay = target;
                    watchMoves = 0;
                    lastMoveUptime = 0;
                    watchStableSince = 0;
                    watchNote = pkg == null ? "" : "看门中";
                    L.i("开始看门：" + pkg + " 必须留在 display " + target);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACT_UNWATCH: {
                    if (watchedPackage != null) {
                        L.i("停止看门：" + watchedPackage);
                    }
                    watchedPackage = null;
                    watchStableSince = 0;
                    watchNote = "";
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    // ---- 注入 --------------------------------------------------------------

    private static void initInput() throws Exception {
        Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
        Method getInstance = inputManagerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        inputManager = getInstance.invoke(null);

        injectInputEvent =
                inputManagerClass.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        injectInputEvent.setAccessible(true);

        motionSetDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
        motionSetDisplayId.setAccessible(true);

        keySetDisplayId = KeyEvent.class.getDeclaredMethod("setDisplayId", int.class);
        keySetDisplayId.setAccessible(true);
        L.i("InputManager 就绪：" + inputManager);
    }

    private static void injectTouch(int action, float x, float y, long downTime, long eventTime) {
        if (displayId < 0) {
            L.e("丢弃触摸：目标 display 未设置", null);
            return;
        }
        MotionEvent event = null;
        try {
            event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            motionSetDisplayId.invoke(event, displayId);
            Object accepted = injectInputEvent.invoke(inputManager, event, 0);
            if (action != MotionEvent.ACTION_MOVE) {
                L.i("注入 action=" + action + " (" + x + "," + y + ") display=" + displayId
                        + " -> " + accepted);
            }
        } catch (Throwable t) {
            L.e("注入触摸失败 action=" + action + " (" + x + "," + y + ")", t);
        } finally {
            if (event != null) {
                event.recycle();
            }
        }
    }

    private static void injectKey(int keyCode) {
        if (displayId < 0) {
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            keySetDisplayId.invoke(down, displayId);
            injectInputEvent.invoke(inputManager, down, 0);
            KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, keyCode, 0);
            keySetDisplayId.invoke(up, displayId);
            injectInputEvent.invoke(inputManager, up, 0);
            L.i("注入按键 " + keyCode + " -> display " + displayId);
        } catch (Throwable t) {
            L.e("注入按键 " + keyCode + " 失败", t);
        }
    }

    // ---- 应用清单 ----------------------------------------------------------

    /**
     * 必须在 uid 2000 里枚举，这是原版的做法（c0/f.smali:6565 用的是傀儡自己的
     * Application.getPackageManager()）。原因：Android 11 起的包可见性过滤是按
     * uid 生效的，普通 App 即使 targetSdk 32 也只能看到自己能"看见"的那一小撮包，
     * 而 uid 2000 不受该过滤约束。
     *
     * 返回扁平三元组：label, packageName, activityName。
     */
    private static List<String> listApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        // 按包去重：一个包只出一行，优先保留带 LAUNCHER 类别的入口。
        Map<String, String[]> byPackage = new LinkedHashMap<String, String[]>();

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        collect(packageManager, packageManager.queryIntentActivities(launcherIntent, 0),
                byPackage, false);

        // 车机上大量应用只声明 ACTION_MAIN 而**没有** LAUNCHER 类别，只查 LAUNCHER 会漏掉
        // 一大半，所以要放开类别，不能只认 MAIN+LAUNCHER。
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        collect(packageManager, packageManager.queryIntentActivities(mainIntent, 0),
                byPackage, true);

        List<String[]> entries = new ArrayList<String[]>(byPackage.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(entries, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return collator.compare(a[0], b[0]);
            }
        });

        List<String> flat = new ArrayList<String>(entries.size() * 3);
        for (String[] entry : entries) {
            flat.add(entry[0]);
            flat.add(entry[1]);
            flat.add(entry[2]);
        }
        L.i("枚举到 " + entries.size() + " 个可投屏应用");
        return flat;
    }

    private static void collect(PackageManager packageManager, List<ResolveInfo> resolved,
            Map<String, String[]> byPackage, boolean requireExported) {
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.packageName == null || activity.name == null) {
                continue;
            }
            // 只声明 ACTION_MAIN 的条目里混着大量不可导出的内部页面，跨 uid 起不来，滤掉。
            if (requireExported && !activity.exported) {
                continue;
            }
            if (trustedPackages.contains(activity.packageName)
                    || byPackage.containsKey(activity.packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packageManager);
            byPackage.put(activity.packageName, new String[]{
                    label == null ? activity.name : label.toString(),
                    activity.packageName,
                    activity.name});
        }
    }

    // ---- 仪表盘镜像 ---------------------------------------------------------

    /**
     * 把仪表盘那块屏（display 2）的画面镜像到 App 给的 Surface 上，主屏就能看到它。
     *
     * 做法取自 SurfaceFlinger 自己的镜像是怎么做的：造一个可编程显示，把它的输出接到
     * 本程序的 Surface，再让它的 layerStack 指向源屏的 layerStack。原版也是这套——
     * c0/l;->a(int displayId, Surface surface, Rect layerStackRect, Rect displayRect)
     * 的参数就是 (源屏, 输出 Surface, 图层栈矩形, 显示矩形)，并且它会反射读
     * DisplayInfo.layerStack（c0/l.smali:2481 `iget v1, v4, Landroid/view/DisplayInfo;->layerStack:I`）。
     *
     * 这些方法全部是 @hide，公共 SDK 里没有声明，只能反射；uid 2000 已确认持有
     * ACCESS_SURFACE_FLINGER（signature|privileged），createDisplay 可用。
     */
    private static Method createDisplay;
    private static Method destroyDisplay;
    private static Method openTransaction;
    private static Method closeTransaction;
    private static Method setDisplaySurface;
    private static Method setDisplaySize;
    private static Method setDisplayProjection;
    private static Method setDisplayLayerStack;

    private static IBinder mirrorToken;

    private static void initMirrorPrimitives() {
        try {
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            createDisplay = surfaceControl.getMethod("createDisplay", String.class, boolean.class);
            destroyDisplay = surfaceControl.getMethod("destroyDisplay", IBinder.class);
            openTransaction = surfaceControl.getMethod("openTransaction");
            closeTransaction = surfaceControl.getMethod("closeTransaction");
            setDisplaySurface =
                    surfaceControl.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            setDisplaySize =
                    surfaceControl.getMethod("setDisplaySize", IBinder.class, int.class, int.class);
            setDisplayProjection = surfaceControl.getMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setDisplayLayerStack =
                    surfaceControl.getMethod("setDisplayLayerStack", IBinder.class, int.class);
            L.i("显示接管原语就绪");
        } catch (Throwable t) {
            L.e("缺少显示接管原语，镜像不可用", t);
        }
    }

    /** 返回 null 表示成功，否则是给用户看的失败原因。 */
    private static String startMirror(Surface surface) {
        stopMirror();
        if (setDisplaySurface == null) {
            return "缺少显示接管原语";
        }
        if (displayId < 0) {
            return "目标 display 未设置";
        }
        if (surface == null || !surface.isValid()) {
            return "预览 Surface 无效";
        }
        try {
            Object info = displayInfo(displayId);
            if (info == null) {
                return "读不到 display " + displayId + " 的 DisplayInfo";
            }
            int width = intField(info, "logicalWidth");
            int height = intField(info, "logicalHeight");
            int layerStack = intField(info, "layerStack");
            L.i("镜像源 display=" + displayId + " " + width + "x" + height
                    + " layerStack=" + layerStack);

            IBinder token = (IBinder) createDisplay.invoke(null, "dashcast-mirror", false);
            openTransaction.invoke(null);
            try {
                setDisplaySurface.invoke(null, token, surface);
                setDisplaySize.invoke(null, token, width, height);
                setDisplayProjection.invoke(null, token, 0,
                        new Rect(0, 0, width, height), new Rect(0, 0, width, height));
                setDisplayLayerStack.invoke(null, token, layerStack);
            } finally {
                closeTransaction.invoke(null);
            }
            mirrorToken = token;
            L.i("镜像显示已建立：" + token);
            return null;
        } catch (Throwable t) {
            L.e("建立镜像失败", t);
            stopMirror();
            return String.valueOf(t);
        }
    }

    private static void stopMirror() {
        IBinder token = mirrorToken;
        if (token == null) {
            return;
        }
        mirrorToken = null;
        try {
            destroyDisplay.invoke(null, token);
            L.i("镜像显示已销毁");
        } catch (Throwable t) {
            L.e("销毁镜像显示失败", t);
        }
    }

    /** DisplayInfo 是 @hide 类，字段也是 @hide，只能反射读。 */
    private static Object displayInfo(int id) throws Exception {
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Method getInstance = global.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object instance = getInstance.invoke(null);
        Method getDisplayInfo = global.getDeclaredMethod("getDisplayInfo", int.class);
        getDisplayInfo.setAccessible(true);
        return getDisplayInfo.invoke(instance, id);
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    // ---- 启动应用到副屏 ------------------------------------------------------

    /**
     * 必须由 uid 2000 来启动，普通应用做不到：ActivityStarter 会拒绝把
     * 非多屏能力（supportsMultiDisplay）的应用放到副屏上，报错为
     *   SecurityException: Permission Denial: starting Intent { ... }
     *   from ProcessRecord{...} (uid=10096) with launchDisplayId=N
     * 而 shell 走 am start-activity 时 AMS 不做这项限制。
     * 原版也正是这么做的：见 com/bumptech/glide/e.smali 里的
     *   "am start-activity -S -W -n $(dumpsys package ...)"。
     */
    private static String[] launch(int target, String packageName, String activityName) {
        String command = "am start-activity --display " + target
                + " -n " + packageName + "/" + activityName;
        L.i("执行：" + command);
        try {
            // 全限定名：本文件已导入 android.os.Process，裸 Process 会撞名。
            java.lang.Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            int exitCode = process.waitFor();
            L.i("am 退出码 " + exitCode + " 输出：" + output.toString().trim());
            return new String[]{String.valueOf(exitCode), output.toString()};
        } catch (Throwable t) {
            L.e("执行 am 失败", t);
            return new String[]{"-1", String.valueOf(t)};
        }
    }

    // ---- 看门：把指定应用钉在仪表屏 -----------------------------------------

    /**
     * 为什么要看门：应用**自己**发起的 Activity 启动不带 display，它自己发的 Intent
     * 会触发 AMS 判定缓存的 display 已失效
     * （`LaunchParamsPersister: needRemoveTaskCacheParams display =null`），于是整条
     * root task 被挪到默认屏（display 0）——投屏当场丢失，用户得手动搬回。
     *
     * 这是应用侧行为，**本程序无法预防**：那个 Intent 由目标应用自己发出，本程序塞不进
     * launchDisplayId。所以只能事后搬回。原版也是这么做的，见
     * c0/k.smali:42201/42244/42250 的 `"move stack %d task %d to display %d"` 与
     * :45517 的 `"moveRootTaskToDisplay"`，以及 :1397-1456 的 `getAllRootTaskInfos` 枚举。
     *
     * 两个符号已在设备的 framework.jar 上逐个核实过（拉 /system/framework/framework.jar 用
     * dexdump 查的，不是靠记忆）：
     *   android.app.IActivityManager      .getAllRootTaskInfos()          -> List
     *   android.app.IActivityTaskManager  .moveRootTaskToDisplay(int,int)  -> void
     * 注意这两个在**不同的 service** 上，取句柄分别走
     * ActivityManager.getService() 与 ActivityTaskManager.getService()。
     *
     * 一个必须注意的坑：`RootTaskInfo` 自己**没有** taskId / topActivity / displayId，
     * 它们声明在父类 `android.app.TaskInfo` 上（RootTaskInfo 自身字段只有
     * bounds / childTaskBounds / childTaskIds / childTaskNames / childTaskUserIds /
     * position / visible）。原版 smali 写 `RootTaskInfo;->displayId` 是字节码按静态类型
     * 发的引用，运行时解析到父类。所以这里读字段必须沿父类链走。
     */
    private static void initTaskPrimitives() {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            amGetService = am.getDeclaredMethod("getService");
            amGetService.setAccessible(true);

            Class<?> iam = Class.forName("android.app.IActivityManager");
            getAllRootTaskInfos = iam.getMethod("getAllRootTaskInfos");
            getAllRootTaskInfos.setAccessible(true);

            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            atmGetService = atm.getDeclaredMethod("getService");
            atmGetService.setAccessible(true);

            Class<?> iatm = Class.forName("android.app.IActivityTaskManager");
            moveRootTaskToDisplay = iatm.getMethod("moveRootTaskToDisplay", int.class, int.class);
            moveRootTaskToDisplay.setAccessible(true);

            componentGetPackageName = ComponentName.class.getMethod("getPackageName");

            taskPrimitivesReady = true;
            L.i("看门原语就绪：getAllRootTaskInfos + moveRootTaskToDisplay");
        } catch (Throwable t) {
            taskPrimitivesReady = false;
            watchNote = "缺少看门原语";
            L.e("缺少看门原语，任务不会自动搬回仪表屏", t);
        }
    }

    /**
     * 找到"顶层 Activity 属于该包"的 root task，没有就返回 null。
     * 看门巡检与屏位查询共用这一套枚举条件，免得两处判断漂移。
     */
    private static Object findRootTaskOf(String pkg) throws Exception {
        Object service = amGetService.invoke(null);
        List<?> tasks = (List<?>) getAllRootTaskInfos.invoke(service);
        if (tasks == null) {
            return null;
        }
        for (Object task : tasks) {
            if (task == null) {
                continue;
            }
            Object top = fieldOf(task, "topActivity");
            if (top instanceof ComponentName
                    && pkg.equals(componentGetPackageName.invoke(top))) {
                return task;
            }
        }
        return null;
    }

    /**
     * 该包的 root task 现在在哪块屏。客户端靠它判断"应用是否已经真的回到仪表屏"——
     * 启动之后任务会先跳到主屏、看门再搬回来，在那之前注入点击等于点在空处。
     *
     * @return 屏号；-1 表示没有该任务；-2 表示缺少原语（此时调用方不该死等）
     */
    private static int taskDisplayOf(String pkg) {
        if (!taskPrimitivesReady || pkg == null) {
            return -2;
        }
        try {
            Object task = findRootTaskOf(pkg);
            return task == null ? -1 : (Integer) fieldOf(task, "displayId");
        } catch (Throwable t) {
            L.e("查询任务屏位失败", t);
            return -2;
        }
    }

    /** 主循环每个节拍调一次；不在看门状态、原语缺失或处于静默期时立即返回。 */
    private static void checkWatch(long now) {
        String pkg = watchedPackage;
        if (pkg == null || !taskPrimitivesReady || watchedDisplay < 0) {
            return;
        }
        if (now - lastMoveUptime < WATCH_SETTLE_MS) {
            return;
        }
        int[] result = moveTasksOf(pkg, watchedDisplay);
        if (result[0] == -2) {
            watchNote = "看门巡检失败";
            return;
        }
        if (result[1] > 0) {
            // 有任务被搬回来：进静默期，避免和"应用自己搬走"打成每秒一次的拉锯。
            watchMoves += result[1];
            watchNote = "已搬回 " + watchMoves + " 次";
            lastMoveUptime = now;
            watchStableSince = 0;
            return;
        }
        if (result[0] < 0 && !"看门中".equals(watchNote)) {
            watchNote = "看门中";
        }
        // 搬动过又稳住了，或目标当前不存在——都算稳定，开始计时松开。
        releaseWatchIfStable(now, pkg);
    }

    /**
     * 看门自动松开：目标稳定 {@link #WATCH_STABLE_MS} 之后撤销。
     *
     * "目标当前不存在"也算稳定：目标一直没被启动时，看门没有理由一直挂着。
     */
    private static void releaseWatchIfStable(long now, String pkg) {
        if (watchStableSince == 0) {
            watchStableSince = now;
            return;
        }
        if (now - watchStableSince < WATCH_STABLE_MS) {
            return;
        }
        L.i("看门：目标已稳定 " + (now - watchStableSince) + "ms，自动松开（" + pkg + "）");
        watchedPackage = null;
        watchStableSince = 0;
        watchNote = "已松开看门";
    }

    /**
     * 把该包**所有** root task 都搬到 target 屏。
     *
     * 为什么不直接用 am start：`am start-activity --display N` 在目标屏上没有该包的任务时
     * 会**新建**一条 root task，于是同一个应用会在两块屏上各跑一份。已经有任务时把它搬过去
     * 才是对的。看门巡检也复用这个方法，所以看门天然会管住重复任务。
     *
     * @return {第一个任务当前的屏位（没有任务为 -1，原语缺失为 -2）, 本次搬动的任务数}
     */
    private static int[] moveTasksOf(String pkg, int target) {
        if (!taskPrimitivesReady || pkg == null) {
            return new int[]{-2, 0};
        }
        int firstFrom = -1;
        int moved = 0;
        try {
            Object service = amGetService.invoke(null);
            List<?> tasks = (List<?>) getAllRootTaskInfos.invoke(service);
            if (tasks == null) {
                return new int[]{-1, 0};
            }
            Object atm = atmGetService.invoke(null);
            for (Object task : tasks) {
                if (task == null) {
                    continue;
                }
                Object top = fieldOf(task, "topActivity");
                if (!(top instanceof ComponentName)
                        || !pkg.equals(componentGetPackageName.invoke(top))) {
                    continue;
                }
                int from = (Integer) fieldOf(task, "displayId");
                if (firstFrom < 0) {
                    firstFrom = from;
                }
                if (from == target) {
                    continue;
                }
                int taskId = (Integer) fieldOf(task, "taskId");
                moveRootTaskToDisplay.invoke(atm, taskId, target);
                moved++;
                L.i("搬屏：" + pkg + " 的 root task " + taskId
                        + " 从 display " + from + " 搬到 display " + target);
            }
        } catch (Throwable t) {
            L.e("搬屏失败", t);
            return new int[]{-2, moved};
        }
        return new int[]{firstFrom, moved};
    }

    /** 沿父类链找字段：RootTaskInfo 的字段实际声明在其父类 TaskInfo 上。 */
    private static Object fieldOf(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(
                name + " 在 " + target.getClass().getName() + " 及其父类上都不存在");
    }

    private static void bypassHiddenApi() throws Exception {
        Method forName = Class.class.getDeclaredMethod("forName", String.class);
        Method getDeclaredMethod =
                Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
        Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
        Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", (Object) null);
        Method setExemptions = (Method) getDeclaredMethod.invoke(
                vmRuntime, "setHiddenApiExemptions", new Class<?>[]{String[].class});
        setExemptions.invoke(getRuntime.invoke(null), (Object) new String[]{"L"});
    }

    private Agent() {
    }
}
