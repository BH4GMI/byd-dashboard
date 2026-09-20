package com.byd.dashcast.privileged;

import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「进入仪表屏预览」的落地实现 —— 复刻原版 Just Dashboard 的画面通路。
 *
 * 机制（证据见 docs/PREVIEW_MECHANISM_ZH.md 与 c0/l.smali）：
 *
 *   SurfaceControl.createDisplay(name, secure)               -> 我们自己的 display token
 *   SurfaceFlinger 侧的 layerStack 是共享的，把目标屏的 layerStack 挂到我们的 display 上，
 *   两块屏就消费同一批 layer；再把我们的 display 的输出 surface 指向 App 主屏上那个
 *   TextureView 的 Surface，画面就过去了。
 *
 *   openTransaction
 *     setDisplayLayerStack(token, 目标屏的 layerStack)   <- 复用图层栈
 *     setDisplayProjection(token, 0, src, dst)           <- 缩放/裁切
 *     setDisplaySurface(token, App 的 Surface)           <- 输出改道
 *   closeTransaction
 *
 * 本质是 SurfaceFlinger 的合成输出重定向，不是截图、不是 readback、不经过 CPU，也不需要编解码。
 *
 * 全部走反射：这些方法在 SDK 32 的 android.jar 里是 @hide（实测本车七个方法全都在，
 * 见 preview-probe list 的输出）。
 */
final class PreviewDisplay {

    private static final String SC = "android.view.SurfaceControl";

    private final int targetDisplayId;
    private final int width;
    private final int height;
    private final int layerStack;
    private final String targetName;

    private IBinder token;

    PreviewDisplay(int targetDisplayId) throws Exception {
        this.targetDisplayId = targetDisplayId;
        Object info = getDisplayInfo(targetDisplayId);
        if (info == null) {
            throw new IllegalStateException("拿不到 display " + targetDisplayId + " 的 DisplayInfo");
        }
        this.layerStack = intField(info, "layerStack");
        this.width = intField(info, "logicalWidth");
        this.height = intField(info, "logicalHeight");
        this.targetName = (String) objField(info, "name");
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("display " + targetDisplayId + " 尺寸无效: "
                    + width + "x" + height);
        }
    }

    int layerStack() {
        return layerStack;
    }

    String describe() {
        return "display " + targetDisplayId + " (" + targetName + ") "
                + width + "x" + height + " layerStack=" + layerStack;
    }

    /**
     * 把预览输出接到给定的 Surface 上。传 null 等价于 {@link #detach()}。
     * 可重复调用：会先销毁旧的 display 再建新的（TextureView 重建时走这条路）。
     */
    synchronized boolean attach(Surface surface) throws Exception {
        detach();
        if (surface == null || !surface.isValid()) {
            return false;
        }

        IBinder t = (IBinder) invokeStatic(SC, "createDisplay",
                new Class<?>[]{String.class, boolean.class},
                new Object[]{"dashcast-preview", Boolean.FALSE});
        if (t == null) {
            return false;
        }

        // 提交失败的话必须把刚建的 display 收掉，否则会一直挂在 SurfaceFlinger 里。
        boolean committed = false;
        try {
            invokeStatic(SC, "openTransaction", null, null);
            invokeStatic(SC, "setDisplayLayerStack",
                    new Class<?>[]{IBinder.class, int.class},
                    new Object[]{t, layerStack});
            invokeStatic(SC, "setDisplayProjection",
                    new Class<?>[]{IBinder.class, int.class, Rect.class, Rect.class},
                    new Object[]{t, 0, new Rect(0, 0, width, height), new Rect(0, 0, width, height)});
            invokeStatic(SC, "setDisplaySurface",
                    new Class<?>[]{IBinder.class, Surface.class},
                    new Object[]{t, surface});
            invokeStatic(SC, "closeTransaction", null, null);
            committed = true;
        } finally {
            if (!committed) {
                try {
                    invokeStatic(SC, "destroyDisplay",
                            new Class<?>[]{IBinder.class}, new Object[]{t});
                } catch (Throwable ignored) {
                    // 已经尽力回收；下面把 token 置空让下次 attach 重新建。
                }
            }
        }

        token = t;
        return true;
    }

    /** 销毁我们自己的 display。对目标屏本身没有任何副作用——layerStack 是共享的。 */
    synchronized void detach() {
        IBinder t = token;
        token = null;
        if (t == null) {
            return;
        }
        try {
            invokeStatic(SC, "destroyDisplay", new Class<?>[]{IBinder.class}, new Object[]{t});
        } catch (Throwable ignored) {
            // 进程退出时 SurfaceFlinger 也会回收；不因为清理失败而影响主流程。
        }
    }

    // ------------------------------------------------------------- reflection

    private static Object getDisplayInfo(int displayId) throws Exception {
        Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Method getInstance = dmg.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object inst = getInstance.invoke(null);
        Method getInfo = dmg.getDeclaredMethod("getDisplayInfo", int.class);
        getInfo.setAccessible(true);
        return getInfo.invoke(inst, displayId);
    }

    private static int intField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getField(name);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    private static Object objField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static Object invokeStatic(String className, String method,
                                       Class<?>[] types, Object[] args) throws Exception {
        Class<?> c = Class.forName(className);
        Method m = (types == null) ? c.getDeclaredMethod(method) : c.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
