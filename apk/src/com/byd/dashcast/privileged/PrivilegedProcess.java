package com.byd.dashcast.privileged;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * 常驻特权进程：以 shell(uid 2000) 身份运行，承载「预览」和「触摸」两条腿。
 *
 * 启动方式（复刻原版 Just Dashboard 实测到的形态，见 docs/PREVIEW_MECHANISM_ZH.md §2）：
 *
 *   APK=$(pm path com.byd.dashcast | sed 's/package://')
 *   nohup sh -c "CLASSPATH=$APK app_process /system/bin \
 *     --nice-name=dashcast-priv com.byd.dashcast.privileged.PrivilegedProcess 2" \
 *     >/dev/null 2>&1 &
 *
 * 关键点：CLASSPATH 直接指向**已安装的 APK**，特权代码就是 App 自己 dex 里的这几个类。
 * 不需要第二个 jar、不需要 push、不需要版本对齐 —— 这一整类不一致问题直接消失。
 *
 * 参数：
 *   <targetDisplayId>  仪表盘屏的 displayId，默认 2
 *   selftest           只做环境自检并退出（不广播、不建屏，零副作用）
 *
 * 回传通道：就绪后向 App 发一个显式广播，extras 里用 Bundle.putBinder 带上本进程的 Binder。
 * App 侧 registerReceiver + extras.getBinder 取回，之后所有跨进程调用都走这个 Binder。
 * 用的是公开 API（putBinder/getBinder），不需要原版那个 @hide 的 Intent.getIBinderExtra。
 */
public final class PrivilegedProcess {

    private static final int DEFAULT_TARGET_DISPLAY = 2;

    private static PreviewDisplay preview;
    private static InputInjector injector;
    private static Context context;
    private static int targetDisplay = DEFAULT_TARGET_DISPLAY;

