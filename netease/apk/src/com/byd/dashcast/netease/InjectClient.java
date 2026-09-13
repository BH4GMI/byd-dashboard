package com.byd.dashcast.netease;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * 与 uid-2000 注入代理通信的客户端。
 *
 * 通道是 Binder，不是 LocalSocket：句柄由代理通过广播送过来
 * （sendBroadcast 带 IBinder extra，原版 b.smali:2403-2460 的做法），之后本端直接
 * transact。Binder 由内核驱动裁决，不经过 SELinux 的 unix socket 策略，
 * 而且代理侧能用 Binder.getCallingUid() 把调用方钉死在本应用。
 *
 * 触摸用 FLAG_ONEWAY：代理不需要回执，UI 线程发完即走，240Hz 的拖动不会造成卡顿，
 * 也就不需要额外的写线程和队列。
 */
public final class InjectClient {

    private static final String TAG = "dashcast";
    private static final String DESCRIPTOR = "com.byd.dashcast.agent.AgentBinder";

    private static final int TRANSACT_PING = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACT_SET_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACT_TOUCH = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TRANSACT_KEY = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int TRANSACT_LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 4;
    private static final int TRANSACT_LIST_APPS = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int TRANSACT_START_MIRROR = IBinder.FIRST_CALL_TRANSACTION + 6;
    private static final int TRANSACT_STOP_MIRROR = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int TRANSACT_WATCH = IBinder.FIRST_CALL_TRANSACTION + 8;
    private static final int TRANSACT_UNWATCH = IBinder.FIRST_CALL_TRANSACTION + 9;
    private static final int TRANSACT_TASK_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 10;
    private static final int TRANSACT_MOVE_TO_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 11;

    /** 自动补点的按下时长。太短会被部分控件当成抖动丢掉，50ms 与真人点按同量级。 */
    private static final long TAP_DURATION_MS = 50;

    /**
     * 代理侧的看门状态。PING 的回执里带回来，状态栏据此显示——
     * 看门如果静默失效，用户只会看到"投屏又跑回主屏了"却不知道为什么。
     */
    public static final class WatchState {
        public final boolean watching;
        public final int moves;
        public final String note;
        /** 正在被看门的包名；空串表示没有。界面据此认领代理侧残留的看门。 */
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

    /** 镜像结果：成功时 message 为 null。 */
    public interface MirrorCallback {
        void onResult(boolean success, String message);
    }

    private volatile IBinder binder;

    /** 最近一次握手/心跳拿到的看门状态；attach 后界面可据此认领代理侧残留的看门。 */
    private volatile WatchState lastWatch;

    public WatchState lastWatch() {
        return lastWatch;
    }

