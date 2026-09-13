package com.byd.dashcast.adb;

import android.content.Context;
import android.util.Log;

/**
 * 把「载入密钥 → 连 adbd → 拿 shell → 拉起代理」这条链收成一个入口，
 * 让引导页和开机接收器共用同一段逻辑，避免两处各写一遍再各自漂移。
 */
public final class AdbBootstrap {

    private static final String TAG = "DashCastBootstrap";

    /** 引导页用：给用户留足去车机上点「允许」的时间。 */
    public static final long TIMEOUT_GUIDED_MS = 60000L;

    /** 开机/后台用：没有用户在看，等待短一点，失败就下次再说。 */
    public static final long TIMEOUT_BACKGROUND_MS = 8000L;

    private AdbBootstrap() {
    }

    public static final class Result {
        public final AdbClient.State state;
        public final String message;
        public final boolean agentRunning;

        Result(AdbClient.State state, String message, boolean agentRunning) {
            this.state = state;
            this.message = message;
            this.agentRunning = agentRunning;
        }

        @Override
        public String toString() {
            return state + ": " + message;
        }
    }

    /**
     * 同步执行整条链，**必须在非主线程调用**（会阻塞等待 adbd 回复，
     * 且首次运行还要现场生成 RSA-2048）。
     *
     * requestAuthorization 决定本次是否允许触发车机的授权对话框。只有引导页在
     * 用户明确点了「开始授权」之后才应传 true；快速探测与开机路径必须传 false，
     * 否则连接一超时就会在车机上留下一个收不到输入的孤儿对话框。
     */
    public static Result provision(Context context, long timeoutMs,
                                   boolean requestAuthorization) {
        AdbKeyStore keys;
        try {
            keys = AdbKeyStore.loadOrCreate(context);
        } catch (Throwable t) {
            Log.e(TAG, "生成/读取密钥失败", t);
            return new Result(AdbClient.State.FAILED, "生成密钥失败：" + t, false);
        }
        if (keys.freshlyCreated()) {
            Log.i(TAG, "已生成新的 ADB 身份，指纹 " + keys.fingerprint());
        }
        if (keys.resetReason() != null) {
            Log.w(TAG, keys.resetReason(), null);
        }
        // 打出发给 adbd 的公钥，便于与 `dumpsys adb` 的 user_keys 逐条比对。
        Log.i(TAG, "ADB 公钥：" + AdbKeyStore.publicKeyText(keys.publicKey()));

        AdbClient.State[] state = new AdbClient.State[1];
        AdbClient adb = AdbClient.connect(keys.privateKey(), keys.publicKey(), timeoutMs,
                requestAuthorization, state);
        if (adb == null) {
            AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
            return new Result(s, withResetReason(describe(s), keys), false);
        }

        try {
            // 一拿到 shell 就先 sync。车机的授权是系统框架**追加**进
            // /data/misc/adb/adb_keys 的，那条追加没有 fsync；掉电后它会以「长度正确、
            // 内容全 0」的形态回来（有一整行被写成 0），adbd 按 \n 切行
            // 后拿到的是空串，这把钥匙就被永久丢弃。应用碰不到框架的 fd，但可以替它落盘。
            adb.syncRemote();
            AgentLauncher.Status status = AgentLauncher.ensureRunning(context, adb);
            Log.i(TAG, "代理状态：" + status.detail);
            makeAuthorizationPermanent(adb);
            return new Result(AdbClient.State.READY,
                    withResetReason(status.detail, keys), status.running);
        } catch (Throwable t) {
            Log.e(TAG, "拉起代理失败", t);
            return new Result(AdbClient.State.READY, "已获得 shell，但拉起代理失败：" + t, false);
        } finally {
            adb.close();
        }
    }

    /** 身份被重建时把原因并进面向用户的说明——否则「怎么又弹了」永远说不清。 */
    private static String withResetReason(String detail, AdbKeyStore keys) {
        String reason = keys.resetReason();
        return reason == null ? detail : reason + "\n" + detail;
    }

