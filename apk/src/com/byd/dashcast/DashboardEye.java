package com.byd.dashcast;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

/**
 * 「仪表屏的眼睛」：抓仪表屏的一帧并判断当前是哪一页。
 *
 * 为什么需要它：补点是一个盲的坐标点击，它既不知道仪表屏上现在是不是目标应用，
 * 也不知道点完有没有反应。实测踩过两次：一次点在车机导航上（应用其实还没被搬回仪表屏），
 * 一次点了但页面没变——两次都没有任何反馈，脚本照样报"已展开"。
 *
 * 抓帧固定走**主投影屏**（{@code screencap -d 2}，见
 * {@link InjectClient#captureProjectionFrame}），不再是 App 侧镜像：那条路要往
 * uid 2000 里塞代码，本版本已整体移除。
 *
 * <p>**判页永远只判仪表屏**。2026-09-24 实测：跟"目标窗口当前挂在哪块屏"走会在目标被
 * 应用自己拽回主屏时判到主屏上，于是误报"已到歌词播放页"。
 */
public final class DashboardEye {

    private static final String TAG = "dashcast";

    private DashboardEye() {
    }

    /**
     * 抓一帧。失败返回 null。**同步阻塞，必须在后台线程调用。**
     *
     * <p>实测一帧约 0.29~0.31s（1920x720 的 PNG 约 765 KB），所以别在 UI 线程上调。
     * {@code width}/{@code height} 是判页阈值标定所用的尺寸；抓到的帧尺寸不符时
     * 等比缩到它，保证 {@link #classify} 看到的画面比例一致。
     */
    public static Bitmap grab(InjectClient injector, int width, int height) {
        if (!injector.isAttached()) {
            return null;
        }
        try {
            byte[] png = injector.captureProjectionFrame();
            if (png == null) {
                Log.w(TAG, "抓帧失败：screencap 没返回可用数据");
                return null;
            }
            Bitmap raw = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (raw == null) {
                Log.w(TAG, "抓帧失败：PNG 解不出来（" + png.length + " 字节）");
                return null;
            }
            if (raw.getWidth() == width && raw.getHeight() == height) {
                return raw;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(raw, width, height, true);
            if (scaled != raw) {
                raw.recycle();
            }
            return scaled;
        } catch (Throwable t) {
            Log.w(TAG, "抓帧异常", t);
            return null;
        }
    }

    /**
     * 仪表屏上当前是哪一页。
     *
     * 为什么必须是"三分类"而不是"是不是首页"：
     * 旧实现只回答"是不是首页"，调用方把"不是首页"直接当成"到了目标页"。但"不是首页"
     * 至少混了四种画面——目标页、启动白屏、加载中的黑屏、车机自己画的地图——里面三种
     * 都不是目标页。重启车机后立即冷启动时，网易云要好几秒才画出首页，第一次抓帧必然
     * 落在加载画面上，于是脚本**一次都不点就报"已投屏并展开"**，页面停在首页。
     * 所以判据必须是**正面识别目标页**，识别不了就老实说"不知道"。
     */
    public enum Page {
        /** 网易云首页：顶部有导航栏（推荐/发现/高音质/播客/我的/搜索）。 */
        HOME,
        /** 网易云歌词播放页——这就是要到达的目标页。 */
        LYRICS,
        /** 其它一切：启动白屏、加载黑屏、车机地图、别的应用。**不等于失败，但绝不算成功**。 */
        OTHER
    }

    /**
     * 标定（1920x720，实拍帧，2026-09-13）：
     *
     * | 画面 | 导航带亮像素 | 封面彩色像素 |
     * | --- | --- | --- |
     * | 歌词播放页（目标） | 0 | 4380..4793 |
     * | 网易云首页 | 3160 | 2283..3648 |
     * | 启动白屏 | 25500 | 0 |
     * | 车机地图 / 前一页 | 402..413 | 6486 |
     *
     * 单看任一维都分不开（白屏的 25500 会被当成首页、地图的 402 会被当成"非首页=成功"），
     * 两维一起才能把四类分开。
     *
     * **分工要分明**（2026-09-20 修正）：区分"首页还是歌词页"只由**导航带**承担——
     * 那是版式特征，稳健；**封面彩色只用来证明"这一帧真的有内容"**（排除白屏/黑屏）。
     * 早先把 {@code COVER_HOME_MIN} 定成 1000，等于让"封面有多鲜艳"参与版面判断，
     * 而实测同一个首页会随推荐封面不同在 958~3648 之间浮动，于是首页被判成 OTHER、
     * 补点闭环一次都不点。现在它只要求"有颜色"，门槛取一个宽松的小值。
     */
    private static final int BAND_HOME_MIN = 1500;
    private static final int BAND_TARGET_MAX = 50;
    /** 一帧"有彩色内容"的下限：只为把整块白屏（0）和黑屏挡掉。 */
    private static final int COVER_ALIVE_MIN = 200;
    private static final int COVER_TARGET_MIN = 3000;

    /** 判页。frame 为 null（抓帧失败）返回 OTHER——"不知道"永远不能当成"到了"。 */
    public static Page classify(Bitmap frame) {
        if (frame == null) {
            Log.w(TAG, "判页：抓帧失败，按 OTHER 处理");
            return Page.OTHER;
        }
        if (frame.getWidth() < 1400 || frame.getHeight() < 455) {
            Log.w(TAG, "判页：帧尺寸异常 " + frame.getWidth() + "x" + frame.getHeight());
            return Page.OTHER;
        }
        int band = brightInBand(frame);
        int cover = colorfulCover(frame);
        Log.i(TAG, "判页：导航带=" + band + " 封面彩色=" + cover);
        // 首页：有顶部导航带 + 这一帧确实有画面（不是白屏/黑屏）。
        if (band >= BAND_HOME_MIN && cover >= COVER_ALIVE_MIN) {
            return Page.HOME;
        }
        // 歌词页：导航带消失 + 左栏有大块彩色封面。
        if (band <= BAND_TARGET_MAX && cover >= COVER_TARGET_MIN) {
            return Page.LYRICS;
        }
        return Page.OTHER;
    }

    /**
     * 首页顶部导航栏（推荐/发现/高音质/播客/我的/搜索）那一带的亮像素数，落在 y≈28..62、
     * x≈650..1400。播放页/歌词页那一带是空的（实测 0）。
     */
    private static int brightInBand(Bitmap frame) {
        int bright = 0;
        for (int y = 28; y < 62; y++) {
            for (int x = 650; x < 1400; x++) {
                if (luma(frame.getPixel(x, y)) > 110) {
                    bright++;
                }
            }
        }
        return bright;
    }

    /**
     * 歌词播放页左栏那张大封面处的彩色像素数（饱和度高的点）。取样步长 2，
     * 只关心"有没有一块彩色内容"，不关心细节，所以没必要逐像素。
     *
     * 这一维是专门用来排除启动白屏的：白屏整块高亮但**没有颜色**，实测 0；
     * 而任何真画面（封面/地图）都有颜色。
     */
    private static int colorfulCover(Bitmap frame) {
        int colorful = 0;
        for (int y = 100; y < 455; y += 2) {
            for (int x = 290; x < 645; x += 2) {
                int pixel = frame.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max - min > 40 && max > 60) {
                    colorful++;
                }
            }
        }
        return colorful;
    }

    private static int luma(int pixel) {
        return (((pixel >> 16) & 0xFF) * 299
                + ((pixel >> 8) & 0xFF) * 587
                + (pixel & 0xFF) * 114) / 1000;
    }
}
