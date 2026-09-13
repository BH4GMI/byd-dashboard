package com.byd.dashcast.netease.adb;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把 uid-2000 代理从"需要电脑 adb push + adb shell"变成"App 自己搞定"。
 *
 * 之前代理 jar 靠 PC 上的 agent/run.ps1 推送并启动，所以每次重启车机都得插一次电脑。
 * 现在 jar 作为 asset 随 APK 分发，App 通过已经建立的 shell 通道把它写到
 * /data/local/tmp（那个目录只有 shell 能写，而这条通道本身就是 shell），再拉起进程。
 *
 * 全程不需要 root、不需要 PC、不需要额外权限。
 */
public final class AgentLauncher {

    private static final String TAG = "DashCastAgent";

    /** 代理 jar 在车机上的落地路径。/data/local/tmp 全车机只有 shell 和 root 能写。 */
    public static final String JAR_PATH = "/data/local/tmp/dashcast-agent.jar";

    /** app_process 的 --nice-name，也是 pidof 匹配的名字。 */
    public static final String PROC_NAME = "dashcast-agent";

    /** 代理启动输出落点，失败时读回来当诊断依据。 */
    private static final String LOG_PATH = "/data/local/tmp/dashcast-agent.log";

    /** 脱离 adbd 会话用的原生工具；用绝对路径，不依赖 PATH。 */
    private static final String SETSID_PATH = "/system/bin/setsid";

    /** 代理入口类（agent/src/com/byd/dashcast/agent/Agent.java）。 */
    private static final String AGENT_CLASS = "com.byd.dashcast.agent.Agent";

    /** APK 内打包的 jar 名，由 build.ps1 从 agent/ 拷进 assets/。 */
    private static final String ASSET_NAME = "dashcast-agent.jar";

    /**
     * 停掉正在运行的代理。用 pidof 按进程名取，不用 pgrep/pkill -f：
     * 包裹用的 `sh -c '<整行>'` 自己的 cmdline 里带着同样的文本，-f 会连它一起杀。
     */
    private static final String KILL_ALL =
            "for p in $(pidof " + PROC_NAME + "); do kill $p; done";

    private AgentLauncher() {
    }

    public static final class Status {
        public final boolean running;
        public final String detail;

        Status(boolean running, String detail) {
            this.running = running;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return detail;
        }
    }

