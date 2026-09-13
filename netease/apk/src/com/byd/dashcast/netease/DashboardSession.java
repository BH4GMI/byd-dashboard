package com.byd.dashcast.netease;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/**
 * 仪表盘屏（副屏）的定位。
 *
 * 关键事实（车机 `dumpsys display`）：仪表盘是**车机自己已有的投影屏**，
 * 不需要也不应该自建：
 *
 *   Display 2: "fission_bg_XDJAScreenProjection"
 *     FLAG_PRESENTATION, 1920x720, layerStack 2, density 320
 *     owner com.byd.containerservice (uid 1000)
 *     仪表应用 com.byd.naviauto/com.byd.automap.meter.MeterActivity 正跑在上面
 *   Display 3/4: "shared_fission_bg_XDJAScreenProjection_0/1"（共享变体）
 *
 * 之前的实现自建了一块 VirtualDisplay（"dashcast"，FLAG_PRIVATE，display 13）
 * 把应用投进去，结果只在主屏的预览窗口里显示 —— 应用根本没上仪表盘。
 * 那是把"投屏"理解成了"造一块屏"，实际要的是"投到车机已有的那块仪表屏上"。
 *
 * 目标屏带 FLAG_PRESENTATION 而非 FLAG_PRIVATE，所以 uid 2000 用
 * am start-activity --display N 就能把任意应用送上去。
 */
public final class DashboardSession {

    private static final String TAG = "dashcast";

    /** 车机仪表投影屏的名字，取自 Display.getName()。 */
    private static final String CLUSTER_DISPLAY_NAME = "fission_bg_XDJAScreenProjection";

    /**
     * 车机仪表投影屏的 displayId。这是**车机平台常量**，不是随手写死的魔法数：
     * 车机 dumpsys display 显示 display 2 = "fission_bg_XDJAScreenProjection"
     * （FLAG_PRESENTATION，1920x720，layerStack 2，owner com.byd.containerservice uid 1000），
     * 且仪表应用 com.byd.naviauto/com.byd.automap.meter.MeterActivity 就常驻其上。
     *
     * 为什么不能只靠枚举：DisplayManager.getDisplays() 从应用侧看不到它（车机对
     * 该投影屏做了可见性过滤，只返回 display 3/4 这两个 shared 变体），
     * 而 ActivityTaskManager 仍然允许 uid 2000 用 am start-activity --display 2 投递。
     * 所以能枚举到就按名字命中，枚举不到就按这个常量走。
     */
    private static final int CLUSTER_DISPLAY_ID = 2;

    private final Context context;
    private int displayId = -1;

    public DashboardSession(Context context) {
        this.context = context;
    }

    /** 解析仪表盘屏；返回 displayId，找不到为 -1。 */
    public int resolve() {
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display match = null;
        for (Display display : displayManager.getDisplays()) {
            int id = display.getDisplayId();
            if (id == Display.DEFAULT_DISPLAY) {
                continue;
            }
            String name = display.getName();
            Log.i(TAG, "应用可见的候选屏 display=" + id + " name=" + name
                    + " flags=0x" + Integer.toHexString(display.getFlags()));
            if (CLUSTER_DISPLAY_NAME.equals(name)) {
                match = display;
            }
        }
        if (match != null) {
            displayId = match.getDisplayId();
            Log.i(TAG, "仪表盘屏按名字命中 displayId=" + displayId);
        } else {
            displayId = CLUSTER_DISPLAY_ID;
            Log.i(TAG, "枚举不到 " + CLUSTER_DISPLAY_NAME
                    + "（应用侧被可见性过滤），改用车机常量 displayId=" + displayId);
        }
        return displayId;
    }

    public boolean isActive() {
        return displayId >= 0;
    }

    public int displayId() {
        return displayId;
    }
}