    /** 由广播接收器送入代理的 Binder 句柄；同一句柄重复送达时直接忽略。 */
    public void attach(IBinder agentBinder) {
        if (agentBinder == binder) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            agentBinder.transact(TRANSACT_PING, data, reply, 0);
            reply.readException();
            int agentUid = reply.readInt();
            int displayId = reply.readInt();
            boolean watching = reply.readInt() != 0;
            int moves = reply.readInt();
            String note = reply.readString();
            String watched = reply.readString();
            this.binder = agentBinder;
            Log.i(TAG, "注入代理已就绪：uid=" + agentUid + " display=" + displayId
                    + " 看门=" + watching + "(" + watched + ") 已搬回=" + moves + " 备注=" + note);
            lastWatch = new WatchState(watching, moves, note, watched);
        } catch (Throwable t) {
            Log.w(TAG, "与注入代理握手失败", t);
            this.binder = null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public boolean isAttached() {
        return binder != null;
    }

    /**
     * 心跳，同时把代理的看门状态取回来。代理在 5 秒收不到任何调用时会重新广播 Binder
     * （用于 App 重启后自愈）；前台定期 PING 既保持代理安静，也顺带刷新状态栏。
     *
     * 同步调用：与 {@link #attach} 一样跑在调用线程上，代理侧处理只是写几个字段。
     */
    public WatchState ping() {
        IBinder target = binder;
        if (target == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            target.transact(TRANSACT_PING, data, reply, 0);
            reply.readException();
            reply.readInt(); // agentUid
            reply.readInt(); // displayId
            boolean watching = reply.readInt() != 0;
            int moves = reply.readInt();
            String note = reply.readString();
            String watched = reply.readString();
            lastWatch = new WatchState(watching, moves, note, watched);
            return lastWatch;
        } catch (Throwable t) {
            Log.w(TAG, "心跳失败，等待代理重新广播", t);
            binder = null;
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 让代理看住这个包：它一旦被搬到别的屏（应用自己发起的启动不带 display，
     * 就会把整条 root task 挪回主屏），代理立刻搬回 displayId。
     *
     * 看门的生命周期由本端掌握，而且**只在投放这一小段里**有效：代理侧还有
     * "目标稳定若干秒自动松开"的兜底，界面这里则在 onPause / onDestroy 就撤销。
     * 留着看门不放，用户在主屏点该应用图标时会被反复拽回目标屏，表现为"回不到前台"。
     *
     * 同步调用：必须确保在这条事务之后才去 am start，否则可能先启动、后看门，
     * 那一次搬屏就没人接。
     */
    public void watch(String packageName, int displayId) {
        if (packageName == null) {
            return;
        }
        IBinder target = binder;
        if (target == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(packageName);
            data.writeInt(displayId);
            target.transact(TRANSACT_WATCH, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "开始看门失败", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 撤销看门。同步调用：界面即将销毁时 oneway 可能来不及送达，看门会留在代理侧
     * 继续把应用钉在目标屏上（与 STOP_MIRROR 同理）。
     */
    public void unwatch() {
        IBinder target = binder;
        if (target == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            target.transact(TRANSACT_UNWATCH, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "停止看门失败", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 目标包的 root task 现在在哪块屏。
     *
     * 存在的理由：`am start-activity --display N` 之后整条 root task 会先跳到主屏，
     * 由看门再搬回仪表屏，中间有一个几百毫秒到一两秒的窗口。调用方要判断"它到底在
     * 哪块屏上"，必须用这个真实屏位，不能用"刚发过启动命令"来推断。
     *
     * 同步调用，必须在后台线程用。
     *
     * @return 屏号；-1 表示没有该任务；-2 表示代理缺少原语或未连接
     */
    public int taskDisplay(String packageName) {
        IBinder target = binder;
        if (target == null || packageName == null) {
            return -2;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(packageName);
            target.transact(TRANSACT_TASK_DISPLAY, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "查询任务屏位失败", t);
            return -2;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 把该包的 root task 搬到目标屏（不是新启动一个）。
     *
     * 存在的理由：`am start-activity --display N` 在目标屏上没有该包的任务时会**新建**
     * 一条 root task，于是同一个应用在两块屏上各跑一份，各有各的页面和动画。
     * 已经存在任务时把它搬过去才对。代理侧会把该包的**所有**任务都搬过去。
     *
     * 同步调用，必须在后台线程用。
     *
     * @return {第一个任务原来的屏位（-1 没有任务、-2 原语缺失或未连接）, 本端请求搬动的任务数}
     */
    public int[] moveToDisplay(String packageName, int displayId) {
        IBinder target = binder;
        if (target == null || packageName == null) {
            return new int[]{-2, 0};
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(packageName);
            data.writeInt(displayId);
            target.transact(TRANSACT_MOVE_TO_DISPLAY, data, reply, 0);
            reply.readException();
            return new int[]{reply.readInt(), reply.readInt()};
        } catch (Throwable t) {
            Log.w(TAG, "请求搬屏失败", t);
            return new int[]{-2, 0};
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 让代理（uid 2000）用 am start-activity 把目标应用送上网守 display。
     * 普通应用自己 startActivity + setLaunchDisplayId 会被 AMS 以
     * "Permission Denial: ... with launchDisplayId=N" 拒绝，除非目标应用本身支持多屏。
     */
    public void launch(final int displayId, final String packageName, final String activityName,
            final LaunchCallback callback) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                IBinder target = binder;
                if (target == null) {
                    callback.onResult(false, "注入代理未连接");
                    return;
                }
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                boolean success = false;
                String message;
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(displayId);
                    data.writeString(packageName);
                    data.writeString(activityName);
                    target.transact(TRANSACT_LAUNCH, data, reply, 0);
                    reply.readException();
                    int exitCode = reply.readInt();
                    String output = reply.readString();
                    success = exitCode == 0;
                    message = output == null ? "" : output.trim();
                    if (!success) {
                        Log.w(TAG, "代理启动失败，am 退出码 " + exitCode + "：" + message);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "调用代理启动失败", t);
                    message = String.valueOf(t);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                callback.onResult(success, message);
            }
        }, "agent-launch");
        worker.setDaemon(true);
        worker.start();
    }

    public void setDisplay(int displayId) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(displayId);
            transact(TRANSACT_SET_DISPLAY, data);
        } finally {
            data.recycle();
        }
    }

    public void touch(int action, float x, float y, long downTime, long eventTime) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(action);
            data.writeFloat(x);
            data.writeFloat(y);
            data.writeLong(downTime);
            data.writeLong(eventTime);
            transact(TRANSACT_TOUCH, data);
        } finally {
            data.recycle();
        }
    }

    public void key(int keyCode) {        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(keyCode);
            transact(TRANSACT_KEY, data);
        } finally {
            data.recycle();
        }
    }

    /**
     * 向代理要应用清单。代理是 uid 2000，不受包可见性过滤，能拿到全部可启动应用；
     * App 自己查只能拿到其中一小撮。
     */
    public void listApps(final AppsCallback callback) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                IBinder target = binder;
                if (target == null) {
                    callback.onApps(new ArrayList<AppRepo.Entry>());
                    return;
                }
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                List<AppRepo.Entry> result = new ArrayList<AppRepo.Entry>();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    target.transact(TRANSACT_LIST_APPS, data, reply, 0);
                    reply.readException();
                    ArrayList<String> flat = reply.createStringArrayList();
                    if (flat != null) {
                        for (int i = 0; i + 2 < flat.size(); i += 3) {
                            result.add(new AppRepo.Entry(flat.get(i), flat.get(i + 1), flat.get(i + 2)));
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "向代理索取应用清单失败", t);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                Log.i(TAG, "代理下发可投屏应用 " + result.size() + " 个");
                callback.onApps(result);
            }
        }, "agent-list-apps");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 把仪表盘画面镜像到本端这个 Surface 上。
     *
     * Surface 走 Parcel 直接传给代理（Surface 是 Parcelable，writeToParcel 把
     * IGraphicBufferProducer 作为强 binder 送过去），这与原版把 App 的 TextureView
     * Surface 交给 uid-2000 傀儡是同一个做法。
     */
    public void startMirror(final Surface surface, final MirrorCallback callback) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                IBinder target = binder;
                if (target == null) {
                    callback.onResult(false, "注入代理未连接");
                    return;
                }
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeParcelable(surface, 0);
                    target.transact(TRANSACT_START_MIRROR, data, reply, 0);
                    reply.readException();
                    String error = reply.readString();
                    callback.onResult(error == null, error);
                } catch (Throwable t) {
                    Log.w(TAG, "请求镜像失败", t);
                    callback.onResult(false, String.valueOf(t));
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }, "agent-mirror");
        worker.setDaemon(true);
        worker.start();
    }

