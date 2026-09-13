package com.byd.dashcast;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取当前登录的 DiLink 账号。
 *
 * 为什么需要它：车机的 Android 层只有一个用户（UserInfo{0:机主}），两个人开同一辆车
 * 在 Android 上是同一个人，区别只在 DiLink 账号。"识别当前登录的是谁"只能走账号层。
 *
 * 通道：com.byd.diLinkAccount（BydPersonalCenter.apk，uid 10036，**普通应用 uid**）
 * 暴露的 ContentProvider，authority = com.byd.accountProvider，清单里
 * exported=true 且**没有 android:permission**（work/pc-manifest.txt:568-572）。
 * 实测从 shell(uid 2000) 可直接 query 出：
 *
 *   account_msg      → isLogin / isCarOwner / nickName / photoUrl / hasUnreadMsg
 *   account_big_data → userId（形如 V1_790f0fc27588bda6d910e3c9053b7d4d，账号稳定 ID）
 *
 * 同一份数据在 BydPersonalCenter.apk 的 dex 字符串池里也出现过，不是猜的。
 *
 * 注意两条边界：
 *   1. 这不是公开 API。车机 OTA 改动 authority 或加上权限就会失效，所以任何读取失败
 *      都必须降级成"识别不到"，绝不能崩。
 *   2. nickName / userId / photoUrl 是个人数据。这里只在本机内存和状态栏使用，
 *      不落盘、不写日志全文（日志只记字段名与长度）。
 */
public final class CarAccount {

    private static final String TAG = "dashcast";
    private static final String AUTHORITY = "com.byd.accountProvider";

    private static final String PATH_MSG = "account_msg";
    private static final String PATH_BIG_DATA = "account_big_data";

    public static final String KEY_LOGIN = "isLogin";
    public static final String KEY_OWNER = "isCarOwner";
    public static final String KEY_NICK = "nickName";
    public static final String KEY_PHOTO = "photoUrl";
    public static final String KEY_USER_ID = "userId";

    /** 一次读取的结果。error 为 null 表示两条 path 都读到了。 */
    public static final class Info {

        private final Map<String, String> values;
        public final String error;

        Info(Map<String, String> values, String error) {
            this.values = values;
            this.error = error;
        }

        public boolean loggedIn() {
            return isTrue(values.get(KEY_LOGIN));
        }

        public boolean carOwner() {
            return isTrue(values.get(KEY_OWNER));
        }

        /** 昵称即"是谁"；读不到返回空串。 */
        public String nickName() {
            return safe(values.get(KEY_NICK));
        }

        /** 稳定的账号 ID，用来做"这台车当前是谁"的判据与按人存配置的键。 */
        public String userId() {
            return safe(values.get(KEY_USER_ID));
        }

        /**
         * 判断"是不是同一个人"用的键。
         *
         * 实测本机（2026-02，DiLink5）：**应用 uid 读不到 userId**——
         * account_big_data 的 userId 对 uid 10096 返回空串，而 shell（uid 2000）
         * 能读到 33 字符的值（V1_…）。同一个 Provider 按调用方 uid 做了区别对待。
         * 所以这里退而用 photoUrl（头像地址里带每用户哈希），再退用昵称。
         *
         * 取舍：后两者会随用户改头像/改昵称而变，那时绑定自然失效、需要重新打开一次开关。
         * 宁可偶尔要重设，也不要绑到一个读到空值的 ID 上、让开关永远不触发还查不出原因。
         */
        public String identity() {
            if (userId().length() > 0) {
                return userId();
            }
            String photo = safe(values.get(KEY_PHOTO));
            if (photo.length() > 0) {
                return photo;
            }
            return nickName();
        }

        /** identity() 用的是哪个字段，只用于日志，不落盘。 */
        public String identitySource() {
            if (userId().length() > 0) {
                return "userId";
            }
            if (safe(values.get(KEY_PHOTO)).length() > 0) {
                return "photoUrl";
            }
            if (nickName().length() > 0) {
                return "nickName";
            }
            return "无";
        }

        /** 供设置界面显示：昵称(车主/授权用户)。识别不到时返回 null。 */
        public String display() {
            String nick = nickName();
            if (nick.length() == 0 && userId().length() == 0) {
                return null;
            }
            String who = nick.length() == 0 ? userId() : nick;
            return who + (carOwner() ? "（车主）" : "（授权用户）");
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }

        private static boolean isTrue(String value) {
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        }
    }

    /**
     * 同步读取。**只在需要同步判定时调用**（首开自动），其它场合请放后台线程——
     * 这是跨进程的 ContentResolver.query，压在 UI 线程上不合适。
     */
    public static Info read(Context context) {
        Map<String, String> all = new LinkedHashMap<String, String>();
        String error = null;
        String[] paths = {PATH_MSG, PATH_BIG_DATA};
        for (String path : paths) {
            try {
                all.putAll(readPath(context, path));
            } catch (Throwable t) {
                Log.w(TAG, "读取 " + path + " 失败", t);
                if (error == null) {
                    error = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage());
                }
            }
        }
        if (all.isEmpty() && error == null) {
            error = "账号服务无返回";
        }
        // 只记录字段名和值的长度，不记录值本身——昵称和账号 ID 是个人数据。
        StringBuilder shape = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            shape.append(e.getKey()).append('=')
                    .append(e.getValue() == null ? -1 : e.getValue().length()).append(' ');
        }
        Log.i(TAG, "车机账号读取完成：" + shape + " error=" + error);
        return new Info(all, error);
    }

    private static Map<String, String> readPath(Context context, String path) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    Uri.parse("content://" + AUTHORITY + "/" + path), null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return out;
            }
            // 按列名取，不按序号——车机换版本加列不会把取值错位。
            for (String name : cursor.getColumnNames()) {
                int index = cursor.getColumnIndex(name);
                if (index >= 0) {
                    out.put(name, cursor.getString(index));
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    private CarAccount() {
    }
}
