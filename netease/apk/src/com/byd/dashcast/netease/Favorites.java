package com.byd.dashcast.netease;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 收藏的应用。
 *
 * 存包名而不是 label：label 会随语言/版本变，包名稳定；而且投屏本来就是按包名+Activity 走的。
 * 只存本地（SharedPreferences），不上传、不依赖代理——代理只负责枚举全量清单。
 */
public final class Favorites {

    private static final String PREFS = "dashcast";
    private static final String KEY = "favorite_packages";

    private final SharedPreferences prefs;

    public Favorites(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** getStringSet 返回的集合不允许修改，必须拷贝一份再用。 */
    public Set<String> all() {
        return new LinkedHashSet<String>(
                prefs.getStringSet(KEY, Collections.<String>emptySet()));
    }

    public boolean contains(String packageName) {
        return prefs.getStringSet(KEY, Collections.<String>emptySet()).contains(packageName);
    }

    /** 返回 true 表示这次是「加入收藏」。 */
    public boolean toggle(String packageName) {
        Set<String> next = all();
        boolean added = !next.contains(packageName);
        if (added) {
            next.add(packageName);
        } else {
            next.remove(packageName);
        }
        prefs.edit().putStringSet(KEY, next).apply();
        return added;
    }
}