    /**
     * 确保代理在跑：**版本一致且已在跑**才直接返回；否则按需换掉 jar 再拉起，并轮询确认。
     *
     * 为什么要先比摘要再认 pid：App 与代理的 Binder 契约必须成对升级，装在 APK 里的 jar
     * 就是契约的另一半。只要"有进程在跑"就算数，车机上就会一直留着上一个版本的代理，
     * 新的契约永远不生效。所以在本机已有的代理与本次构建不一致时，先停掉它。
     *
     * 拉起后一定要回读 pidof，不能假定成功——后台进程被 SELinux 或路径问题挡掉时
     * 命令本身仍然返回 0，只有回读才能发现。
     */
    public static Status ensureRunning(Context context, AdbClient adb) throws IOException {
        byte[] jar = readAsset(context);
        String localDigest = AdbClient.sha256Hex(jar);
        String remoteDigest = readRemoteDigest(adb);
        boolean jarUpToDate = localDigest.equals(remoteDigest);

        String pid = adb.shell("pidof " + PROC_NAME, 5000L).trim();
        if (!pid.isEmpty() && jarUpToDate) {
            return new Status(true, "代理已在运行 pid=" + firstLine(pid));
        }
        if (!pid.isEmpty()) {
            Log.i(TAG, "车机上运行的代理与本次构建不一致，先停掉它 pid=" + firstLine(pid));
            adb.shell(KILL_ALL, 5000L);
            for (int i = 0; i < 10; i++) {
                sleep(300L);
                if (adb.shell("pidof " + PROC_NAME, 5000L).trim().isEmpty()) {
                    break;
                }
            }
        }

        if (!jarUpToDate) {
            Log.i(TAG, "上传代理 jar：" + jar.length + " 字节（远端摘要「" + remoteDigest + "」）");
            try {
                // 远端内容的核对归 AdbClient 管：writeRemoteFile 返回即代表长度与 sha256
                // 都已通过，不通过会抛错。这里只负责判断"要不要传"。
                adb.writeRemoteFile(JAR_PATH, jar, 20000L);
            } catch (IOException e) {
                return new Status(false, "上传代理 jar 失败：" + e.getMessage());
            }
        }

        // 启动分两步，各自解决一个真问题：
        //
        //  1. `echo … > LOG` 先落一行"shell 确实跑到了这里"的凭据。adbd 的 `shell:`
        //     服务是非交互的：命令里的重定向由 shell 建立、进程却在同一会话里，
        //     一旦拉起失败，日志要么空白要么只有半行——分不清"命令没送到"还是
        //     "送到了但 app_process 没起来"。先写凭据，两种失败就能区分。
        //  2. `setsid` 而不是 `nohup`：nohup 只挡 SIGHUP，而 adbd 在 shell 退出后
        //     回收的是**整个会话**。setsid 让代理另开 session 与进程组、脱离控制
        //     终端，会话回收就再也不会连带杀掉它。这是内核提供的原生脱离机制。
        //
        // 输出不丢进 /dev/null：拉起失败时必须能读回原因，否则只剩"没起来"三个字。
        String launch = "echo dashcast-agent-launch uid=$(id -u) setsid=" + SETSID_PATH
                + " > " + LOG_PATH
                + "; CLASSPATH=" + JAR_PATH
                + " " + SETSID_PATH + " /system/bin/app_process /system/bin --nice-name=" + PROC_NAME
                + " " + AGENT_CLASS
                + " < /dev/null >> " + LOG_PATH + " 2>&1 &";

        // 上传和启动都在**同一条**连接上，不另开连接：AdbClient.shell() 已经按 shell v2
        // 的真实帧格式（帧头 5 字节、一帧可横跨两条 WRTE）分帧，同一条连接上连发多条
        // 命令不会错位。少一条连接，也少一个"为什么要这样"的解释负担。
        adb.shell(launch, 5000L);

        String seen = null;
        for (int i = 0; i < 20; i++) {
            sleep(500L);
            seen = adb.shell("pidof " + PROC_NAME, 5000L).trim();
            if (!seen.isEmpty()) {
                return new Status(true, "代理已启动 pid=" + firstLine(seen));
            }
        }

        String reason = adb.shell("cat " + LOG_PATH + " 2>&1 | tail -n 20", 8000L).trim();
        if (reason.isEmpty()) {
            reason = "日志为空——命令没能送达远端 shell";
        } else if (reason.startsWith("dashcast-agent-launch")) {
            reason = "shell 已收到命令，但 app_process 没有留下输出：\n" + reason;
        }
        return new Status(false, "拉起代理后没有回读到进程。启动输出：\n" + reason);
    }

    /**
     * 远端 jar 的 sha256；读不到或不是合法摘要时返回 ""（等价于"需要重传"）。
     *
     * 原来这里比的是**字节数**。远端出现过"长度正好、内容全是 0"的 jar：长度判据判定
     * "已是最新"，每次都跳过上传，app_process 于是永远
     * `ClassNotFoundException: com.byd.dashcast.agent.Agent`。判据必须是内容。
     */
    private static String readRemoteDigest(AdbClient adb) {
        try {
            String out = adb.shell("sha256sum " + JAR_PATH + " 2>/dev/null", 8000L).trim();
            int sp = out.indexOf(' ');
            String digest = (sp < 0 ? out : out.substring(0, sp)).trim();
            // sha256sum 出错时会把错误文本打到 stdout，只接受 64 位十六进制。
            return digest.matches("[0-9a-fA-F]{64}")
                    ? digest.toLowerCase(java.util.Locale.US) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] readAsset(Context context) throws IOException {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_NAME);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
