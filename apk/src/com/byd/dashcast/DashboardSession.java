package com.byd.dashcast;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * 仪表盘屏（副屏）的定位。
 *
 * <h3>2026-09-20 实测更正：必须投到「共享变体」，不能投 display 2</h3>
 *
 * 本车由 {@code com.byd.containerservice} 造了**三块**相关显示：
 *
 * <pre>
 *   Display 2: "fission_bg_XDJAScreenProjection"                    layerStack 2
 *              owner com.byd.containerservice, FLAG_PRESENTATION|FLAG_OWN_CONTENT_ONLY
 *              车机导航 com.byd.launchermap 用 Presentation(type=2037) 常驻其上，
 *              window 层级 mBaseLayer=31000
 *   Display 3: "shared_fission_bg_XDJAScreenProjection_0"          layerStack 3
 *   Display 4: "shared_fission_bg_XDJAScreenProjection_1"          layerStack 4
 * </pre>
 *
 * 反编译 {@code AutoDisplayService} 看到共享变体是官方为"多应用共用仪表屏"准备的：
 * {@code createVirtualDisplay(name, w, h, 320, surface, 11)}，其中 11 = PUBLIC|PRESENTATION|
 * OWN_CONTENT_ONLY，且 {@code AutoSharedDisplay} 把它们的容器层接到 display 2 的同一
 * layerStack 上（{@code transaction.setLayerStack(sc, outDisplayInfo.layerStack)}、
 * {@code transaction.setLayer(sc, i + 100)}）。
 *
 * <p><b>实测结论（同一台车、同一时刻、逐像素比对）</b>：
 * <ul>
 *   <li>投 display 2 → 应用 window(BASE_APPLICATION, layer 21000) 被导航的
 *       Presentation(layer 31000) 盖住，{@code screencap -d 2} 三帧 SHA 全同，画面没上屏。</li>
 *   <li>投 display 3/4 → 内容真的出现在物理仪表屏上，且盖过导航。</li>
 * </ul>
 *
 * 旧实现把 {@code CLUSTER_DISPLAY_ID} 写死成 2，是"投上去了但看不见"的根因。
 *
 * <h3>2026-09-24 补充实测：投到槽位 ≠ 任务真的落在槽位上</h3>
 *
 * 解析出正确的槽位只是第一步。同日实车复盘发现：首开自动那次
 * {@code am start-activity --display 3 -n …} 之后，网易云任务被建在 display 0
 * （{@code wm_create_task: [0,10]}），仪表槽 3/4 全程为空 —— 也就是
 * <b>启动参数不保证落点</b>。链路里必须有人搬回去并回查
 * （{@link InjectClient#ensureOnDisplay}），解析结果本身不能当成"已经投上去了"。
 */
public final class DashboardSession {

    private static final String TAG = "dashcast";

    /**
     * 共享变体的名字前缀。车机建了两个（_0 → display 3、_1 → display 4），
     * 它们才是给应用用的入口。
     */
    private static final String SHARED_DISPLAY_PREFIX = "shared_fission_bg_XDJAScreenProjection";

    /** 主投影屏的名字。**不要投它**，只用来识别与解释。 */
    private static final String MAIN_PROJECTION_NAME = "fission_bg_XDJAScreenProjection";

    /**
     * 兜底 displayId：共享变体 _0。
     *
     * <p>为什么要有兜底：{@code DisplayManager.getDisplays()} 从应用侧只能看到共享变体，
     * 但车机若改了可见性策略，枚举就会落空，这时按平台常量走仍然可用（实测 display 3
     * 稳定是 shared_..._0）。
     */
    private static final int SHARED_DISPLAY_FALLBACK_ID = 3;

    /**
     * 主投影屏的兜底 displayId。
     *
     * <p>为什么必须兜底：**应用进程枚举不到这块屏**。实测 {@code DisplayManager.getDisplays()}
     * 在车机上只回 display 3/4（主投影屏被可见性过滤），而 {@code screencap} 跑在 uid 2000
     * 里、按 id 直接就能抓到（实测 display 2 = 170 KB 有内容、display 3/4 = 7131 B 全黑）。
     *
     * <p>这与 {@link #SHARED_DISPLAY_FALLBACK_ID} 是同一类兜底：枚举优先，枚举不到就用
     * 这台车机的实测常量，并且**一定**在日志里写明走的是哪条路。
     */
    private static final int PROJECTION_DISPLAY_FALLBACK_ID = 2;

    private final Context context;
    private int displayId = -1;
    private boolean foundByName;
    /**
     * 主投影屏：仪表盘**实际显示内容**的那块。
     *
     * <p>它与 {@link #displayId}（投屏目标槽位）是两个不同的屏，用途正好相反：
     * 槽位是"往哪里投"，这块是"投完在哪儿看得见"。找不到为 -1。
     */
    private int projectionDisplayId = -1;

    public DashboardSession(Context context) {
        this.context = context;
    }

    /**
     * 解析目标副屏；返回 displayId，找不到为 -1。
     *
     * <p>只认共享变体。若只枚举到主投影屏（display 2），**不用它** —— 实测那条路
     * 画面会被车机导航盖住。
     */
    public int resolve() {
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        int shared = -1;
        int sharedFallback = -1;
        projectionDisplayId = -1;

        for (Display display : displayManager.getDisplays()) {
            int id = display.getDisplayId();
            if (id == Display.DEFAULT_DISPLAY) {
                continue;
            }
            String name = display.getName();
            AppLog.i(TAG, "应用可见的候选屏 display=" + id + " name=" + name
                    + " flags=0x" + Integer.toHexString(display.getFlags()));
            if (name == null) {
                continue;
            }
            if (name.startsWith(SHARED_DISPLAY_PREFIX)) {
                if (shared < 0) {
                    shared = id;
                }
                if (name.endsWith("_0")) {
                    sharedFallback = id;
                }
            } else if (MAIN_PROJECTION_NAME.equals(name)) {
                // 主投影屏不能当投屏目标（实测画面会被车机导航盖住），但**预览必须看它**：
                // 共享槽位的内容是容器服务镜像到它的 layerStack 上的，抓槽位只会得到全黑。
                projectionDisplayId = id;
            }
        }

        if (sharedFallback >= 0) {
            displayId = sharedFallback;
            foundByName = true;
            AppLog.i(TAG, "副屏按名字命中共享变体 _0：displayId=" + displayId);
        } else if (shared >= 0) {
            displayId = shared;
            foundByName = true;
            AppLog.i(TAG, "副屏命中共享变体（非 _0）：displayId=" + displayId);
        } else {
            displayId = SHARED_DISPLAY_FALLBACK_ID;
            foundByName = false;
            AppLog.i(TAG, "枚举不到共享变体（可见性过滤），改用车机常量 displayId=" + displayId
                    + "（主投影屏=" + projectionDisplayId + "，它不是投屏目标）");
        }

        if (projectionDisplayId < 0) {
            projectionDisplayId = PROJECTION_DISPLAY_FALLBACK_ID;
            AppLog.i(TAG, "主投影屏枚举不到（可见性过滤），用实测常量 displayId=" + projectionDisplayId);
        }
        return displayId;
    }

    public boolean isActive() {
        return displayId >= 0;
    }

    public int displayId() {
        return displayId;
    }

    /**
     * 仪表盘**实际显示内容**的那块屏（主投影屏），界面预览抓它；找不到为 -1。
     *
     * <p>注意它和 {@link #displayId()} 不是一回事：投屏往槽位投，内容经容器服务镜像到
     * 这块屏上才看得见。抓槽位只会得到全黑（实测非黑像素 0%）。
     */
    public int projectionDisplayId() {
        return projectionDisplayId;
    }

    /** 是否由枚举命中（而非兜底常量）。用于诊断展示。 */
    public boolean foundByName() {
        return foundByName;
    }
}
