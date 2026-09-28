package com.byd.dashcast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AMS 的 activity 启动记录（system 日志里的 {@code START u0 {…} from uid N}）。
 *
 * <h3>为什么需要它：守位无法区分"用户叫回来的"和"应用自己跳走的"</h3>
 *
 * 投屏守位（{@link CastGuardService}）的不变量是"目标任务必须留在投屏屏上"。但 AMS 把任务
 * 挪回主屏有**两种完全相反的原因**：
 *
 * <ul>
 *   <li><b>应用自己跳的</b>：应用发起了一次不带 display 的启动（闪屏交接、应用内跳转、
 *       点视频卡都会），AMS 按默认屏解析，整条 root task 被挪回主屏。守位必须把它搬回来。</li>
 *   <li><b>用户自己叫的</b>：用户在桌面上点了这个应用的图标。AMS 记录
 *       {@code launchedFromUid=1000 launchedFromPackage=com.android.launcher3}，
 *       任务被带到用户所在的屏。这时守位把它搬回去，就是**把用户自己的操作撤销掉** ——
 *       实测表现正是"点图标回不到主屏、只闪一下"。</li>
 * </ul>
 *
 * 两种情况的**最终状态完全一样**（任务在主屏、前台），所以只看屏位或只看顶层 Activity 都
 * 分不出来。能分开的只有一件事：**这次启动是谁发起的**。而这个事实 AMS 每条启动都会记一次：
 *
 * <pre>
 * I ActivityTaskManager: START u0 {act=android.intent.action.MAIN
 *     cat=[android.intent.category.LAUNCHER] flg=0x10200000
 *     cmp=com.byd.dashcast/.CastActivity bnds=[816,684][1104,921]} from uid 1000
 * </pre>
 *
 * <p>出处（Android 12 {@code ActivityStarter.execute()}，仅当启动结果成功时打印）：
 * <pre>
 * if (err == ActivityManager.START_SUCCESS) {
 *     Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
 *             + "} from uid " + callingUid);
 * }
 * </pre>
 * 上面那条 {@code cmp=com.byd.dashcast} 的记录就是本机（2026-09-13 04:13、21:48）用户点桌面
 * 图标时留下的原文，{@code bnds} 是图标在屏幕上的矩形 —— 只有从图标点起来才会带。
 *
 * <h3>判据为什么是"桌面图标"这一个形状</h3>
 *
 * 释放投屏是**不可逆的用户可见行为**（通知消失、投屏结束），所以判据取窄不取宽，
 * 要求同时成立（四个条件互相独立，任一不成立都退回"照旧搬回"）：
 *
 * <ol>
 *   <li>{@code act=android.intent.action.MAIN} 且 {@code cat=[…LAUNCHER]}：桌面入口的形态；</li>
 *   <li>{@code flg} 含 {@code FLAG_ACTIVITY_RESET_TASK_IF_NEEDED(0x00200000)}：
 *       启动器点图标才会带，{@code getLaunchIntentForPackage()} 那种"开机自启"不带；</li>
 *   <li>带 {@code bnds=[…]}：图标矩形，只有从图标上点起来才有；</li>
 *   <li>发起者不是 shell(2000)/root(0)/本应用/目标应用自己。</li>
 * </ol>
 *
 * <p>第 4 条不是凑数：应用自己把 {@code getIntent()} 再启动一次时，那条 Intent 上**还带着**
 * 启动器留下的 bounds 和 LAUNCHER 分类，只有"发起 uid 是它自己"能把这种情况排除掉。
 *
 * <h3>为什么解析日志而不是调框架 API</h3>
 *
 * 应用进程（uid 10xxx）能拿到任务信息的路径都被权限挡住了：{@code registerTaskStackListener}
 * 要 {@code MANAGE_ACTIVITY_TASKS}，{@code getTasks} 要 {@code REAL_GET_TASKS}，
 * 而 {@code TaskStackListener} / {@code ActivityManager.registerTaskStackListener} 都是 @hide。
 * 唯一带"发起者 uid"这一列、又能在自己进程里读到的事实，就是 AMS 自己打的这条启动记录；
 * 它由 shell 通道（uid 2000，本机 {@code READ_LOGS: granted=true}）读取。
 *
 * <p>读日志的代价是**通道可能失效**（厂商改日志策略就会读不到），所以它的失效方向必须是
 * 安全的：读不到 ⇒ 判据不成立 ⇒ 行为退回 4.2 的"照旧搬回"，不会误释放投屏。
 * {@link CastGuardService} 会在读不到时把这件事写进 {@code AppLog}，不静默。
 *
 * <h3>本类不依赖任何 Android 类</h3>
 *
 * 纯字符串解析 + 判定，便于在 PC 上用 {@code scripts/verify_activity_startlog.ps1}
 * 拿真实车机日志跑回归（javac + java，不需要设备）。
 */
