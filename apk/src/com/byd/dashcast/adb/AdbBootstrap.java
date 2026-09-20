package com.byd.dashcast.adb;

import android.content.Context;
import android.util.Log;

/**
 * 「载入密钥 → 连 adbd → 拿到一条可用的 shell 通道」这条链的唯一入口，
 * 让引导页、界面与开机接收器共用同一段逻辑，避免三处各写一遍再各自漂移。
 *
 * <p>2026-09-20 起不再拉起 uid-2000 代理：整条投屏/触控链改由
 * {@link com.byd.dashcast.ShellChannel} 在同一条长连接上跑 shell 命令完成。
 * 这里只负责**建立并验证连接**，连接本身由 ShellChannel 持有。
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

        Result(AdbClient.State state, String message) {
            this.state = state;
            this.message = message;
        }

        /** 连接可用（可继续跑 shell 命令）。 */
        public boolean isReady() {
            return state == AdbClient.State.READY;
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
     * <p>成功时连接**保持打开**并交给 {@link com.byd.dashcast.ShellChannel} 复用，
     * 所以这里不关闭它 —— 这是与旧实现最大的区别（旧实现用完即 close）。
     *
     * @param requestAuthorization 决定本次是否允许触发车机的授权对话框。只有引导页在
     *        用户明确点了「开始授权」之后才应传 true；快速探测与开机路径必须传 false，
     *        否则连接一超时就会在车机上留下一个收不到输入的孤儿对话框。
     */
    public static Result provision(Context context, long timeoutMs,
                                   boolean requestAuthorization) {
        AdbKeyStore keys;
        try {
            keys = AdbKeyStore.loadOrCreate(context);
        } catch (Throwable t) {
            Log.e(TAG, "生成/读取密钥失败", t);
            return new Result(AdbClient.State.FAILED, "生成密钥失败：" + t);
        }
        if (keys.freshlyCreated()) {
            Log.i(TAG, "已生成新的 ADB 身份，指纹 " + keys.fingerprint());
        }
        // 打出发给 adbd 的公钥，便于与 `dumpsys adb` 的 user_keys 逐条比对。
        Log.i(TAG, "本机 ADB 公钥：" + AdbKeyStore.publicKeyText(keys.publicKey()));

        AdbClient.State[] state = new AdbClient.State[1];
        AdbClient adb = AdbClient.connect(keys.privateKey(), keys.publicKey(), timeoutMs,
                requestAuthorization, state);
        if (adb == null) {
            AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
            return new Result(s, describe(s));
        }
        // 连接已可用。交给 ShellChannel 持有并复用；**不关闭**。
        com.byd.dashcast.ShellChannel.get().adopt(adb, keys.privateKey(), keys.publicKey());
        Log.i(TAG, "shell 通道就绪，指纹 " + keys.fingerprint());
        return new Result(AdbClient.State.READY, "已获得 shell（uid 2000）");
    }

    /** 只连不保持，且绝不触发授权框，用于快速探测授权状态。 */
    public static Result checkAuthorization(Context context, long timeoutMs) {
        AdbKeyStore keys;
        try {
            keys = AdbKeyStore.loadOrCreate(context);
        } catch (Throwable t) {
            return new Result(AdbClient.State.FAILED, "生成密钥失败：" + t);
        }
        AdbClient.State[] state = new AdbClient.State[1];
        AdbClient adb = AdbClient.connect(keys.privateKey(), keys.publicKey(), timeoutMs,
                false, state);
        if (adb == null) {
            AdbClient.State s = state[0] == null ? AdbClient.State.FAILED : state[0];
            return new Result(s, describe(s));
        }
        adb.close();
        return new Result(AdbClient.State.READY, "已完成 ADB 授权，指纹 " + keys.fingerprint());
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
