package com.byd.dashcast;

/**
 * 可投屏应用条目。
 *
 * 清单本身由 uid-2000 代理枚举后经 Binder 下发（见 InjectClient.listApps），
 * 不在 App 进程里查：Android 11 起的包可见性过滤按 uid 生效，App 侧只能看到
 * 自己能"看见"的一小撮包，而 uid 2000 不受该过滤约束。
 */
public final class AppRepo {

    public static final class Entry {

        public final String label;
        public final String packageName;
        public final String activityName;

        public Entry(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private AppRepo() {
    }
}