public final class ActivityStartLog {

    /** adb shell（uid 2000）。它有 READ_LOGS，这条记录就是经它读出来的。 */
    public static final int SHELL_UID = 2000;

    private static final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;

    private static final String ACTION_MAIN = "android.intent.action.MAIN";
    private static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    /**
     * 一条启动记录。**时间戳由日志的 {@code MM-DD HH:MM:SS.mmm} 还原**：
     * 日志里没有年份，还原时以"读取时刻"所在年份为准（见 {@link #parse}）。
     */
    public static final class Entry {
        /** 设备墙钟毫秒。 */
        public final long timeMs;
        /** 发起这次启动的 uid（AMS 打印的 {@code from uid N}）。 */
        public final int callingUid;
        /** 被启动 Activity 所属的包名；解析不出时为空串。 */
        public final String targetPackage;
        /** 被启动组件的全限定名；解析不出时为空串。 */
        public final String component;
        /** Intent 的 action；没有时为空串。 */
        public final String action;
        /** 是否带 {@code android.intent.category.LAUNCHER}（**精确匹配**，不含 LAUNCHER_APP）。 */
        public final boolean launcherCategory;
        /** Intent flags 的原始值。 */
        public final int flags;
        /** 是否带 {@code bnds=[…]}（图标在屏幕上的矩形）。 */
        public final boolean hasBounds;

        Entry(long timeMs, int callingUid, String targetPackage, String component,
                String action, boolean launcherCategory, int flags, boolean hasBounds) {
            this.timeMs = timeMs;
            this.callingUid = callingUid;
            this.targetPackage = targetPackage;
            this.component = component;
            this.action = action;
            this.launcherCategory = launcherCategory;
            this.flags = flags;
            this.hasBounds = hasBounds;
        }

        /** 一行可读摘要，用于落盘日志与 PC 侧回归输出。 */
        public String describe() {
            return "uid=" + callingUid + " cmp=" + component + " act=" + action
                    + " flg=0x" + Integer.toHexString(flags)
                    + (hasBounds ? " bnds=yes" : " bnds=no");
        }
    }

    private ActivityStartLog() {
    }

    /**
     * 一条 {@code threadtime} 格式的启动记录。
     *
     * <pre>09-13 21:48:56.976  1124  4674 I ActivityTaskManager: START u0 {…} from uid 1000</pre>
     *
     * <p>末尾锚定 {@code $}：格式变了就解析不出来（宁可判不出来，也不要基于半条记录下判断）。
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+"
                    + "\\d+\\s+\\d+\\s+([VDIWEF])\\s+ActivityTaskManager:\\s+"
                    + "START u\\d+\\s+\\{(.*)\\}\\s+from uid (\\d+)\\s*$");

    private static final Pattern ACT = Pattern.compile("act=(\\S+)");
    private static final Pattern CAT = Pattern.compile("cat=\\[([^\\]]*)\\]");
    private static final Pattern FLG = Pattern.compile("flg=0x([0-9a-fA-F]+)");
    private static final Pattern CMP = Pattern.compile("cmp=(\\S+)");

    /**
     * 解析一段 logcat 输出。**不认识的行走开**，不抛异常 —— 排障现场永远混着别的输出。
     *
     * @param nowMs 读取时刻（设备墙钟），用来给日志补年份
     */
    public static List<Entry> parse(String logcat, long nowMs) {
        List<Entry> out = new ArrayList<Entry>();
        if (logcat == null || logcat.isEmpty()) {
            return out;
        }
        for (String line : logcat.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            long timeMs = toMillis(nowMs, m.group(1), m.group(2), m.group(3), m.group(4),
                    m.group(5), m.group(6));
            if (timeMs < 0) {
                continue;
            }
            String body = m.group(8);
            int uid;
            try {
                uid = Integer.parseInt(m.group(9));
            } catch (NumberFormatException e) {
                continue;
            }
            Matcher cmp = CMP.matcher(body);
            String component = cmp.find() ? cmp.group(1) : "";
            int slash = component.indexOf('/');
            String pkg = slash > 0 ? component.substring(0, slash) : "";
            Matcher act = ACT.matcher(body);
            String action = act.find() ? act.group(1) : "";
            boolean launcher = false;
            Matcher cat = CAT.matcher(body);
            if (cat.find()) {
                for (String one : cat.group(1).split(",")) {
                    if (CATEGORY_LAUNCHER.equals(one.trim())) {
                        launcher = true;
                        break;
                    }
                }
            }
            int flags = 0;
            Matcher flg = FLG.matcher(body);
            if (flg.find()) {
                try {
                    flags = (int) Long.parseLong(flg.group(1), 16);
                } catch (NumberFormatException ignored) {
                    // flags 读不出来按 0 处理：判据要求必须带 RESET_TASK_IF_NEEDED，缺了就不成立
                }
            }
            out.add(new Entry(timeMs, uid, pkg, component, action, launcher, flags,
                    body.contains("bnds=[")));
        }
        return out;
    }