    public void stopMirror() {
        IBinder target = binder;
        if (target == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            // 必须同步：oneway 在进程即将退出时可能来不及送达，镜像显示就会留在
            // SurfaceFlinger 里一直泄漏到重启。
            target.transact(TRANSACT_STOP_MIRROR, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "请求停止镜像失败", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void transact(int code, Parcel data) {
        IBinder target = binder;
        if (target == null) {
            return;
        }
        try {
            target.transact(code, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "代理已失联，等待重新广播", e);
            binder = null;
        }
    }

    /**
     * 一次点击。用户的手指就是 DOWN/UP 一对事件，自动补点必须走同一条路——
     * 不能用 KeyEvent 或其它捷径，否则落点语义和手指不一致。
     *
     * displayId 只是让调用方读起来完整：代理的 TOUCH 不带 display，
     * 用的是它自己 SET_DISPLAY 记下的那块屏，所以调用前必须 setDisplay。
     *
     * 为什么分支要补回这个方法：母工程 v1.0.0 的 InjectClient 没有它（新骨架不补点），
     * 但本分支 CastActivity.tapUntilTargetPage 是实测闭环，方法体逐字保留、内部就调用
     * injector.tap(...)。删掉那个调用点等于改行为，所以把客户端侧的点击助手补回来。
     * 它只用现成的 touch() 发 DOWN/UP 一对事件，不涉及任何代理侧协议。
     */
    public void tap(int displayId, float x, float y) {
        long now = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, x, y, now, now);
        touch(MotionEvent.ACTION_UP, x, y, now, now + TAP_DURATION_MS);
    }
}
