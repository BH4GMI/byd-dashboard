package com.byd.dashcast.netease;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 「一键启动」的目标：在收藏项右侧点亮的那**一个**应用。
 *
 * 只能有一个。存包名 + 启动 Activity + 显示名；包名是稳定键（显示名会随语言和版本变），
 * 启动 Activity 用真正停在仪表屏上的那个，不是 LAUNCHER 入口。
 * 只存本地，不上传。
 *
 * key 仍沿用 quick_* ：这是同一份「快捷目标」的延续，改名只发生在界面文案上。
 */
public final class QuickLaunch {

    private static final String TAG = "dashcast";
    private static final String PREFS = "dashcast";
    private static final String KEY_PACKAGE = "quick_package";
    private static final String KEY_ACTIVITY = "quick_activity";
    private static final String KEY_LABEL = "quick_label";

    /** 一键启动目标：包名 + 启动 Activity + 显示名。 */
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

    public QuickLaunch(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 没有选择过时返回 null。 */
    public Target target() {
        String packageName = prefs.getString(KEY_PACKAGE, "");
        if (packageName == null || packageName.length() == 0) {
            return null;
        }
        String activityName = prefs.getString(KEY_ACTIVITY, "");
        String label = prefs.getString(KEY_LABEL, "");
        return new Target(packageName,
                activityName == null ? "" : activityName,
                label == null ? "" : label);
    }

    public boolean isTarget(String packageName) {
        Target current = target();
        return current != null && current.packageName.equals(packageName);
    }

    /** 设为目标。单选：直接覆盖上一个。 */
    public void set(AppRepo.Entry entry) {
        prefs.edit()
                .putString(KEY_PACKAGE, entry.packageName)
                .putString(KEY_ACTIVITY, entry.activityName)
                .putString(KEY_LABEL, entry.label)
                .apply();
        Log.i(TAG, "一键启动目标已设为 " + entry.packageName);
    }

    /** 取消选择。 */
    public void clear() {
        prefs.edit().remove(KEY_PACKAGE).remove(KEY_ACTIVITY).remove(KEY_LABEL).apply();
        Log.i(TAG, "一键启动目标已清空");
    }
}