    /**
     * 日志时间（{@code MM-DD HH:MM:SS.mmm}，**没有年份**）还原成毫秒。
     *
     * <p>按读取时刻的年份补；补出来落在**未来**（说明这段日志跨了月界，实际是上个月）就退一年。
     * 两种还原方式错了都只会让这条记录落到"过期"区间里，不会凭空变成一条新记录 ——
     * 失效方向是安全的。
     *
     * @return 毫秒；解析不出来返回 -1
     */
    private static long toMillis(long nowMs, String mm, String dd, String hh, String mi,
            String ss, String ms) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(nowMs);
            c.set(Calendar.MONTH, Integer.parseInt(mm) - 1);
            c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dd));
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hh));
            c.set(Calendar.MINUTE, Integer.parseInt(mi));
            c.set(Calendar.SECOND, Integer.parseInt(ss));
            c.set(Calendar.MILLISECOND, Integer.parseInt(ms));
            long t = c.getTimeInMillis();
            if (t > nowMs + 60000L) {
                // 跨月界：日志里是上个月的日子，按今年补会补到未来去
                c.add(Calendar.YEAR, -1);
                t = c.getTimeInMillis();
            }
            return t;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 找出"用户点了目标应用桌面图标"那一条记录。
     *
     * @param targetPackage 目标包名
     * @param sinceMs       投屏成立时刻：比它早的记录不算（那是投屏之前的旧手势）
     * @param nowMs         当前墙钟
     * @param ignoredUids   不算"外部发起"的 uid（root / shell / 本应用 / 目标应用自己）
     * @return 命中最新的一条；没有返回 null
     */
    public static Entry desktopIconTap(List<Entry> entries, String targetPackage, long sinceMs,
            long nowMs, int[] ignoredUids) {
        if (entries == null || targetPackage == null || targetPackage.isEmpty()) {
            return null;
        }
        Entry newest = null;
        for (Entry e : entries) {
            if (!isDesktopIconTap(e, targetPackage, sinceMs, nowMs, ignoredUids)) {
                continue;
            }
            if (newest == null || e.timeMs > newest.timeMs) {
                newest = e;
            }
        }
        return newest;
    }

    private static boolean isDesktopIconTap(Entry e, String targetPackage, long sinceMs,
            long nowMs, int[] ignoredUids) {
        if (!targetPackage.equals(e.targetPackage)) {
            return false;
        }
        if (!ACTION_MAIN.equals(e.action) || !e.launcherCategory) {
            return false;
        }
        if ((e.flags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0 || !e.hasBounds) {
            return false;
        }
        if (isIgnored(e.callingUid, ignoredUids)) {
            return false;
        }
        // 窗口留 1s 余量：投屏成立与本次巡检之间设备时钟只会往前走，
        // 而"早于投屏成立"必须排除，否则用户投屏前的那次点图标会立刻把新投屏释放掉。
        return e.timeMs >= sinceMs - 1000L && e.timeMs <= nowMs + 60000L;
    }

    private static boolean isIgnored(int uid, int[] ignoredUids) {
        if (ignoredUids == null) {
            return false;
        }
        for (int one : ignoredUids) {
            if (one == uid) {
                return true;
            }
        }
        return false;
    }
}
