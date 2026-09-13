package com.byd.dashcast.adb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * 开机后把 uid-2000 代理拉起来，让用户不必再插电脑。
 *
 * 为什么要退避重试，而不是开机跑一次就算：
 * 开机时序里 adbd 并不是一开始就监听 TCP 5555。车机上这条链是
 *   init 读 sys.connect.adb.wiress=1  ->  setprop service.adb.tcp.port 5555  ->  重启 adbd
 * 而 sys.connect.adb.wiress 是 com.byd.appserver（持久化系统应用）在它自己的进程启动时
 * 才写入的（AppServerApplication.onCreate -> startServicesIfNeeded -> WifiAdbDebugMain.start）。
 * 也就是说 BOOT_COMPLETED 到达本程序时，AppServer 很可能还没跑完。所以这里按固定退避重试，
 * 直到连上或次数用尽——每次重试都有明确理由，不是盲目重试。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DashCastBoot";

    /** 本程序自己排的重试动作；AlarmManager 只能发 Intent，所以用动作名区分。 */
    public static final String ACTION_RETRY = "com.byd.dashcast.action.RETRY_AGENT";

    private static final String PREFS = "dashcast_boot";
    private static final String KEY_ATTEMPT = "attempt";

    /**
     * 重试间隔。0 表示立即尝试；之后逐步放宽，总跨度约 5 分钟，
     * 足以覆盖 AppServer 启动 + init 重启 adbd 的时间，又不至于整夜重试。
     */
    private static final long[] RETRY_DELAYS_MS = {0L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L};

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean isRetry = ACTION_RETRY.equals(action);
        Log.i(TAG, "收到 " + action);

        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(app);
                } finally {
                    pending.finish();
                }
            }
        }, "dashcast-boot").start();
    }

    private void handle(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int attempt = prefs.getInt(KEY_ATTEMPT, 0);

        AdbBootstrap.Result result =
                AdbBootstrap.provision(context, AdbBootstrap.TIMEOUT_BACKGROUND_MS, false);
        Log.i(TAG, "第 " + (attempt + 1) + " 次尝试：" + result);

        if (result.agentRunning) {
            prefs.edit().putInt(KEY_ATTEMPT, 0).apply();
            return;
        }

        // 还没授权就别排重试了：再试一百次也没用，等用户主动打开应用走引导。
        if (result.state == AdbClient.State.NEED_AUTHORIZATION) {
            Log.i(TAG, "尚未授权，交由引导页处理，不再重试");
            prefs.edit().putInt(KEY_ATTEMPT, 0).apply();
            return;
        }

        int next = attempt + 1;
        if (next >= RETRY_DELAYS_MS.length) {
            Log.w(TAG, "重试次数用尽，放弃本次开机拉起");
            prefs.edit().putInt(KEY_ATTEMPT, 0).apply();
            return;
        }
        prefs.edit().putInt(KEY_ATTEMPT, next).apply();
        scheduleRetry(context, RETRY_DELAYS_MS[next]);
    }

    private void scheduleRetry(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(context, BootReceiver.class).setAction(ACTION_RETRY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 1001, intent, flags);

        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        Log.i(TAG, delayMs + " ms 后重试");
    }
}
