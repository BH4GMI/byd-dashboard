package com.byd.dashcast.privileged;

/**
 * App 侧与特权进程侧共享的通道契约。
 *
 * 两侧的代码都在同一个 APK 的 dex 里（特权进程用 CLASSPATH=&lt;已安装 APK&gt; 拉起，
 * 与原版 Just Dashboard 的做法一致），所以这些常量是编译期内联的，不存在版本漂移。
 *
 * 为什么必须有一个常驻特权进程（而不是每条命令走一次 adb shell）：
 *   - 触摸：InputManager.injectInputEvent 需要 INJECT_EVENTS（signature 级），普通 App 拿不到；
 *           走 input 命令则每个事件 fork 一个 ART，本车实测 40~140 ms/次，拖拽不可用。
 *   - 预览：SurfaceControl 的 setDisplayLayerStack/setDisplaySurface 是 @hide，
 *           且只有 shell 身份才通得过 SurfaceFlinger 的检查。
 */
public final class PrivilegedProtocol {

    private PrivilegedProtocol() {
    }

    /** 特权进程把 Binder 挂到这个广播上回传给 App；App 侧必须 registerReceiver。 */
    public static final String ACTION_READY = "com.byd.dashcast.PRIVILEGED_READY";

    /** 广播 extras 里 Binder 的键。用 Bundle.putBinder/getBinder（公开 API），不碰 @hide 的 Intent.getIBinderExtra。 */
    public static final String KEY_BINDER = "com.byd.dashcast.privileged.binder";

    /** 目标 App 的包名，特权进程用它做显式广播。 */
    public static final String APP_PACKAGE = "com.byd.dashcast";

    /** Binder 接口名。两侧 transact/enforceInterface 必须用同一个。 */
    public static final String DESCRIPTOR = "com.byd.dashcast.privileged.Channel";

    /** 特权进程主类全名，启动命令行与文档共用一份，避免两处写死不一致。 */
    public static final String MAIN_CLASS = "com.byd.dashcast.privileged.PrivilegedProcess";

    /**
     * {@code --nice-name} 用的进程名，便于 {@code ps} 辨认与清理。
     *
     * <p>**必须带上包名**：清理用的是 {@code pkill -f}，它按整条命令行做正则匹配，
     * 而 netease 分支（{@code com.byd.dashcast.netease}）是同一份源码的拷贝 ——
     * 若两边都叫 {@code dashcast-priv}，各自的 start()/stop() 会把对方的进程一起杀掉，
     * 两个包同时装着时表现为"预览随机消失"。从 {@link #APP_PACKAGE} 派生就永远不会撞：
     * 本包得到 {@code com.byd.dashcast-priv}，fork 得到 {@code com.byd.dashcast.netease-priv}，
     * 两个模式互不匹配。
     */
    public static final String NICE_NAME = APP_PACKAGE + "-priv";

    // ---- Binder 事务号（从 1 开始；Binder 自己占用了 0 和一些负数）----

    /** 无参数，reply 写 uid 与目标 displayId，用于确认对端身份。 */
    public static final int CODE_PING = 1;

    /** [Surface] -> reply [int ok]。把预览输出接到这个 Surface；传 null 表示解除。 */
    public static final int CODE_SET_SURFACE = 2;

    /** 无参数，reply [int ok]。显式解除预览并销毁 display。 */
    public static final int CODE_CLEAR_SURFACE = 3;

    /** [int action, float x, float y, long downTime, long eventTime] -> reply [int ok]。 */
    public static final int CODE_TOUCH = 4;

    /** [int keyCode, int action] -> reply [int ok]。 */
    public static final int CODE_KEY = 5;

    /** 无参数。请求特权进程退出（会被清理后 return）。 */
    public static final int CODE_SHUTDOWN = 6;

    /**
     * {@code [IBinder clientToken] -> reply [int ok]}。把 App 进程的 token 交给特权进程，
     * 由它 {@code linkToDeath} 盯着 App 的生死。
     *
     * <p>为什么必须有这一条：{@code am force-stop} 和 LMK 杀进程**都不会走 onPause**，
     * 于是 {@link #CODE_SHUTDOWN} 永远发不出去，一个 uid 2000 的进程会带着它建的
     * SurfaceControl display 一直挂到下次启动 App 被 pkill 为止（实测 confirm 过）。
     * 死亡通知是 Binder 为这件事准备的原生机制，不需要轮询、不需要超时猜测。
     */
    public static final int CODE_ATTACH_CLIENT = 7;

    // ---- 触摸 action（与 MotionEvent 的 ACTION_* 对齐，但显式定义避免两侧耦合到框架常量）----

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_CANCEL = 3;
}
