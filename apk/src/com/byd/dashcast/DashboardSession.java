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
 *
 * <h3>2026-09-28 加固：认不出投屏槽位就不再猜</h3>
 *
 * 旧实现在枚举落空时按写死的 {@code displayId=3} 硬投。本机恰好是 3，但没有任何证据
 * 表明别的 DiLink 车型也是 —— 实测 DiLink 4.0 的仪表是 display 1、名字
 * {@code fission_bg_xdjaVirtualSurface}、owner {@code com.xdja.containerservice}，
 * 连"共享槽位"这个概念都不存在。赌错的代价是把用户的画面投到一块没人知道是什么的屏上
 * （后排屏、别人的投屏……），比"如实说不支持"糟糕得多。
 *
 * <p>现在：认不出来 → {@code displayId = -1}，由 {@link #unsupportedReason()} 给出
 * 可核对的原因（含应用侧实际枚举到的每一块副屏），UI 据此外显。
 * <b>本机不受影响</b>：实车日志证明本车走的是"按名字命中"（{@code 由枚举命中=true}）。
 */
public final class DashboardSession {

    private static final String TAG = "dashcast";

    /*
     * 副屏命名族不在这里 —— 它们是判定依据，统一放在 {@link DisplayTable}
     * （SLOT_PREFIX / MIRROR_PREFIX / DIRECT_PREFIX）。散在调用处的名字匹配
     * 正是"同一处判据写两遍"的来源，而这里与应用侧/daemon 侧两条数据源共用同一套判据。
     */

    /**
     * 主投影屏的实测常量 displayId。
     *
     * <p><b>这不是猜测，是本机上的正常路径</b>：应用进程**看不到**主投影屏。实测（应用
     * 身份 uid=10100 的探针输出，见 {@code work/probe_result.txt}）：
     *
     * <pre>
     *   getDisplays() 返回 3 个
     *     id=0 内置屏幕
     *     id=3 shared_fission_bg_XDJAScreenProjection_0
     *     id=4 shared_fission_bg_XDJAScreenProjection_1
     *   getDisplay(2) = null      ← 主投影屏被可见性过滤，应用侧永远枚举不到
     * </pre>
     *
     * 而 {@code screencap} 跑在 uid 2000 里、按 id 直接就能抓到（实测 display 2 = 170 KB
     * 有内容、display 3/4 = 7131 B 全黑）。所以 {@code DisplayTable.MIRROR_PREFIX} 那支名字匹配
     * 对本工程在本机**永远命中不了**，常量才是这条路的正常取值。
     *
     * <p>它与投屏槽位的处理**语义完全不同**，不能一起收敛：槽位认不出来只能判未适配
     * （见 {@link #unsupportedReason()}），因为"猜一块屏往上投"会把用户的画面送到一处
     * 没人知道的地方；主投影屏只用于**读**（预览、判页），猜错代价小得多，而且本机事实上
     * 就是枚举不到。诊断导出会写明它是否走了常量（{@link #projectionFromConstant()}）。
     */
    private static final int PROJECTION_DISPLAY_FALLBACK_ID = 2;

    private final Context context;
    /** 最近一次判定结果（应用侧那一趟，或被 daemon 精化之后的）。 */
    private DisplayTable.Pick pick;
    private int displayId = -1;
    /** 判「本机未适配」的原因；为空表示解析正常。用于日志、UI 与诊断导出。 */
    private String unsupportedReason = "";
    /**
     * 主投影屏：仪表盘**实际显示内容**的那块。
     *
     * <p>它与 {@link #displayId}（投屏目标槽位）是两个不同的屏，用途正好相反：
     * 槽位是"往哪里投"，这块是"投完在哪儿看得见"。找不到为 -1。
     */
    private int projectionDisplayId = -1;
    /** 主投影屏是否取自实测常量（而非枚举命中）。诊断用。 */
    private boolean projectionFromConstant;

    public DashboardSession(Context context) {
        this.context = context;
    }

    /**
     * 解析目标副屏。返回投屏槽位的 displayId；返回 -1 表示**本机未适配**，
     * 具体原因见 {@link #unsupportedReason()}。
     *
     * <p>只认共享变体。若只枚举到主投影屏（display 2），**不用它** —— 实测那条路
     * 画面会被车机导航盖住。
     *
     * <p>认不出来时**不猜**：旧实现会退到写死的 {@code displayId=3}，那等于赌"一台没见过的
     * 车和我们这台一样"。实测 DiLink 4.0 的仪表是 display 1、名字 {@code fission_bg_xdjaVirtualSurface}，
     * 连"槽位"这个概念都不存在 —— 赌错的代价是把用户的画面投到一块没人知道是什么的屏上。
     */
    public int resolve() {
        return apply(appSideTable(), false);
    }

    /**
     * 用 daemon 侧屏表精化。通道起来后调用（见 {@code CastActivity} 的通道拉起线程）。
     *
     * <p><b>这不是本工程的发明</b>：原版 APK 的 {@code c0/k.m(I)} 里解出的就是
     * {@code dumpsys display | grep mOverrideDisplayInfo=DisplayInfo{} 与
     * {@code logicalWidth}/{@code logicalHeight} —— 它同样靠解析 dumpsys 拿显示信息，
     * 也自己开 ADB loopback 跑 shell（{@code AdbClient} / {@code connectShell}）。
     *
     * <p>为什么必须精化：应用侧枚举**看不到**被固件按 uid 过滤掉的屏（实测本车主投影屏
     * display 2 就是 {@code getDisplay(2) = null}），而 DiLink 3/4 的仪表屏正属于这一类 ——
     * 不精化就永远发现不了它，{@link DisplayTable.Path#DIRECT} 那条通路也就无从启用。
     */
    public int refineFromDaemon(String dumpsysDisplay) {
        return apply(DisplayTable.parse(dumpsysDisplay), true);
    }

    /** 应用侧枚举。uid 就是本应用，拿不到 owner，所以 ownerUid 一律记 -1（未知不否决）。 */
    private DisplayTable appSideTable() {
        DisplayTable table = new DisplayTable();
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        for (Display display : displayManager.getDisplays()) {
            int id = display.getDisplayId();
            if (id == Display.DEFAULT_DISPLAY) {
                continue;
            }
            // 枚举结果原样落盘：本机适没适配，全靠这段证据。
            AppLog.i(TAG, "应用可见的候选屏 display=" + id + " name=" + display.getName()
                    + " flags=0x" + Integer.toHexString(display.getFlags()));
            table.add(id, display.getName(), -1, "0x" + Integer.toHexString(display.getFlags()));
        }
        return table;
    }

    /**
     * 把一份屏表的判定落到会话状态上。
     *
     * <p>应用侧与 daemon 侧**共用这一段**，所以不会出现"两个真相"：两趟走的是同一套判据，
     * 差别只在看到了哪些屏。精化是单调的（见 {@link DisplayTable#refine}），
     * 一次 dump 抖动不会把已经跑起来的投屏抖没。
     */
    private int apply(DisplayTable table, boolean fromDaemon) {
        DisplayTable.Pick next = fromDaemon
                ? DisplayTable.refine(pick, table) : table.resolve();
        pick = next;
        displayId = next.castDisplayId;
        unsupportedReason = next.isActive() ? "" : next.reason;

        // 仪表屏优先用枚举/daemon 的真实值；都没有才退到本车实测常量 ——
        // 它在应用侧**永远**枚举不到（{@code getDisplay(2) = null}），
        // 所以常量是本机的正常取值，不是猜 id。见 PROJECTION_DISPLAY_FALLBACK_ID。
        projectionDisplayId = next.clusterDisplayId;
        projectionFromConstant = false;
        if (projectionDisplayId < 0) {
            projectionDisplayId = PROJECTION_DISPLAY_FALLBACK_ID;
            projectionFromConstant = true;
        }

        AppLog.i(TAG, (fromDaemon ? "daemon 精化：" : "应用侧判定：") + next.reason
                + " ｜ 投屏目标=" + displayId
                + " 仪表屏=" + projectionDisplayId
                + (projectionFromConstant ? "（实测常量）" : "（枚举命中）"));
        if (!next.isActive()) {
            AppLog.w(TAG, "本机未适配：" + unsupportedReason + "；不做任何猜测");
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

    /**
     * 判「本机未适配」的原因；为空表示解析正常。
     *
     * <p>这是"不猜"的对外表达：上层必须据此把终态明确告诉用户，而不是继续走一条
     * 基于猜测的投屏路径。见 {@code CastActivity} 的无屏文案分支。
     */
    public String unsupportedReason() {
        return unsupportedReason;
    }

    /** 主投影屏是否取自实测常量（而非枚举命中）。诊断用。 */
    public boolean projectionFromConstant() {
        return projectionFromConstant;
    }

    /**
     * 当前投屏通路（诊断用）：{@code SLOT} / {@code DIRECT} / {@code UNSUPPORTED}。
     *
     * <p>{@code DIRECT} 是 DiLink 3/4 那条通路，本工程**没有**这些车的实机验证，
     * 所以它必须能在日志与界面里被看见，不能静默生效。
     */
    public String path() {
        return pick == null ? "未判定" : pick.path.name();
    }
}
