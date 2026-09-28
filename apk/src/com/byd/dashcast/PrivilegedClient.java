package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.byd.dashcast.privileged.PrivilegedProtocol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 常驻特权进程（uid 2000）的客户端。
 *
 * <h3>为什么又需要它</h3>
 * 2026-09-20 那轮"去代理"是成立的前提错了：当时认为 shell 命令足以覆盖全部需求。
 * 实测下来有两条腿 shell 根本走不了：
 *
 * <ul>
 *   <li><b>触摸</b>：{@code InputManager.injectInputEvent} 需要 INJECT_EVENTS（signature 级），
 *       普通 App 拿不到；而 shell 的 {@code input} 命令每个事件 fork 一个 ART，
 *       本车实测 40~140 ms/次，一次拖拽上百个事件就积压成几秒。</li>
 *   <li><b>预览</b>：要把仪表盘那块屏的画面拿到主屏，只有
 *       {@code SurfaceControl.setDisplayLayerStack + setDisplaySurface} 这条路，
 *       两者都是 @hide 且需要 shell 身份。screencap 抓帧是唯一的 shell 替代品，
 *       实测 3.2~3.5 fps。</li>
 * </ul>
 *
 * <p>反编译原版 Just Dashboard 证实：它就是 {@code app_process} 起的 uid 2000 常驻进程
 * （{@code --nice-name=CommunicationProcess}，CLASSPATH 指向它自己的 APK），
 * 而且它的"进入仪表屏预览"也跑在那个进程里。详见 docs/PREVIEW_MECHANISM_ZH.md。
 *
 * <h3>本类比原版简单的地方</h3>
 * <ul>
 *   <li>启动：{@code CLASSPATH=<已安装 APK>}，特权代码就是本 APK dex 里的
 *       {@code com.byd.dashcast.privileged.*}。不需要第二个 jar、不需要 push、
 *       不需要 sha256 校验 —— 版本漂移这类问题从结构上不存在。</li>
 *   <li>回传：原版用 @hide 的 {@code Intent.getIBinderExtra}；这里用公开的
 *       {@code Bundle.putBinder/getBinder}（见 {@link PrivilegedProtocol}）。</li>
 * </ul>
 *
 * <p>生命周期与界面一致：可见才拉起，onPause 就停。这是"后台不留资源"的一贯做法，
 * 也保证用户随时能拿回控制权。
 */
public final class PrivilegedClient {

    private static final String TAG = "dashcast";

    /** 特权进程要起 ART 虚拟机 + 建 Application，实测几百毫秒；给足余量。 */
    private static final long READY_TIMEOUT_MS = 10000L;

    /**
     * {@link #tap} 里 UP 相对 DOWN 的时间差。取值只为了让两个事件时间戳不同 ——
     * 事件是 oneway 立刻下发的，真实间隔由 Binder 与输入管线决定，不靠这个数等待。
     */
    private static final long TAP_HOLD_MS = 40L;

    /**
     * 特权进程的日志落点。
     *
     * <p>它是**每次启动截断覆写**的单个文件，不会累积；存在的原因是
     * "进程起不来"必须有可读的原因，否则用户只能看到一句"未连接"。
     */
    private static final String LOG_PATH = "/data/local/tmp/dashcast-priv.log";

    public interface Listener {
        /** Binder 已到手，可以开始 setPreviewSurface / touch。**在主线程回调**。 */
        void onReady();

        /** 起不来。reason 是可直接展示给用户的一句话。**在主线程回调**。 */
        void onFailed(String reason);
    }

    private final Context context;
    private final ShellChannel shell = ShellChannel.get();
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * 本进程的身份凭据，交给特权进程去 {@code linkToDeath}。只要本进程活着它就活着，
     * 所以放在字段上而不是临时对象 —— 临时对象被回收会让 Binder 提前判定"对端已死"。
     */
    private final IBinder clientToken = new Binder();