    public static void main(String[] args) {
        boolean selfTest = false;
        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    continue;
                }
                if ("selftest".equals(a.trim())) {
                    selfTest = true;
                } else {
                    try {
                        targetDisplay = Integer.parseInt(a.trim());
                    } catch (Throwable ignored) {
                        // 非法参数就沿用默认值，不要因此起不来。
                    }
                }
            }
        }

        log("启动 pid=" + Process.myPid() + " uid=" + Process.myUid()
                + " targetDisplay=" + targetDisplay + " selftest=" + selfTest);

        try {
            bypassHiddenApi();
            log("hidden-api bypass OK");
        } catch (Throwable t) {
            log("hidden-api bypass 失败: " + t);
        }

        try {
            context = initContext();
            log("Context OK: packageName=" + context.getPackageName());
        } catch (Throwable t) {
            log("拿 Context 失败: " + t);
            if (selfTest) {
                log("自检结束（Context 不可用）");
                return;
            }
            return;
        }

        try {
            injector = InputInjector.create(targetDisplay);
            log("InputManager OK，注入目标 display=" + injector.displayId());
        } catch (Throwable t) {
            log("InputManager 准备失败，触摸不可用: " + t);
        }

        try {
            preview = new PreviewDisplay(targetDisplay);
            log("预览目标 OK: " + preview.describe());
        } catch (Throwable t) {
            log("预览目标解析失败，预览不可用: " + t);
        }

        if (selfTest) {
            log("自检结束：Context=" + (context != null)
                    + " injector=" + (injector != null)
                    + " preview=" + (preview != null));
            return;
        }

        if (injector == null && preview == null) {
            log("两条腿都不可用，退出");
            return;
        }

        // 进程无论如何退出，都要把自己建的 display 收掉，不在 SurfaceFlinger 里留垃圾。
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                detachPreview();
            }
        }, "dashcast-priv-cleanup"));

        Channel channel = new Channel();

        // 停止只走 Binder 的 CODE_SHUTDOWN（外加 App 侧的 pkill 兜底）。
        // 曾经这里还注册过一个 ACTION_STOP 广播接收器，实测在 app_process 进程里
        // **必然抛 SecurityException**（"Unable to find app for caller" ——
        // 这个进程没有向 AMS 注册过 app 记录），属于一条永远走不通的死路，已删除。
        try {
            broadcastReady(channel);
            log("已广播 " + PrivilegedProtocol.ACTION_READY + "，等待 App 连接");
        } catch (Throwable t) {
            log("广播就绪失败: " + t);
        }

        Looper.loop();
        log("主循环退出");
    }

    // ------------------------------------------------------------- Channel

    /** App 侧通过广播拿到这个 Binder，之后预览与触摸都走它。 */
    private static final class Channel extends Binder {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case PrivilegedProtocol.CODE_PING: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    if (reply != null) {
                        reply.writeInt(Process.myUid());
                        reply.writeInt(targetDisplay);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_SET_SURFACE: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    Surface surface = data.readParcelable(Surface.class.getClassLoader());
                    boolean ok = false;
                    PreviewDisplay p = preview;
                    if (p != null) {
                        try {
                            ok = p.attach(surface);
                        } catch (Throwable t) {
                            log("attach 失败: " + t);
                        }
                    }
                    log("SET_SURFACE -> " + ok);
                    if (reply != null) {
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_CLEAR_SURFACE: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    detachPreview();
                    if (reply != null) {
                        reply.writeInt(1);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_TOUCH: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    int action = data.readInt();
                    float x = data.readFloat();
                    float y = data.readFloat();
                    long downTime = data.readLong();
                    long eventTime = data.readLong();
                    InputInjector inj = injector;
                    boolean ok = inj != null && inj.touch(action, x, y, downTime, eventTime);
                    if (reply != null) {
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_KEY: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    int keyCode = data.readInt();
                    int keyAction = data.readInt();
                    InputInjector inj = injector;
                    boolean ok = inj != null && inj.key(keyCode, keyAction);
                    if (reply != null) {
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_ATTACH_CLIENT: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    IBinder client = data.readStrongBinder();
                    if (reply != null) {
                        reply.writeInt(client != null ? 1 : 0);
                    }
                    if (client != null) {
                        watchClientDeath(client);
                    }
                    return true;
                }
                case PrivilegedProtocol.CODE_SHUTDOWN: {
                    data.enforceInterface(PrivilegedProtocol.DESCRIPTOR);
                    if (reply != null) {
                        reply.writeInt(1);
                    }
                    log("收到退出事务");
                    shutdownSoon();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * 盯着 App 进程的生死。它一死（{@code am force-stop}、被 LMK 回收、崩溃），
     * 本进程自行退出 —— 退出走 {@code System.exit}，shutdown hook 会把 display 收掉。
     *
     * <p>这条是"后台不留资源"的**结构性保证**：只靠 App 主动发 SHUTDOWN 是不够的，
     * 因为进程被强杀时根本走不到那里（实测：force-stop 之后 dashcast-priv 仍然活着）。
     */
    private static void watchClientDeath(IBinder client) {
        try {
            client.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    log("客户端进程已退出，本进程一并退出");
                    shutdownSoon();
                }
            }, 0);
            log("已挂上客户端死亡通知");
        } catch (RemoteException e) {
            // linkToDeath 抛 RemoteException 的含义就是"对端已经死了"，直接收工。
            log("客户端已不在，直接退出");
            shutdownSoon();
        }
    }

    private static void detachPreview() {
        PreviewDisplay p = preview;
        if (p != null) {
            p.detach();
        }
    }

    /** Binder 线程里不能直接 System.exit，转回主线程再退。 */
    private static void shutdownSoon() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                detachPreview();
                log("退出");
                System.exit(0);
            }
        });
    }

    private static void broadcastReady(IBinder binder) {
        Intent intent = new Intent(PrivilegedProtocol.ACTION_READY);
        intent.setPackage(PrivilegedProtocol.APP_PACKAGE);
        Bundle extras = new Bundle();
        extras.putBinder(PrivilegedProtocol.KEY_BINDER, binder);
        intent.putExtras(extras);
        context.sendBroadcast(intent);
    }

    // ------------------------------------------------------------- bootstrap

    /**
     * 裸 app_process 进程没有 Application。走 ActivityThread.systemMain() 造一个，
     * 但它的包名是 "android"，而 uid 2000 实际拥有的包是 com.android.shell ——
     * 用 shell 的 package context 才有正确的 opPackageName 与权限归属。
     */
    private static Context initContext() throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        systemMain.invoke(null);
        Method currentApplication = at.getDeclaredMethod("currentApplication");
        currentApplication.setAccessible(true);
        Context app = (Context) currentApplication.invoke(null);
        if (app == null) {
            throw new IllegalStateException("ActivityThread.currentApplication() 返回 null");
        }
        return app.createPackageContext("com.android.shell", 0);
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

    /** 日志一律走 stderr：stdout 保持干净，将来若要接二进制流不会串。 */
    private static void log(String s) {
        System.err.println("[priv] " + s);
        System.err.flush();
    }

    private PrivilegedProcess() {
    }
}
