package com.byd.dashcast.agent;

/** 极简 stdout 日志门面，与主程序同构。 */
public final class L {

    private static final String TAG = "dashcast-agent";

    public static void i(String msg) {
        System.out.println(TAG + ": " + msg);
        System.out.flush();
    }

    public static void e(String msg, Throwable t) {
        System.out.println(TAG + ": ERROR " + msg + ": " + t);
        if (t != null) {
            t.printStackTrace(System.err);
        }
        System.out.flush();
    }

    private L() {
    }
}
