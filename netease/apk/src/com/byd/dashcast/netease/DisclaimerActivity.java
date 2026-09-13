package com.byd.dashcast.netease;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

/**
 * 首次打开的免责声明页。它是**桌面入口**，因此是整个应用的第一屏，
 * 早于任何调试通道动作（车机的「允许 USB 调试吗」弹窗在 {@link GuideActivity} 里才会出现）。
 *
 * 为什么用"新建一个 Activity"而不是在 CastActivity 里加个浮层：
 *   - 免责声明是流程的第一步，不是主界面的一种状态；混在一起会让 CastActivity 承担
 *     "还没同意"这个它不该知道的状态；
 *   - 放在 manifest 的 LAUNCHER 上，桌面图标这条路天然只有一条，不存在"某个入口绕过了声明"；
 *   - 同意之后本页立即 finish 并转主界面，用户看不到它，也不占返回栈。
 *
 * 「不同意」直接退出应用：不提供"不授权也能看主界面"的降级路径 ——
 * 这个应用的全部功能都建立在调试通道上，降级后它什么也做不了，留着只会让人以为坏了。
 */
public final class DisclaimerActivity extends Activity {

    private static final String TAG = "dashcast";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Disclaimer.accepted(this)) {
            // 同意过就完全不显示本页，直接进主界面。
            Log.i(TAG, "免责声明：已同意，直接进主界面");
            goNext();
            return;
        }
        Log.i(TAG, "免责声明：未同意，显示使用前须知");
        setContentView(R.layout.activity_disclaimer);
        findViewById(R.id.btnAgree).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i(TAG, "免责声明：用户点了同意");
                Disclaimer.accept(DisclaimerActivity.this);
                goNext();
            }
        });
        findViewById(R.id.btnDecline).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // finishAffinity：把本应用整个任务关掉，而不是只关这一页
                // （只 finish 的话会退回桌面，但任务还在最近任务里挂着）。
                Log.i(TAG, "免责声明：用户点了不同意，退出应用");
                finishAffinity();
            }
        });
    }

    private void goNext() {
        startActivity(new Intent(this, CastActivity.class));
        finish();
    }
}
