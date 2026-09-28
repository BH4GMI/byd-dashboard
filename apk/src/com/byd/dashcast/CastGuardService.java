package com.byd.dashcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;

/**
 * 投屏守位服务：**投屏一旦成立，屏位不变量由它持有，而不是由发起投屏的界面持有。**
 *
 * <h3>为什么必须脱离界面（2026-09-24 实车结论）</h3>
 *
 * 原来的看门挂在 {@link CastActivity} 的心跳线程上，`onPause`/`onDestroy` 就松开。
 * 首开自动那条链路**没有界面**，跑完立刻 `finish()`，于是从链路结束那一刻起就没人再看屏位；
 * 目标应用只要再来一次"不带 display"的内部启动，AMS 就把整条 root task 挪回主驾屏 ——
 * 实测正是这样丢的投屏（登录页/闪屏交接、应用内部跳转都会触发）。
 *
 * <p>换成服务之后，界面在不在都不影响：只要投屏还在，就有人每 {@link #INTERVAL_MS}
 * 巡检一次，发现跑掉就搬回并回查。
 *
 * <h3>"跑掉"有两种相反的成因，搬回只对其中一种正确</h3>
 *
 * AMS 把目标任务挪回主屏，可能是应用自己跳的，也可能是**用户自己叫的**：
 *
 * <ul>
 *   <li>应用发起了一次不带 display 的启动（闪屏交接、应用内跳转、点视频卡）→ AMS 按默认屏
 *       解析 → 任务落到主屏。用户没动过它，必须搬回。</li>
 *   <li>用户在桌面上点了这个应用的图标 → AMS 记录
 *       {@code launchedFromUid=1000 launchedFromPackage=com.android.launcher3}（本机实测原文），
 *       任务被带到用户面前。**这时搬回就是撤销用户自己的操作** —— 用户看到的是"点图标
 *       回不到主屏、只闪一下"，这正是 2026-09-25 报的那个缺陷。</li>
 * </ul>
 *
 * <p>两种情况的最终状态一模一样（任务在主屏前台），所以**只按屏位判断永远分不开**。
 * 能分开的只有"这次启动是谁发起的"，而这个事实 AMS 每条启动都会在日志里留一条
 * （带发起 uid、Intent、以及只有点图标才有的 {@code bnds}）。判据因此落成一条独立规则：
 * **用户点桌面图标 ⇒ 投屏按用户意图结束**（把目标留在主屏、服务停止），而不是搬回。
 * 判据的出处、为什么取窄、以及通道失效时为什么是安全的，见 {@link ActivityStartLog}。
 *
 * <p>顺序上这条判断**必须排在归位之前**：晚一步就会先把任务拽回仪表屏，再谈"尊重用户意图"
 * 已经没有意义了。
 *
 * <h3>用户怎么收回（这是"有界性"的正解）</h3>
 *
 * 旧实现用**时间窗口**（20s，最多 45s）来保证"用户随时能夺回控制权"，代价是投屏会自己过期。
 * 这里换成正解：**给用户两个明确的收回入口** ——
 *
 * <ul>
 *   <li>常驻通知上的「收回投屏」按钮（{@link #ACTION_RELEASE}）：把目标搬回主屏后服务自己停；</li>
 *   <li>**用户在桌面上点这个应用的图标**：那本来就是"我要在中控上用"的意思，投屏随之结束
 *       （判据见 {@link ActivityStartLog}，实现在 {@link #userCalledItBack}）。</li>
 * </ul>
 *
 * <p>服务还会在两种情况自动收工，都不需要用户操作：
 * <ul>
 *   <li>目标应用的任务没了（用户在别处把它结束掉了）；</li>
 *   <li>目标应用被卸载/不可见了。</li>
 * </ul>
 *
 * <p>另外它**不会跟链路抢活**：链路自己也在做同样的归位（方向一致，重复搬回是幂等的）。
 */
public final class CastGuardService extends Service {

    private static final String TAG = "dashcast";

    /** 启动/重设守位目标。 */
    public static final String ACTION_GUARD = "com.byd.dashcast.action.GUARD";
    /** 用户主动收回投屏（通知按钮）。 */
    public static final String ACTION_RELEASE = "com.byd.dashcast.action.RELEASE";

    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_DISPLAY = "display";
    public static final String EXTRA_LABEL = "label";

    private static final String CHANNEL_ID = "dashcast-guard";
    private static final int NOTIFICATION_ID = 0x0DA5;

