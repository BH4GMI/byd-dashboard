package com.byd.dashcast;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 「仪表屏的眼睛」：抓仪表屏的一帧并判断当前是哪一页。
 *
 * 为什么需要它：补点是一个盲的坐标点击，它既不知道仪表屏上现在是不是目标应用，
 * 也不知道点完有没有反应。实测踩过两次：一次点在车机导航上（应用其实还没被搬回仪表屏），
 * 一次点了但页面没变——两次都没有任何反馈，脚本照样报"已展开"。
 *
 * 实现上零特权：复用现有镜像通道（{@link InjectClient#startMirror}），只是把输出
 * Surface 换成 ImageReader 的 Surface 而不是预览的 TextureView，然后用
 * {@link ImageReader#acquireLatestImage()} 读像素。代理侧完全不用改。
 */
public final class DashboardEye {

    private static final String TAG = "dashcast";

    /** 抓一帧的总超时：镜像建立 + 等 SurfaceFlinger 出第一帧。 */
    private static final long GRAB_TIMEOUT_MS = 1500;
    private static final long POLL_INTERVAL_MS = 40;

    private DashboardEye() {
    }

    /**
     * 抓一帧。失败返回 null。**同步阻塞，必须在后台线程调用。**
     *
     * 注意：内部会 stopMirror()，所以界面侧的预览镜像会被拆掉；调用方用完必须重建。
     */
    public static Bitmap grab(InjectClient injector, int width, int height) {
        if (!injector.isAttached()) {
            return null;
        }
        ImageReader reader = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            final CountDownLatch started = new CountDownLatch(1);
            final boolean[] ok = new boolean[1];
            injector.startMirror(reader.getSurface(), new InjectClient.MirrorCallback() {
                @Override
                public void onResult(boolean success, String message) {
                    ok[0] = success;
                    started.countDown();
                }
            });
            if (!started.await(GRAB_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !ok[0]) {
                Log.w(TAG, "抓帧失败：镜像没建起来");
                return null;
            }
            long deadline = SystemClock.uptimeMillis() + GRAB_TIMEOUT_MS;
            Image image = null;
            while (image == null && SystemClock.uptimeMillis() < deadline) {
                image = reader.acquireLatestImage();
                if (image == null) {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            }
            if (image == null) {
                Log.w(TAG, "抓帧失败：等不到镜像的第一帧");
                return null;
            }
            try {
                return toBitmap(image, width, height);
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "抓帧异常", t);
            return null;
        } finally {
            injector.stopMirror();
            if (reader != null) {
                reader.close();
            }
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
     * 两维一起才能把四类分开。封面彩色像素那一维同时也是"抓帧是否真的抓到了内容"的自检。
     */
    private static final int BAND_HOME_MIN = 1500;
    private static final int BAND_TARGET_MAX = 50;
    private static final int COVER_HOME_MIN = 1000;
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
        if (band >= BAND_HOME_MIN && cover >= COVER_HOME_MIN) {
            return Page.HOME;
        }
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

    /** ImageReader 的 buffer 每行可能带 padding，必须先按 rowStride 建再裁掉右侧。 */
    private static Bitmap toBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        if (rowPadding <= 0) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        }
        int paddedWidth = width + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }
}
