package com.byd.dashcast.netease;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 首次打开的免责声明是否已被接受。
 *
 * 只存在本机 SharedPreferences（与 {@link Favorites} 同一个 "dashcast" 文件），
 * 不上传、不同步、也没有"远程撤销"这种说法 —— 它和收藏一样是这个安装的本地状态。
 *
 * 放在单独一个类而不是散在各 Activity 里：判断依据只有一处，
 * 将来要改"接受"的语义（比如加版本号，改了声明就要重新同意）只改这里。
 */
public final class Disclaimer {

    private static final String PREFS = "dashcast";

    /**
     * 键名带声明版本号：同意记录是"对某一版声明"的同意，不是一张永久通行证。
     * 声明内容有实质改动时把版本号加一，所有人会被重新问一次；只改错别字就不必动。
     *
     * v1 → v2：免责条款整体重写（补齐损害类型、加入开源免费与许可证说明），
     * 属于实质改动，因此重新征求同意。
     */
    private static final String KEY = "disclaimer_accepted_v2";

    public static boolean accepted(Context context) {
        return prefs(context).getBoolean(KEY, false);
    }

    public static void accept(Context context) {
        prefs(context).edit().putBoolean(KEY, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Disclaimer() {
    }
}
