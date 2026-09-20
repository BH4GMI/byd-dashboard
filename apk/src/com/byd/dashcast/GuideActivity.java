package com.byd.dashcast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.byd.dashcast.adb.AdbBootstrap;
import com.byd.dashcast.adb.AdbClient;

/**
 * ADB 授权引导页。**只会在还没授权时出现一次**。
 *
 * 背景：投屏必须由 uid 2000 执行（普通应用 setLaunchDisplayId(2) 会被 AMS 拒绝，
 * 输入注入需要 INJECT_EVENTS），而拿到 uid 2000 的唯一合法途径是让 adbd 认我们。
 * 首次连接时车机会弹「允许 USB 调试吗」，用户点一次允许，我们的公钥就写进
 * /data/misc/adb/adb_keys —— 此后重启、重装都不丢，页面再也不会出现。
 *
 * 因此本页同时是"快速通道"：启动时先做一次极短的连通检查，
 *   - 已授权且代理已起  -> 立刻转到目标界面，肉眼看不到本页
 *   - 未授权 / 连不上    -> 才把引导内容显示出来
 *
 * 触发条件是**能力探测**而不是"首次运行"标记：万一授权真的丢了（车机恢复出厂、
 * 用户手动撤销），引导会自动回来，不会出现"标记说完成、实际连不上"的哑状态。
 */
public final class GuideActivity extends Activity {

    /** 快速通道：已就绪时跳到这里。 */
    private static final String EXTRA_NEXT = "com.byd.dashcast.extra.NEXT_ACTIVITY";

    private static final long FAST_PROBE_MS = 3000L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView detail;
    private Button authorize;
    private Button retry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        probeFast();
    }

    // ---- 快速通道 ----------------------------------------------------------

    private void probeFast() {
        setStatus("正在检查车机授权…", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbBootstrap.Result result =
                        AdbBootstrap.provision(GuideActivity.this, FAST_PROBE_MS, false);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result.state == AdbClient.State.READY && result.isReady()) {
                            // 已就绪：不打扰用户，直接进目标界面。
                            goNext();
                            return;
                        }
                        showGuide(result);
                    }
                });
            }
        }, "dashcast-probe").start();
    }

    private void goNext() {
        Intent intent = getIntent();
        String next = intent == null ? null : intent.getStringExtra(EXTRA_NEXT);
        Intent target;
        if (next != null) {
            target = new Intent().setClassName(this, next);
        } else {
            target = new Intent(this, CastActivity.class);
        }
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(target);
        finish();
    }

    // ---- 引导内容 ----------------------------------------------------------

    private void showGuide(AdbBootstrap.Result result) {
        switch (result.state) {
            case NEED_AUTHORIZATION:
                setStatus("需要一次性授权", AdbBootstrap.describe(result.state)
                        + "\n\n本应用已生成自己的调试密钥（不会复用任何第三方凭据）。"
                        + "点下面的按钮后，车机会弹出授权对话框，请点「允许」。"
                        + "授权只做这一次，之后每次打开都直接投屏。");
                authorize.setEnabled(true);
                break;
            case UNREACHABLE:
                setStatus("车机无线 ADB 未开启", AdbBootstrap.describe(result.state)
                        + "\n\n打开路径：车机上的「开发者工具 / 调试工具」里找到无线 ADB 开关并打开，"
                        + "然后回到这里点「重试」。");
                authorize.setEnabled(true);
                break;
            default:
                setStatus("连接失败", AdbBootstrap.describe(result.state)
                        + "\n\n请确认车机无线 ADB 已开启后点「重试」。");
                authorize.setEnabled(true);
                break;
        }
    }

    private void startAuthorize() {
        authorize.setEnabled(false);
        retry.setEnabled(false);
        setStatus("正在请求授权…",
                "请在这台车机的屏幕上点「允许 USB 调试」。这个对话框由系统弹出，"
                        + "本应用无法代按。授权完成后本页会自动继续。");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbBootstrap.Result result =
                        AdbBootstrap.provision(GuideActivity.this,
                                AdbBootstrap.TIMEOUT_GUIDED_MS, true);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        authorize.setEnabled(true);
                        retry.setEnabled(true);
                        if (result.state == AdbClient.State.READY && result.isReady()) {
                            setStatus("授权完成", result.message + "\n\n以后不再需要这一步。");
                            goNext();
                        } else if (result.state == AdbClient.State.READY) {
                            setStatus("已授权，但代理未起来", result.message);
                        } else {
                            showGuide(result);
                        }
                    }
                });
            }
        }, "dashcast-authorize").start();
    }

    // ---- 界面（用代码搭，避免为一个页面引入布局资源） ----------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("投屏授权");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#FFD54F"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        root.addView(status, sp);

        detail = new TextView(this);
        detail.setTextColor(Color.parseColor("#B0BEC5"));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        detail.setMovementMethod(new ScrollingMovementMethod());
        root.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeFast();
            }
        });
        buttons.addView(retry);

        authorize = new Button(this);
        authorize.setText("开始授权");
        authorize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAuthorize();
            }
        });
        buttons.addView(authorize);

        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void setStatus(String headline, String body) {
        if (status != null) {
            status.setText(headline);
        }
        if (detail != null) {
            detail.setText(body);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
