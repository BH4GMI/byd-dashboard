package com.byd.dashcast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机副屏表：把「这台车有哪些屏」变成一份可判定的数据，并据此选出**投屏通路**。
 *
 * <h3>为什么单独一个类，而且不依赖任何 Android API</h3>
 *
 * 本工程只有一台 DiLink 5.0 能实车验证，其它代次（3.0 / 4.0 / 5.1）的行为**无法上车确认**。
 * 把「解析 dump」与「判定通路」收进一个纯 Java 类，就可以拿真实 dump 在电脑上离线跑通 ——
 * 这是"照原版适配更多代次"里唯一能被验证的部分，因此它必须可测。
 *
 * <h3>两个数据源</h3>
 *
 * <ul>
 *   <li><b>应用侧</b>{@code DisplayManager.getDisplays()}：随时可用，但**看不到**被固件按 uid
 *       过滤掉的屏（实测本车看不到主投影屏 display 2：{@code getDisplay(2) = null}）。</li>
 *   <li><b>daemon 侧</b>（uid 2000 跑 {@code dumpsys display}）：看得到全部屏，还带 owner。
 *       这不是本工程的发明 —— 原版 APK 的 {@code c0/k.m(I)} 里解出的就是
 *       {@code dumpsys display | grep mOverrideDisplayInfo=DisplayInfo{}，与
 *       {@code logicalWidth}/{@code logicalHeight}，它同样靠解析 dumpsys 拿显示信息。</li>
 * </ul>
 *
 * 所以识别分两趟：应用侧先给一个立刻可用的结论，通道起来后再用 daemon 结果**精化**
 * （见 {@link #refine}）。两趟走的是同一套判据，不会出现"两个真相"。
 *
 * <h3>通路与代次</h3>
 *
 * 原版 APK 的代次分叉已解出：{@code SDK_INT > 30} 才启动
 * {@code SecondaryDisplayService}（自建虚拟屏 + 往仪表屏挂叠加窗口），SDK ≤ 30 时
 * **完全不启动**；但两代都走 {@code ActivityTaskManager.getService()} 去**搬任务**
 * （{@code moveStackToDisplay} / {@code setTaskResizeable} / {@code setTaskWindowingMode}）。
 * 也就是说旧代次去掉的是"自己造屏"那一层，改为把应用搬到车机**已有**的仪表屏上。
 *
 * <p>本工程复刻的是 5.x 那条通路（投进共享槽位，容器服务再镜像到主投影屏）。要在 3/4 上可用，
 * 就得把"仪表屏本身"当成投屏目标 —— 这就是 {@link Path#DIRECT}。
 *
 * <h3>判据为什么必须用精确族名</h3>
 *
 * 本车的主投影屏叫 {@code fission_bg_XDJAScreenProjection}（byd 族），把它当投屏目标是**错的**
 * （实测画面会被车机导航盖住）。所以三族各自精确匹配，绝不写"看着像仪表屏就投"这类宽松规则：
 * 本车有槽位，永远走 {@link Path#SLOT}，行为逐项不变。
 */
public final class DisplayTable {

    /** 一条副屏记录。{@code flags} 只用于诊断落盘，不参与判定。 */
    public static final class Entry {
        public final int id;
        public final String name;
        /** owner 的 uid；应用侧拿不到 owner，记 -1 表示未知。 */
        public final int ownerUid;
        /** dump 里的 FLAG_* 原文，仅诊断用。 */
        public final String flags;

        Entry(int id, String name, int ownerUid, String flags) {
            this.id = id;
            this.name = name;
            this.ownerUid = ownerUid;
            this.flags = flags;
        }

        @Override
        public String toString() {
            return id + ":" + name + (ownerUid >= 0 ? "(uid " + ownerUid + ")" : "");
        }
    }

    /** 投屏通路。 */
    public enum Path {
        /** DiLink 5.x：投进共享槽位，容器服务再把槽位镜像到主投影屏。 */
        SLOT,
        /** DiLink 3/4：没有槽位，直接把应用投到车机已有的那块仪表屏上。 */
        DIRECT,
        /** 两种拓扑都不是 —— 本机未适配。**不猜任何 id**。 */
        UNSUPPORTED
    }

    /** 判定结果。 */
    public static final class Pick {
        public final Path path;
        /** 投屏目标 displayId；{@link Path#UNSUPPORTED} 时为 -1。 */
        public final int castDisplayId;
        /** 仪表屏（"投完在哪儿看得见"）displayId；未知为 -1。 */
        public final int clusterDisplayId;
        /** 判据说明，写进日志给用户核对。 */
        public final String reason;

        Pick(Path path, int castDisplayId, int clusterDisplayId, String reason) {
            this.path = path;
            this.castDisplayId = castDisplayId;
            this.clusterDisplayId = clusterDisplayId;
            this.reason = reason;
        }

        public boolean isActive() {
            return castDisplayId >= 0;
        }
    }

    /**
     * 共享槽位族：5.x 才有，是**给应用用**的投屏入口。实测本车 _0 → display 3、_1 → display 4。
     */
    private static final String SLOT_PREFIX = "shared_fission_bg_XDJAScreenProjection";

    /**
     * 主投影屏族：5.x 的镜像屏，本车 display 2。只用于**读**（预览、判页），**不要投**。
     *
     * <p>注意它和 {@link #SLOT_PREFIX} 不冲突：槽位名以 {@code shared_} 开头。
     */
    private static final String MIRROR_PREFIX = "fission_bg_XDJAScreenProjection";

    /**
     * 仪表屏族：DiLink 3/4 的仪表屏（xdja 容器服务），社区取证名为
     * {@code fission_bg_xdjaVirtualSurface}，它**本身就是投屏目标**。
     *
     * <p>本工程**没有**这些车的实机验证：这个名字来自对 DiLink 4.0 车的公开取证记录。
     * 因此这条通路必须把"走的是它"明确写进日志与界面，不能静默生效。
     */
    private static final String DIRECT_PREFIX = "fission_bg_xdjaVirtualSurface";

    /**
     * 默认屏（主屏）的 displayId。
     *
     * <p>{@code DisplayTable} 刻意不引任何 Android API（见类注释），所以这里是字面量，
     * 而不是 {@code Display.DEFAULT_DISPLAY}。它的唯一用途是在 {@link #resolve()} 的
     * 结构判据里把主屏排除掉 —— 主屏永远不该成为投屏目标。
     */
    private static final int DEFAULT_DISPLAY_ID = 0;

    /** 车机系统服务（容器服务）的 uid。归属校验用它。 */
    private static final int SYSTEM_UID = 1000;

    /** dump 中的一块屏：名字 + displayId 在同一行，owner 与 FLAG_* 也在同一行。 */
    private static final Pattern DISPLAY_INFO = Pattern.compile(
            "m(?:Base|Override)DisplayInfo=DisplayInfo\\{\"([^\"]*)\", displayId (\\d+)");

    /** 同一行里的 {@code owner <包名> (uid <N>)}；内置屏没有这一段。 */
    private static final Pattern OWNER = Pattern.compile("owner ([^ ]+) \\(uid (\\d+)\\)");

    /** 同一行里的 FLAG_* 列表，仅用于诊断。 */
    private static final Pattern FLAG = Pattern.compile("\\bFLAG_[A-Z_]+\\b");

    private final List<Entry> entries = new ArrayList<Entry>();

    public List<Entry> entries() {
        return entries;
    }

    public void add(int id, String name, int ownerUid, String flags) {
        if (name == null) {
            return;
        }
        // 同一块屏在 dump 里会出现两次（mBaseDisplayInfo 与 mOverrideDisplayInfo），去重。
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                return;
            }
        }
        entries.add(new Entry(id, name, ownerUid, flags == null ? "" : flags));
    }

    /**
     * 解析 {@code dumpsys display} 的输出。
     *
     * <p>选 {@code mBaseDisplayInfo} / {@code mOverrideDisplayInfo} 这两行作为唯一来源，
     * 因为**只有它们把名字和 displayId 放在同一行**，同时带 owner 与 FLAG_*：
     *
     * <pre>
     * mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 2", ...,
     *     layerStack 2, ..., owner com.byd.containerservice (uid 1000), removeMode 0, ...}
     * </pre>
     *
     * <p>上面那份 {@code DisplayDeviceInfo{...}} 段只有名字、没有 displayId，所以不用它。
     */
    public static DisplayTable parse(String dump) {
        DisplayTable table = new DisplayTable();
        if (dump == null) {
            return table;
        }
        for (String line : dump.split("\n")) {
            Matcher m = DISPLAY_INFO.matcher(line);
            if (!m.find()) {
                continue;
            }
            int ownerUid = -1;
            Matcher o = OWNER.matcher(line);
            if (o.find()) {
                ownerUid = Integer.parseInt(o.group(2));
            }
            StringBuilder flags = new StringBuilder();
            Matcher f = FLAG.matcher(line);
            while (f.find()) {
                if (flags.length() > 0) {
                    flags.append(' ');
                }
                flags.append(f.group());
            }
            table.add(Integer.parseInt(m.group(2)), m.group(1), ownerUid, flags.toString());
        }
        return table;
    }

    /**
     * 按判据选出投屏通路。
     *
     * <p>归属校验：owner **已知且不是系统 uid** 的候选一律不认。这是防"第三方应用自建了一块
     * 名字碰巧相似的屏被选中"的正解 —— 原版与 BYDMate 的取证都显示，同名的自建屏
     * 归属是普通应用（例如本工程自己的预览屏 {@code dashcast} 就是 uid 10096 + FLAG_PRIVATE）。
     * owner 未知（应用侧那一趟拿不到 owner）时不因此否决，交由 daemon 那一趟精化。
     */
    public Pick resolve() {
        int slot = -1;
        int slotFirst = -1;
        int mirror = -1;
        int direct = -1;
        // 不属于任何已知族、归属可接受的副屏；结构判据的候选集（见方法末尾）。
        List<Entry> unknown = new ArrayList<Entry>();
        List<String> rejected = new ArrayList<String>();

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean foreign = e.ownerUid >= 0 && e.ownerUid != SYSTEM_UID;
            if (e.name.startsWith(SLOT_PREFIX)) {
                if (foreign) {
                    rejected.add(e + " 非系统归属");
                    continue;
                }
                if (slotFirst < 0) {
                    slotFirst = e.id;
                }
                if (e.name.endsWith("_0")) {
                    slot = e.id;
                }
            } else if (e.name.startsWith(MIRROR_PREFIX)) {
                if (foreign) {
                    rejected.add(e + " 非系统归属");
                    continue;
                }
                if (mirror < 0) {
                    mirror = e.id;
                }
            } else if (e.name.startsWith(DIRECT_PREFIX)) {
                if (foreign) {
                    rejected.add(e + " 非系统归属");
                    continue;
                }
                if (direct < 0) {
                    direct = e.id;
                }
            } else if (e.id != DEFAULT_DISPLAY_ID) {
                // 不认识这个名字。**主屏必须排除** —— parse() 会把 mBaseDisplayInfo
                // 里的主屏也收进屏表，不排除的话下面的结构判据会在每一台车上
                // 命中 display 0，把用户的画面投到主屏上。
                if (foreign) {
                    rejected.add(e + " 非系统归属");
                } else {
                    unknown.add(e);
                }
            }
        }

        if (slot < 0) {
            slot = slotFirst;
        }
        String seen = entries.toString();
        String tail = rejected.isEmpty() ? "" : "，已排除 [" + rejected + "]";

        if (slot >= 0) {
            return new Pick(Path.SLOT, slot, mirror,
                    "通路=槽位（DiLink 5.x）投屏槽=" + slot
                            + (mirror >= 0 ? " 主投影屏=" + mirror : " 主投影屏需用实测常量")
                            + " 可见副屏=" + seen + tail);
        }
        if (direct >= 0) {
            // 3/4 上仪表屏自己就是落点：投上去就看得见，没有"镜像"这一步。
            return new Pick(Path.DIRECT, direct, direct,
                    "通路=直接投仪表屏（DiLink 3/4）仪表屏=" + direct
                            + " 可见副屏=" + seen + tail);
        }

        // 结构判据：已知三族都没命中时，看本机到底有几块**不属于任何已知族**的副屏。
        //
        //   恰好一块 → 这台车只有"主屏 + 一块副屏"这个拓扑，那它就是仪表屏，走 DIRECT。
        //   多于一块 → 拓扑有歧义（可能还有后排屏、其它投影屏），保持未适配，
        //              **绝不从多块里挑一块** —— 挑错就是把用户的画面送到别处。
        //
        // 这条判据只用本机 dumpsys display 的实际结果，不依赖任何外部软件的常量、
        // 也不看版本号，所以对没见过的代次（3.0 / 4.0 / 未来的 6.0）同样成立。
        // 5.x 上候选必然为空（display 2/3/4 都属已知族，第三方残留屏归属 uid 2000 被排除），
        // 且槽位族先命中，故 5.x 行为逐项不变。
        if (unknown.size() == 1) {
            Entry only = unknown.get(0);
            return new Pick(Path.DIRECT, only.id, only.id,
                    "通路=直接投仪表屏（本机唯一一块非已知族副屏：" + only
                            + "）仪表屏=" + only.id + " 可见副屏=" + seen + tail);
        }

        return new Pick(Path.UNSUPPORTED, -1, mirror,
                "两种拓扑都没命中（期望槽位族 " + SLOT_PREFIX + "* 或仪表屏族 " + DIRECT_PREFIX
                        + "*；非已知族副屏 " + unknown.size()
                        + " 块，需恰好 1 块才能按结构判据投屏），可见副屏=" + seen + tail);
    }

    /**
     * daemon 侧结果精化应用侧结论。
     *
     * <p>通道起来后我们能看到**应用侧看不到的屏**（实测本车主投影屏就是这样）。
     * 但精化必须**单调**：只有新表确实给出了可用结果、且与旧结论不同才替换，
     * 免得一次 dump 抖动把已经跑起来的投屏抖没。
     */
    public static Pick refine(Pick current, DisplayTable fromDaemon) {
        if (fromDaemon == null) {
            return current;
        }
        Pick later = fromDaemon.resolve();
        if (later.isActive()) {
            return later;
        }
        return current;
    }
}