    /** 巡检间隔。2s 与旧心跳一致：一次 dumpsys 设备端约 0.03s，不构成负担。 */
    private static final long INTERVAL_MS = 2000L;
    /** 单次归位的等待上限。 */
    private static final long MOVE_TIMEOUT_MS = 3000L;
    /** 收回时把目标搬回主屏的等待上限。 */
    private static final long RELEASE_TIMEOUT_MS = 3000L;

    private InjectClient injector;

    /**
     * 用到才建。
     *
     * <p>**不能在字段初始化里 new**：Service 的字段初始化发生在
     * {@code AppComponentFactory.instantiateService()} 内部，早于 {@code attachBaseContext()}；
     * 那一刻 {@code this} 还不是可用的 Context，而 {@link InjectClient} 的构造函数要取
     * {@code applicationContext} —— 实测直接抛
     * {@code NullPointerException: getApplicationContext() on a null object reference}，
     * 服务起不来（2026-09-24）。组件对 Context 的依赖必须放在 onCreate 之后。
     */
    private InjectClient injector() {
        if (injector == null) {
            injector = new InjectClient(this);
        }
        return injector;
    }
    private HandlerThread thread;
    private Handler handler;

    private volatile String targetPackage;
    private volatile int guardDisplay = -1;
    private volatile String targetLabel = "";

    /**
     * 投屏成立时刻（设备墙钟）。只认这之后的启动记录 —— 否则用户投屏之前那次"点图标"
     * 会把刚成立的投屏立刻释放掉。
     */
    private volatile long establishedAtMs;

    /**
     * 不算"外部发起"的 uid：root(0) / shell(2000) / 本应用 / 目标应用自己。
     *
     * <p>目标应用自己那条不能省：应用把 `getIntent()` 再启动一次时，那条 Intent 上还带着
     * 桌面给的 {@code bnds} 与 LAUNCHER 分类，只有发起 uid 能把它与"用户点图标"分开。
     */
    private volatile int[] ignoredUids = new int[0];

    /** "读不到启动记录"只报一次，避免每 2s 刷一行。 */
    private volatile boolean startLogUnreadable;

