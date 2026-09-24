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
 * <h3>用户怎么收回（这是"有界性"的正解）</h3>
 *
 * 旧实现用**时间窗口**（20s，最多 45s）来保证"用户随时能夺回控制权"，代价是投屏会自己过期。
 * 这里换成正解：**给用户一个明确的收回入口** —— 常驻通知上的「收回投屏」按钮
 * （{@link #ACTION_RELEASE}）。收回时把目标搬回主屏（用户接着在中控上用），然后服务自己停。
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
    private volatile String note = "";

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
            int at = injector().taskDisplay(pkg);
            if (at < 0) {
                // 任务没了：用户把它结束掉了，守位到此为止（不留一条永远搬不动的看门）。
                AppLog.i(TAG, "守位结束：" + pkg + " 的任务已不存在");
                release(false);
                return;
            }
            if (at != display) {
                boolean ok = injector().ensureOnDisplay(pkg, display, MOVE_TIMEOUT_MS);
                note = ok ? "已搬回仪表屏" : "搬回失败";
                AppLog.i(TAG, (ok ? "守位：把 " : "守位失败：") + pkg + " 从 display " + at
                        + " 拉回 display " + display);
            }
            Handler h = handler;
            if (h != null) {
                h.postDelayed(this, INTERVAL_MS);
            }
        }
    };

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
        note = "";
        startForeground(NOTIFICATION_ID, buildNotification());
        ensureThread();
        handler.removeCallbacks(patrol);
        handler.post(patrol);
        AppLog.i(TAG, "守位开始：" + pkg + " 必须留在 display " + guardDisplay
                + "（每 " + (INTERVAL_MS / 1000) + "s 巡检一次，收回入口=通知按钮）");
        return START_NOT_STICKY;
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
