package com.byd.dashcast;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 关键日志的落盘通道。
 *
 * <h3>为什么需要它（2026-09-24 实车结论）</h3>
 *
 * 车机的日志策略会把第三方应用的 {@code Log.*} 丢掉：首开自动那条链路在车上跑失败时，
 * {@code logcat} 里只剩框架日志，应用自报的"投屏未生效"一个字都看不到 —— 等于没有现场，
 * 修复也就无法验证。
 *
 * <p>这里把链路关键节点追加写到**外部文件目录**：
 * {@code /sdcard/Android/data/<包名>/files/dashcast.log}，用 adb 直接就能取回。
 * 写外部目录而不是内部目录，是因为内部目录 adb 读不到，而这条日志的唯一用途
 * 就是事后取回来对账。
 *
 * <p>体积上限 {@link #MAX_BYTES}，超了就整体重写一次（丢历史、保最近），
 * 不让一条诊断日志在用户车上无限长大。
 *
 * <p>落盘失败**绝不打断链路**：日志是旁路。但也不静默——第一次失败会往
 * logcat 写一条带异常的原因，之后只记一次，避免刷屏。
 */
public final class AppLog {

    private static final String FILE_NAME = "dashcast.log";
    private static final long MAX_BYTES = 256 * 1024L;
    private static final Object LOCK = new Object();

    private static File file;
    private static boolean writeFailedReported;

    private AppLog() {
    }

    /**
     * 绑定落盘位置。在界面初始化阶段调用一次即可（幂等）。
     *
     * <p>外部目录拿不到（未挂载 / 无存储）时不报错：链路照跑，只是没有落盘日志。
     */
    public static void init(Context context) {
        synchronized (LOCK) {
            if (file != null) {
                return;
            }
            File dir = context.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            file = new File(dir, FILE_NAME);
        }
    }

    /** 诊断日志的落盘路径；未初始化或不可用时为 null。 */
    public static String path() {
        synchronized (LOCK) {
            return file == null ? null : file.getAbsolutePath();
        }
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        write("I", tag, message);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        write("W", tag, message);
    }

    private static void write(String level, String tag, String message) {
        File target;
        synchronized (LOCK) {
            target = file;
        }
        if (target == null) {
            return;
        }
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + " " + tag + ": " + message + "\n";
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                if (target.length() > MAX_BYTES) {
                    // 滚一次：只保留最近这一段，避免无限增长。
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                out = new FileOutputStream(target, true);
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                if (!writeFailedReported) {
                    writeFailedReported = true;
                    Log.w(tag, "日志落盘失败（链路不受影响）：" + target, e);
                }
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // 关不上没有可做的补救；数据可能少一行，链路不受影响。
                    }
                }
            }
        }
    }
}
