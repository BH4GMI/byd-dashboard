package com.byd.dashcast.netease;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;

/**
 * 「一键投屏」目标与「首开自动」开关的持久化与判定。
 *
 * 两件事都围绕同一个「快捷目标」（包名 + 启动 Activity + 显示名）展开：
 *   - 一键：底部按钮 / 列表里的「一键」立即执行 = 投到仪表屏 + 补点 + 看门；
 *   - 首开自动：开关打开且当前登录账号就是当初记下的那个账号时，
 *     本次开机第一次打开本程序不显示界面，直接后台执行同一个脚本。
 *
 * 目标存包名不存 label：label 随语言和版本变，包名稳定。
 * 账号存 userId 不存昵称：昵称用户随时可改，userId 是稳定 ID。
 */
public final class AutoCast {

    private static final String TAG = "dashcast";
    private static final String PREFS = "dashcast";

    private static final String KEY_ENABLED = "auto_enabled";
    private static final String KEY_USER_ID = "auto_user_id";
    private static final String KEY_USER_LABEL = "auto_user_label";
    private static final String KEY_BOOT = "auto_last_boot";

    /** 两次 bootMarker 相差小于这个值就算同一次开机，容忍时钟被校时。 */
    private static final long BOOT_SAME_TOLERANCE_MS = 60000L;

    public static final class Target {

        public final String packageName;
        public final String activityName;
        public final String label;

        Target(String packageName, String activityName, String label) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
        }
    }

    private final SharedPreferences prefs;
    private final Context context;

    public AutoCast(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 一键目标 ----------------------------------------------------------

    /**
     * 一键目标就是网易云这一个，不做成"每个应用都能设一键"。
     * 它放在资源里（res/values/quick_taps.xml）而不是写死在逻辑里：换应用改一行。
     */
    public Target target() {
        return new Target(
                context.getString(R.string.quick_package),
                context.getString(R.string.quick_activity),
                context.getString(R.string.quick_label));
    }

    // ---- 首开自动开关 ------------------------------------------------------

    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public String userId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    public String userLabel() {
        return prefs.getString(KEY_USER_LABEL, "");
    }

    /**
     * 打开开关：把"当前这个用户"记下来，之后只有这个人首次开机会触发。
     *
     * @return true 表示绑定成功。失败必须让调用方知道——静默失败的开关比没有开关更糟：
     *         用户看到开关是开的，却永远不触发，还查不出原因。
     */
    public boolean enable(CarAccount.Info account) {
        if (account == null || account.error != null || !account.loggedIn()) {
            Log.i(TAG, "首开自动绑定失败：账号不可用");
            return false;
        }
        String id = account.identity();
        // 身份键是唯一的绑定依据。拿不到就不能绑——实测本机从应用 uid 读
        // account_big_data 的 userId 是空的，这时必须明说，不能假装绑上了。
        if (id.length() == 0) {
            Log.w(TAG, "首开自动绑定失败：读不到任何可用的身份字段", null);
            return false;
        }
        String label = account.display();
        Log.i(TAG, "首开自动绑定：键来源=" + account.identitySource()
                + " 键长度=" + id.length()
                + " 昵称长度=" + account.nickName().length() + " 车主=" + account.carOwner());
        prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_USER_ID, id)
                .putString(KEY_USER_LABEL, label == null ? "" : label)
                // 重新绑定 = 重新武装：清掉"本次开机已执行"。
                // 不清的话，用户在本次开机内没有任何办法再试一次首开自动，只能重启车机。
                .remove(KEY_BOOT)
                .apply();
        return true;
    }

    /**
     * 回滚"本次开机已执行"。首开自动失败时必须调——失败不该吃掉"本次开机仅一次"的机会，
     * 否则用户要重启才能再试，而且他根本看不出发生了什么。
     */
    public void unmarkRan() {
        prefs.edit().remove(KEY_BOOT).apply();
    }

    public void disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        Log.i(TAG, "首开自动已关闭");
    }

    // ---- 判定 --------------------------------------------------------------

    /**
     * 是否该在本次开机首次打开时执行脚本。四个条件缺一不可：
     * 开关开着、账号读得到且已登录、账号就是记下的那个、本次开机还没执行过。
     */
    public boolean shouldRun(CarAccount.Info account) {
        if (!enabled()) {
            Log.i(TAG, "首开自动跳过：开关未开");
            return false;
        }
        if (account == null || account.error != null || !account.loggedIn()) {
            Log.i(TAG, "首开自动跳过：账号不可用");
            return false;
        }
        String want = userId();
        if (want.length() == 0 || !want.equals(account.identity())) {
            Log.i(TAG, "首开自动跳过：当前账号与绑定账号不一致（键来源="
                    + account.identitySource() + "）");
            return false;
        }
        long now = bootMarker();
        long last = prefs.getLong(KEY_BOOT, Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && Math.abs(now - last) < BOOT_SAME_TOLERANCE_MS) {
            Log.i(TAG, "首开自动跳过：本次开机已经执行过");
            return false;
        }
        return true;
    }

    /** 记下"本次开机已执行"，保证再次打开是正常进入界面。 */
    public void markRan() {
        prefs.edit().putLong(KEY_BOOT, bootMarker()).apply();
    }

    /**
     * 本次开机的标识 = 开机时刻（墙上时间 - 开机以来毫秒）。
     * 同一次开机内稳定，重启后必然改变，不需要任何权限。
     */
    public static long bootMarker() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    // ---- 补点落点 ----------------------------------------------------------

    /**
     * 该包的补点落点，没有就返回 null（表示只投屏、不点）。
     * 表在 res/values/quick_taps.xml，每条都是实测值，不是代码里的魔法数。
     */
    public static float[] tapFor(Context context, String packageName) {
        if (packageName == null) {
            return null;
        }
        Resources resources = context.getResources();
        for (String row : resources.getStringArray(R.array.quick_taps)) {
            String[] parts = row.split(":");
            if (parts.length != 3 || !parts[0].equals(packageName)) {
                continue;
            }
            try {
                return new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
            } catch (NumberFormatException e) {
                Log.w(TAG, "落点表格式错误：" + row, e);
            }
        }
        return null;
    }
}