    /** 目标任务的最近一次巡检结果，只用于日志/诊断。 */
    private final Runnable patrol = new Runnable() {
        @Override
        public void run() {
            String pkg = targetPackage;
            int display = guardDisplay;
            if (pkg == null || display < 0) {
                stopSelf();
                return;
            }
            // ① 先问"用户是不是自己把它叫回来了"。**必须在归位之前问**：
            //    一旦先搬回仪表屏，用户的操作就已经被撤销了，再谈尊重用户意图没有意义。
            if (userCalledItBack(pkg)) {
                return;
            }
            int at = injector().taskDisplay(pkg);
            if (at < 0) {
                // 任务没了：用户把它结束掉了，守位到此为止（不留一条永远搬不动的看门）。
                AppLog.i(TAG, "守位结束：" + pkg + " 的任务已不存在");
                release(false);
                return;
            }
            if (at != display) {
                boolean ok = injector().ensureOnDisplay(pkg, display, MOVE_TIMEOUT_MS);
                AppLog.i(TAG, (ok ? "守位：把 " : "守位失败：") + pkg + " 从 display " + at
                        + " 拉回 display " + display);
            }
            Handler h = handler;
            if (h != null) {
                h.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    /**
     * 用户是不是刚在桌面上点了目标应用的图标。
     *
     * <p>是的话**投屏按用户意图结束**：把目标留在主屏（他要接着在中控上用），服务停止。
     * 判据只认"桌面图标"这一种形状（见 {@link ActivityStartLog}）：这是唯一能区分
     * "用户自己叫回来的"与"应用自己跳回主屏"的事实，而两者的处理恰好相反。
     *
     * <p>读不到启动记录不是错误：通道失效时判据不成立，行为退回 4.2 的"照旧搬回"，
     * 只是把这件事写进落盘日志，不静默。
     *
     * @return true 表示投屏已结束（服务已停），调用方不必再巡检
     */
    private boolean userCalledItBack(String pkg) {
        long now = System.currentTimeMillis();
        String log = injector().recentStartLog();
        if (log == null) {
            if (!startLogUnreadable) {
                startLogUnreadable = true;
                AppLog.w(TAG, "读不到启动记录（logcat 通道不可用）：无法判断目标是不是被用户"
                        + "自己叫回主屏，本次投屏只做归位，不按用户意图释放");
            }
            return false;
        }
        ActivityStartLog.Entry hit = ActivityStartLog.desktopIconTap(
                ActivityStartLog.parse(log, now), pkg, establishedAtMs, now, ignoredUids);
        if (hit == null) {
            return false;
        }
        AppLog.i(TAG, "用户从桌面打开了 " + pkg + "（" + hit.describe() + "）"
                + " → 投屏按其意图结束，任务留在主屏");
        release(true);
        return true;
    }

    public static void start(Context context, String packageName, int display, String label) {
        Intent intent = new Intent(context, CastGuardService.class)
                .setAction(ACTION_GUARD)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_DISPLAY, display)
                .putExtra(EXTRA_LABEL, label == null ? packageName : label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 用户主动收回：搬回主屏并停服务。 */
    public static void release(Context context) {
        context.startService(new Intent(context, CastGuardService.class)
                .setAction(ACTION_RELEASE));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.init(this);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RELEASE.equals(action)) {
            release(true);
            return START_NOT_STICKY;
        }
        String pkg = intent == null ? null : intent.getStringExtra(EXTRA_PACKAGE);
        if (pkg == null) {
            // 没有目标就没得守（例如被系统重建）。如实收工，不假装在工作。
            stopSelf();
            return START_NOT_STICKY;
        }
        targetPackage = pkg;
        guardDisplay = intent.getIntExtra(EXTRA_DISPLAY, -1);
        targetLabel = intent.getStringExtra(EXTRA_LABEL);
        // 用户意图判据的基准点：只看这之后的启动记录。
        establishedAtMs = System.currentTimeMillis();
        ignoredUids = ignoredUidsFor(pkg);
        startLogUnreadable = false;
        startForeground(NOTIFICATION_ID, buildNotification());
        ensureThread();
        handler.removeCallbacks(patrol);
        handler.post(patrol);
        AppLog.i(TAG, "守位开始：" + pkg + " 必须留在 display " + guardDisplay
                + "（每 " + (INTERVAL_MS / 1000) + "s 巡检一次，"
                + "收回入口=通知按钮 / 桌面图标）");
        return START_NOT_STICKY;
    }

    /**
     * 哪些 uid 发起的启动不算"用户从桌面叫回来"。
     *
     * <p>目标应用的 uid 查不到（包不可见）就不排除它：其余三个条件仍然成立，
     * 而"发起者是应用自己"只影响精度、不影响安全方向。
     */
    private int[] ignoredUidsFor(String pkg) {
        int target = -1;
        try {
            target = getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable ignored) {
            // 包不可见：Android 11+ 的正常结果，不是错误
        }
        return target >= 0
                ? new int[]{0, ActivityStartLog.SHELL_UID, android.os.Process.myUid(), target}
                : new int[]{0, ActivityStartLog.SHELL_UID, android.os.Process.myUid()};
    }

    /**
     * 收工。
     *
     * @param moveHome true = 用户主动收回，把目标搬回主屏 0（他要接着在中控上用）；
     *                 false = 任务已经没了/没必要搬，直接停。
     */
    private void release(boolean moveHome) {
        String pkg = targetPackage;
        int display = guardDisplay;
        if (moveHome && pkg != null && display >= 0) {
            int at = injector().taskDisplay(pkg);
            if (at >= 0 && at != Display.DEFAULT_DISPLAY) {
                boolean ok = injector().ensureOnDisplay(pkg, Display.DEFAULT_DISPLAY,
                        RELEASE_TIMEOUT_MS);
                AppLog.i(TAG, (ok ? "收回：把 " : "收回失败：") + pkg + " 从 display " + at
                        + " 搬向 display " + Display.DEFAULT_DISPLAY);
            }
        }
        targetPackage = null;
        guardDisplay = -1;
        if (handler != null) {
            handler.removeCallbacks(patrol);
        }
        stopForeground(true);
        stopSelf();
    }

    private void ensureThread() {
        if (handler != null) {
            return;
        }
        thread = new HandlerThread("dashcast-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(patrol);
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
        AppLog.i(TAG, "守位服务结束");
        super.onDestroy();
    }

    /**
     * 常驻通知：既满足前台服务的平台要求，也是**用户唯一需要的收回入口**。
     * 低优先级、无声、不可划掉 —— 它表达的是"投屏正在生效"这个状态。
     */
    private Notification buildNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.guard_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent releaseIntent = new Intent(this, CastGuardService.class)
                .setAction(ACTION_RELEASE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent release = PendingIntent.getService(this, 1, releaseIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.guard_title, targetLabel))
                .setContentText(getString(R.string.guard_text))
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.guard_release), release).build())
                .build();
    }
}