    /** 只连不管代理，且绝不触发授权框，用于快速探测。 */
    public static Result checkAuthorization(Context context, long timeoutMs) {
        AdbKeyStore keys;
        try {
            keys = AdbKeyStore.loadOrCreate(context);
        } catch (Throwable t) {
            return new Result(AdbClient.State.FAILED, "生成密钥失败：" + t, false);
        }
        AdbClient.State[] state = new AdbClient.State[1];
        AdbClient adb = AdbClient.connect(keys.privateKey(), keys.publicKey(), timeoutMs,
                false, state);
        if (adb == null) {
            AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
            return new Result(s, describe(s), false);
        }
        adb.close();
        return new Result(AdbClient.State.READY, "已完成 ADB 授权，指纹 " + keys.fingerprint(), false);
    }

    /**
     * 把车机的 adb 授权窗口设为 0，也就是**永久有效**。
     *
     * 为什么需要这一步：Android 只对勾了「一律允许」的授权做事，而且**会回收**。
     * AdbKeyStore.isKeyAuthorized() 的判定是
     *
     *   allowedConnectionTime == 0
     *       || now < (lastConnectionTime + allowedConnectionTime)
     *
     * 该项在车机上默认没有配置，于是取代码默认 604800000 ms（7 天）。
     * 也就是说「装一次永久静默」实际是「7 天内至少成功连接一次」——车子长期停放时，
     * 授权会被 filterOutOldKeys() 从 adb_keys 里删掉，重新弹框。
     *
     * 0 的语义见 AOSP 原文注释：「revert to the previous behavior of always allowing
     * previously granted adb grants」。shell 持有 WRITE_SECURE_SETTINGS，UID 2000
     * 通道写得进去。
     *
     * best-effort：写不进去（车机没有该权限、SELinux 拒绝等）只记一条日志，
     * **绝不影响本次连接**——大不了退回 7 天窗口的行为。
     * 回滚：`settings delete global adb_allowed_connection_time`。
     *
     * 读-写-回读都在调用方那条已经用过的连接上做，不另开专用连接、也不只发一条
     * 命令不回读，是因为 `shell()` 当时在同一条连接上从第二条起会静默失效；那个真因
     * 已修（见 {@link AdbClient} 的 shell v2 分帧）。改回读之后，"设置没生效"不再是
     * 静默的——以前写入无报错但设置没变，日志却照样说成功。
     */
    private static void makeAuthorizationPermanent(AdbClient adb) {
        try {
            String before = adb.shell("settings get global adb_allowed_connection_time", 5000L)
                    .trim();
            if ("0".equals(before)) {
                Log.i(TAG, "授权窗口已是 0（永久有效），无需改动");
                return;
            }
            adb.shell("settings put global adb_allowed_connection_time 0", 5000L);
            String after = adb.shell("settings get global adb_allowed_connection_time", 5000L)
                    .trim();
            if ("0".equals(after)) {
                Log.i(TAG, "授权窗口已设为 0（永久有效），原值=" + before);
            } else {
                Log.w(TAG, "授权窗口写入后回读仍为「" + after + "」（原值「" + before
                        + "」），该设置未被接受", null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "设置授权窗口失败（不影响本次连接）：" + t);
        }
    }

    /** 状态 → 面向用户的中文说明，带上可执行的下一步。 */
    public static String describe(AdbClient.State state) {
        if (state == null) {
            return "未知失败";
        }
        switch (state) {
            case READY:
                return "已获得 shell（uid 2000）";
            case NEED_AUTHORIZATION:
                return "车机还没授权本应用：请在弹出的「允许 USB 调试吗」对话框上点「允许」";
            case UNREACHABLE:
                return "连不上 127.0.0.1:5555 —— 车机的无线 ADB 没有开启，"
                        + "请在「开发者工具」里打开无线 ADB 开关";
            default:
                return "ADB 握手失败";
        }
    }
}