    /**
     * 所有会碰设备的操作都排在这条线上：拉起（要等就绪）、停止（要 pkill）。
     *
     * <p>两条理由，缺一不可：
     * <ul>
     *   <li>{@link ShellChannel#run} 是 socket IO，主线程上做会抛
     *       NetworkOnMainThreadException —— 而 {@link #stop()} 是从 {@code onPause} 调的。</li>
     *   <li>这两件事**必须保序**：若停止的 pkill 落在拉起之后，
     *       会把刚起来的进程当场杀掉，表现为"预览随机起不来"这种偶发故障。</li>
     * </ul>
     */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dashcast-priv-io");
            t.setDaemon(true);
            return t;
        }
    });

    /** 特权进程回传的 Binder。为空 = 没连上。 */
    private volatile IBinder binder;
    private volatile int targetDisplay = -1;
    private volatile boolean receiverRegistered;
    private volatile boolean starting;
    private volatile CountDownLatch readyLatch;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null || !PrivilegedProtocol.ACTION_READY.equals(intent.getAction())) {
                return;
            }
            Bundle extras = intent.getExtras();
            IBinder b = (extras == null)
                    ? null : extras.getBinder(PrivilegedProtocol.KEY_BINDER);
            if (b == null) {
                return;
            }
            binder = b;
            Log.i(TAG, "特权进程已回传 Binder");
            CountDownLatch latch = readyLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    };

    private PrivilegedClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 进程内单例。
     *
     * <p>通道状态（Binder 句柄、目标屏、广播是否已注册、{@link #ioExecutor}）本来就是
     * **每个进程一份**：一个 App 进程只对应一个特权进程。做成单例有两个实际好处 ——
     * 界面重建时不会漏下一条空闲的 io 线程，也不会重复注册广播接收器。
     * 与 {@link ShellChannel#get()} 同一惯例。
     */
    public static PrivilegedClient get(Context context) {
        PrivilegedClient c = instance;
        if (c == null) {
            synchronized (PrivilegedClient.class) {
                c = instance;
                if (c == null) {
                    c = new PrivilegedClient(context);
                    instance = c;
                }
            }
        }
        return c;
    }

    private static volatile PrivilegedClient instance;

    /** 现在能不能直接用。会顺带确认对端进程还活着。 */
    public boolean isReady() {
        return binder != null && binder.isBinderAlive() && ping();
    }

    public int targetDisplay() {
        return targetDisplay;
    }

    /**
     * 拉起特权进程并等它回传 Binder。**立即返回**，结果走 listener（主线程）。
     *
     * <p>重复调用是安全的：已经在跑且目标屏一致就直接 onReady。
     *
     * <p>就绪快查（含一次同步 ping()，Binder 往返）也在 io 线程上做：调用方是
     * UI 线程，Binder 往返不该占住主线程。
     */
    public void start(final int displayId, final Listener listener) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (binder != null && binder.isBinderAlive()
                        && targetDisplay == displayId && ping()) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onReady();
                        }
                    });
                    return;
                }
                final String reason = doStart(displayId);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (reason == null) {
                            listener.onReady();
                        } else {
                            listener.onFailed(reason);
                        }
                    }
                });
            }
        });
    }

    /** @return null 表示成功，否则是给用户看的原因。跑在后台线程。 */
    private String doStart(int displayId) {
        if (starting) {
            return "特权进程正在启动中";
        }
        starting = true;
        try {
            ensureReceiver();
            binder = null;

            String apk = apkPath();
            if (apk == null || apk.isEmpty()) {
                return "拿不到自己的 APK 路径";
            }

            // 先清掉可能残留的旧实例：App 被系统杀掉时走不到 stop()，
            // 留着的旧进程会让新进程的广播和旧句柄混在一起。
            shell.run("pkill -f " + PrivilegedProtocol.NICE_NAME + " 2>/dev/null");

            readyLatch = new CountDownLatch(1);
            targetDisplay = displayId;

            String cmd = "CLASSPATH=" + apk
                    + " nohup app_process /system/bin --nice-name=" + PrivilegedProtocol.NICE_NAME
                    + " " + PrivilegedProtocol.MAIN_CLASS + " " + displayId
                    + " > " + LOG_PATH + " 2>&1 &";
            Log.i(TAG, "启动特权进程：" + cmd);
            String out = shell.run(cmd);
            if (out == null) {
                return "下发启动命令失败（shell 通道已断）";
            }

            CountDownLatch latch = readyLatch;
            if (!latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "特权进程 " + (READY_TIMEOUT_MS / 1000) + " 秒内未就绪。"
                        + tailLog();
            }
            // 就绪之后立刻把本进程的 token 交过去，让对端挂上死亡通知。
            // 这一步不能省：进程被 force-stop / LMK 强杀时走不到 stop()，
            // 没有死亡通知就会留下一个 uid 2000 的孤儿进程和它建的 display。
            if (!attachDeathWatch()) {
                return "特权进程不接受客户端死亡通知";
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "启动特权进程异常", t);
            return "启动特权进程异常：" + t;
        } finally {
            starting = false;
        }
    }

    /**
     * 停掉特权进程。**立即返回**，可以从 onPause 直接调。
     *
     * <p>binder 先置空：{@link #touch}/{@link #tap} 读的就是这个 volatile，
     * "立刻断"的语义由此保证，不必等事务真的发出去。
     *
     * <p>SHUTDOWN 事务（对端要在事务里 destroyDisplay）与 pkill 都是会挂住调用方的
     * 活，统一排到 {@link #ioExecutor}：既不让 UI 线程等对端，单线程排队也天然保住
     * "先停干净、再轮到后续拉起"的顺序（见 {@link #ioExecutor} 的注释）。
     *
     * <p>已知代价：SHUTDOWN 排在队列已有工作之后 —— 若正有一次进行中的拉起（回环
     * connect 最长 8s），对端会晚几秒才真正退出、多占着它建的 display 这几秒。
     * app 侧的"立刻断"由 binder 置空保证，不受影响。
     */
    public void stop() {
        final IBinder b = binder;
        binder = null;
        targetDisplay = -1;
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (b != null && b.isBinderAlive()) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
                        b.transact(PrivilegedProtocol.CODE_SHUTDOWN, data, reply, 0);
                    } catch (Throwable ignored) {
                        // 对端已经没了，下面 pkill 兜底
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                }
                unregisterReceiver();
                shell.run("pkill -f " + PrivilegedProtocol.NICE_NAME + " 2>/dev/null");
            }
        });
    }

    // ---- 跨进程调用 --------------------------------------------------------

    /**
     * 把本进程的 token 交给特权进程，让它 {@code linkToDeath} 盯着我们。
     *
     * <p>{@code writeStrongBinder} 把一个本地 {@link Binder} 送过去，对端拿到的是指向
     * **本进程**的代理；本进程一死，内核就通知对端。
     */
    private boolean attachDeathWatch() {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            data.writeStrongBinder(clientToken);
            b.transact(PrivilegedProtocol.CODE_ATTACH_CLIENT, data, reply, 0);
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "挂客户端死亡通知失败", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 把预览输出接到这个 Surface；传 null 解除。 */
    public boolean setPreviewSurface(Surface surface) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            data.writeParcelable(surface, 0);
            b.transact(PrivilegedProtocol.CODE_SET_SURFACE, data, reply, 0);
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "setPreviewSurface 失败", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean clearPreviewSurface() {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            // 与 submitPreviewSurface 的 SET 事务定序（见 surfaceTxLock 注释）。
            // UI 线程可能在这把锁上等一次在途的 SET 事务（一次 Binder 往返，毫秒级）
            // ——与它本就要同步发一次事务的代价同量级，可接受。
            synchronized (surfaceTxLock) {
                b.transact(PrivilegedProtocol.CODE_CLEAR_SURFACE, data, reply, 0);
            }
            return reply.readInt() != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** submitPreviewSurface 的结果，一律回主线程。 */
    public static final int SURFACE_BOUND = 0;
    /** 通道还没就绪：不是失败，调用方等 onReady 再补交一次。 */
    public static final int SURFACE_NOT_READY = 1;
    /** 通道在但接不上：调用方应转降级路径。 */
    public static final int SURFACE_REFUSED = 2;
    /** 等到 io 线程时 Surface 已被调用方替换/收工：本次提交作废，不算失败。 */
    public static final int SURFACE_STALE = 3;

    /** submitPreviewSurface 的结果回调。**在主线程回调**。 */
    public interface SurfaceCallback {
        void onResult(int state);
    }

    /**
     * 在 io 线程上、SET_SURFACE 事务发出前复核 Surface 是否仍是调用方手上最新的那块。
     *
     * <p>提交是异步的，排队期间调用方可能已经换了 Surface（TextureView 重建）或整个
     * 收工（onSurfaceTextureDestroyed）。已释放的 Surface 绝不能再发给对端绑定，
     * 所以事务发出前必须复核。
     */
    public interface SurfaceGuard {
        boolean isCurrent(Surface surface);
    }

    /**
     * SET_SURFACE 与 CLEAR_SURFACE 事务的串行锁。异步提交（io 线程）与同步清除
     * （UI 线程，见 {@link #clearPreviewSurface}）靠它定序：对端要么看到
     * SET→CLEAR（清除赢），要么 SET 被守卫拦下 —— 不存在 CLEAR 之后的 SET。
     */
    private final Object surfaceTxLock = new Object();

    /** binder 是否已回传（不含活性确认）。只读本地 volatile，可安全地在 UI 线程调。 */
    public boolean hasBinder() {
        return binder != null;
    }

    /**
     * {@link #setPreviewSurface} 的异步版：事务排到 {@link #ioExecutor}，结果回主线程。
     *
     * <p>SET_SURFACE 是同步事务，且对端要在事务里建/接 display（一串 SurfaceControl
     * 调用）——对端卡住时调用方会被无限期挂住，所以不许在 UI 线程上直接发。
     *
     * <p>{@code guard} 在事务发出前于 io 线程上复核 Surface 仍是最新的：排队期间
     * 它可能已被替换或释放（见 {@link SurfaceGuard}），过期就以 {@link #SURFACE_STALE}
     * 收场，事务不发。
     */
    public void submitPreviewSurface(final Surface surface, final SurfaceGuard guard,
            final SurfaceCallback callback) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final int state;
                if (!isReady()) {
                    state = SURFACE_NOT_READY;
                } else {
                    // 与 clearPreviewSurface 共用 surfaceTxLock 定序（见其注释）。
                    synchronized (surfaceTxLock) {
                        if (guard != null && !guard.isCurrent(surface)) {
                            state = SURFACE_STALE;
                        } else {
                            state = setPreviewSurface(surface)
                                    ? SURFACE_BOUND : SURFACE_REFUSED;
                        }
                    }
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(state);
                    }
                });
            }
        });
    }

    /** launchOnDisplay 的结果回调。**在主线程回调**；error 为 null 表示已受理。 */
    public interface LaunchListener {
        void onResult(String error);
    }

    /**
     * 备用启动：让特权进程用 {@code ActivityOptions.setLaunchDisplayId} 定向启动目标
     * Activity（见 {@code PrivilegedProtocol.CODE_LAUNCH} 的注释——3.0/4.0 参考实现
     * 在 Android 10 上实证过的通路）。事务排到 {@link #ioExecutor}，结果回主线程。
     *
     * <p>只应作为 {@code am start-activity --display} 失败后的兜底，不改变首选路径。
     */
    public void launchOnDisplay(final int display, final String packageName,
            final String activityName, final LaunchListener listener) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final String error;
                IBinder b = binder;
                if (b == null) {
                    error = "特权通道未就绪";
                } else {
                    String outcome = null;
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
                        data.writeInt(display);
                        data.writeString(packageName);
                        data.writeString(activityName);
                        b.transact(PrivilegedProtocol.CODE_LAUNCH, data, reply, 0);
                        outcome = reply.readInt() != 0 ? null : reply.readString();
                    } catch (Throwable t) {
                        outcome = String.valueOf(t);
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                    error = outcome;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onResult(error);
                    }
                });
            }
        });
    }

    /**
     * 注入一次触摸。**走 oneway**：拖拽时每秒几十个 MOVE，每个都等一次往返
     * 会把触摸线程变成瓶颈，而触摸的返回值没有任何调用方会用它做决策。
     *
     * @param action 取 {@code PrivilegedProtocol.TOUCH_*}，数值与 {@code MotionEvent.ACTION_*}
     *               一致（调用方直接传 {@code getActionMasked()} 即可）
     */
    public boolean touch(int action, float x, float y, long downTime, long eventTime) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            data.writeInt(normalizeAction(action));
            data.writeFloat(x);
            data.writeFloat(y);
            data.writeLong(downTime);
            data.writeLong(eventTime);
            b.transact(PrivilegedProtocol.CODE_TOUCH, data, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
        }
    }

    /**
     * 一次完整点按（DOWN + UP）。可以直接在 UI 线程调：两次都是 oneway，不等回执。
     *
     * <p>两个事件共用同一个 downTime 才是同一次手势；DOWN 与 UP 的 eventTime 必须不同 ——
     * 时间戳完全相同的手势在 InputDispatcher 侧会被看作长度为 0 的异常事件，
     * 部分应用会判成长按而漏掉点击。
     */
    public boolean tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        boolean down = touch(PrivilegedProtocol.TOUCH_DOWN, x, y, now, now);
        boolean up = touch(PrivilegedProtocol.TOUCH_UP, x, y, now, now + TAP_HOLD_MS);
        return down && up;
    }

    /**
     * 把 {@code MotionEvent} 的动作收敛到协议定义的四态。
     *
     * <p>多指动作（{@code ACTION_POINTER_DOWN/UP}）在单指协议里没有对应表示，收敛成 MOVE ——
     * 与降级路径的行为一致（{@code InjectClient.touch} 也是这么归一的）。
     * 本界面是用一根手指操作仪表屏，不需要第二根手指。
     */
    private static int normalizeAction(int action) {
        switch (action) {
            case PrivilegedProtocol.TOUCH_DOWN:
            case PrivilegedProtocol.TOUCH_UP:
            case PrivilegedProtocol.TOUCH_MOVE:
            case PrivilegedProtocol.TOUCH_CANCEL:
                return action;
            default:
                return PrivilegedProtocol.TOUCH_MOVE;
        }
    }

    public boolean key(int keyCode, int action) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            data.writeInt(keyCode);
            data.writeInt(action);
            b.transact(PrivilegedProtocol.CODE_KEY, data, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
        }
    }

    /** 确认对端是 uid 2000 的活进程，而不是一个已经死掉的句柄。 */
    private boolean ping() {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedProtocol.DESCRIPTOR);
            b.transact(PrivilegedProtocol.CODE_PING, data, reply, 0);
            return reply.readInt() == 2000;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ---- 辅助 --------------------------------------------------------------

    private void ensureReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(PrivilegedProtocol.ACTION_READY);
        context.registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
            // 没注册成功或已注销
        }
        receiverRegistered = false;
    }

    /**
     * 本 APK 的绝对路径。特权进程的 {@code CLASSPATH} 就是它。
     *
     * <p>直接问系统，**不去解析 {@code pm path} 的输出**：路径是我们自己进程的属性，
     * {@link android.content.pm.ApplicationInfo#sourceDir} 就是权威答案 ——
     * 精确、零开销，也不会因为输出格式或传输层的任何变化而变。
     */
    private String apkPath() {
        ApplicationInfo info = context.getApplicationInfo();
        if (info != null && info.sourceDir != null && info.sourceDir.endsWith(".apk")) {
            return info.sourceDir;
        }
        return null;
    }

    /** 起不来时把特权进程日志的尾巴带回来，否则用户只能看到一句"未连接"。 */
    private String tailLog() {
        String out = shell.run("tail -n 6 " + LOG_PATH + " 2>/dev/null");
        if (out == null) {
            return "";
        }
        String text = out.trim();
        if (text.isEmpty()) {
            return "（无日志输出）";
        }
        return " " + text.replace('\n', ' ');
    }
}
