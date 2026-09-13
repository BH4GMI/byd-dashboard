package com.byd.dashcast;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.byd.dashcast.adb.AdbBootstrap;
import com.byd.dashcast.adb.AdbClient;

/**
 * ADB 授权引导页。**只会在还没授权时出现一次**。
 *
 * 背景：投屏必须由 uid 2000 执行（普通应用 setLaunchDisplayId(2) 会被 AMS 拒绝，
 * 输入注入需要 INJECT_EVENTS），而拿到 uid 2000 的唯一合法途径是让 adbd 认本程序。
 * 首次连接时车机会弹「允许 USB 调试吗」，用户点一次允许，本程序的公钥就写进
 * /data/misc/adb/adb_keys —— 此后重启、重装都不丢，页面再也不会出现。
 *
 * 因此本页同时是"快速通道"：启动时先做一次极短的连通检查，
 *   - 已授权且代理已起  -> 立刻转到目标界面，肉眼看不到本页
 *   - 未授权 / 连不上    -> 才把引导内容显示出来
 *
 * 触发条件是**能力探测**而不是"首次运行"标记：万一授权真的丢了（车机恢复出厂、
 * 用户手动撤销），引导会自动回来，不会出现"标记说完成、实际连不上"的哑状态。
 *
 * 界面版式见 `res/layout/activity_guide.xml`（对应设计预览页的 S5）。
 * 全页只有四处会随状态变：标题、说明、右下角圆点与文案；三步步进条是静态的。
 */
public final class GuideActivity extends Activity {

    /** 快速通道：已就绪时跳到这里。 */
    private static final String EXTRA_NEXT = "com.byd.dashcast.extra.NEXT_ACTIVITY";

    private static final long FAST_PROBE_MS = 3000L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView title;
    private TextView detail;
    private TextView status;
    private View dot;
    private Button authorize;
    private Button retry;

    /** 正在跑探测或授权。用来忽略重复点击，而不是靠 setEnabled 之后没有视觉反馈的按钮。 */
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        bindUi();
        probeFast();
    }

    private void bindUi() {
        title = (TextView) findViewById(R.id.guideTitle);
        detail = (TextView) findViewById(R.id.guideDetail);
        status = (TextView) findViewById(R.id.guideStatus);
        dot = findViewById(R.id.guideDot);
        authorize = (Button) findViewById(R.id.btnAuthorize);
        retry = (Button) findViewById(R.id.btnRetry);
        authorize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startAuthorize();
            }
        });
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                probeFast();
            }
        });
    }

    /**
     * 一次改齐四处状态。分四处各改一次迟早会出现"标题说失败、圆点还是绿的"，
     * 所以标题、说明、圆点、右下角文案只允许从这里出去。
     *
     * body 为 null 时说明位只显示常驻注意事项（回环地址 + 必须勾选「一律允许」）。
     */
    private void show(int dotColorRes, int titleRes, String body, int statusRes, boolean working) {
        busy = working;
        dot.setBackgroundTintList(ColorStateList.valueOf(getColor(dotColorRes)));
        title.setText(titleRes);
        detail.setText(body == null || body.length() == 0
                ? getString(R.string.guide_note)
                : body + "\n\n" + getString(R.string.guide_note));
        status.setText(statusRes);
        // 按钮置灰必须看得出来：自绘背景没有 disabled 态，只 setEnabled 会变成"点了没反应"。
        float alpha = working ? 0.5f : 1.0f;
        authorize.setEnabled(!working);
        retry.setEnabled(!working);
        authorize.setAlpha(alpha);
        retry.setAlpha(alpha);
    }

    // ---- 快速通道 ----------------------------------------------------------

    private void probeFast() {
        if (busy) {
            return;
        }
        show(R.color.warn, R.string.guide_checking, null, R.string.guide_st_checking, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbBootstrap.Result result =
                        AdbBootstrap.provision(GuideActivity.this, FAST_PROBE_MS, false);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result.state == AdbClient.State.READY && result.agentRunning) {
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
                show(R.color.warn, R.string.guide_state_auth_title,
                        getString(R.string.guide_state_auth_body),
                        R.string.guide_st_waiting, false);
                break;
            case UNREACHABLE:
                show(R.color.danger, R.string.guide_state_adb_title,
                        getString(R.string.guide_state_adb_body),
                        R.string.guide_st_offline, false);
                break;
            case READY:
                // 已经拿到 shell 却走到这里，说明是「连上了但代理没起来」这类局部失败。
                // 按 default 报「连接失败」会把人带到完全错误的排查方向（去查无线 ADB 开关），
                // 而真正该看的是 result.message 里的代理启动输出。
                show(R.color.warn, R.string.guide_state_agent_title, result.message,
                        R.string.guide_st_connected, false);
                break;
            default:
                show(R.color.danger, R.string.guide_state_fail_title,
                        getString(R.string.guide_state_fail_body) + "\n\n" + result.message,
                        R.string.guide_st_offline, false);
                break;
        }
    }

    private void startAuthorize() {
        if (busy) {
            return;
        }
        // 授权是不可逆的用户可见动作（车机会弹系统框），没看过免责声明就绝不触发它。
        // 正常流程走不到这条分支——桌面入口本身就是声明页；只有被 am start 直接拉起来的
        // 授权页才会落到这里，那时把用户送回第一步，而不是替他点掉授权框。
        if (!Disclaimer.accepted(this)) {
            startActivity(new Intent(this, DisclaimerActivity.class));
            finish();
            return;
        }
        show(R.color.warn, R.string.guide_state_authing_title,
                getString(R.string.guide_state_authing_body),
                R.string.guide_st_waiting, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbBootstrap.Result result =
                        AdbBootstrap.provision(GuideActivity.this,
                                AdbBootstrap.TIMEOUT_GUIDED_MS, true);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result.state == AdbClient.State.READY && result.agentRunning) {
                            show(R.color.ok, R.string.guide_state_ok_title,
                                    result.message + "\n" + getString(R.string.guide_state_ok_body),
                                    R.string.guide_st_connected, true);
                            goNext();
                        } else if (result.state == AdbClient.State.READY) {
                            show(R.color.warn, R.string.guide_state_agent_title,
                                    result.message, R.string.guide_st_connected, false);
                        } else {
                            showGuide(result);
                        }
                    }
                });
            }
        }, "dashcast-authorize").start();
    }
}
