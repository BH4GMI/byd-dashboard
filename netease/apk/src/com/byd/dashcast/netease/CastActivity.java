package com.byd.dashcast.netease;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.dashcast.netease.adb.AdbBootstrap;
import com.byd.dashcast.netease.adb.AdbClient;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 【网易云版分支 dashcast-netease】
 *
 * 与母工程（main 分支）的差异**全部**在 `netease/apk/fork/` 里，共四处：
 *   1. 本段类注释；
 *   2. onCreate：打开即投屏，永不建界面（`CastActivity.onCreate.txt`）；
 *   3. 母工程删掉的那套自动投屏编排被原样搬回，外加三处判空守卫
 *      （`CastActivity.overrides.txt`）；
 *   4. `InjectClient.tap(...)` 补回来（`InjectClient.tap.txt`）——
 *      tapUntilTargetPage 是实测闭环、方法体逐字保留，它内部要调这个方法。
 *
 * 本文件由 `scripts/sync_netease_fork.ps1` 机械生成，**不要手改**。
 * 脚本取母工程的 CastActivity.java 逐字复制（只改包名），再把上面那些片段拼进来，
 * 所以母工程的修复会自动流到本分支，不需要每次重新分析差异。
 *
 * 本分支永不建界面：onCreate 直接进"打开即投屏"。母工程那套管理界面
 * （应用列表 / 镜像预览 / 触摸注入 / 一键按钮）的代码仍在文件里，但没有任何入口
 * 调用它——保留它们正是为了让本文件能与母工程逐字对齐，差异越少跟进越省。
 * 为此三处只加判空守卫、不删代码：refreshStatus / setStatus / applyMirrorVisuals
 * 在自动分支下会拿到 null 界面元素，会被后台路径调到。
 *
 * 本分支实际执行的脚本（与母工程「一键」同一个）：
 *   投到仪表屏 display 2 → 轮询真实屏位等它稳定 → 抓帧判页、闭环补点进歌词页 → 结束。
 *   补点落点是实测数据，存 res/values/quick_taps.xml：网易云把播放页做成了应用内部页面，
 *   既不开放深链也不响应语音广播。
 *
 * 硬约束（任何"优化""简化""重构"都是错的）：
 *   - settleThenTap / tapUntilTargetPage 的方法体逐字保留；
 *   - AutoCast / CarAccount / DashboardEye / quick_taps.xml 逐字保留；
 *   - DashboardEye.grab / DashboardEye.classify 的用法不变。
 *
 * 看门：本分支送到歌词页就结束，不留看门。新骨架没有 keepWatchAfterTarget 这个概念——
 * 新代理自己会在目标稳定 WATCH_STABLE_MS 后松开（Agent.releaseWatchIfStable），
 * 所以 runQuickCast 调 injector.watch(pkg, displayId) 即可。
 *
 * 桌面入口是 DisclaimerActivity（与母工程一致）。本分支包名不同，SharedPreferences
 * 是独立的，所以免责声明本分支会单独弹一次。
 *
 * 已知限制：授权丢失时 ensureAgent 会去打开引导页（母工程行为，本分支未改）。
 */
public final class CastActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "dashcast";

    /** 代理侧常量，必须与 com.byd.dashcast.agent.Agent 保持一致。 */
    private static final String ACTION_AGENT_READY = "com.byd.dashcast.action.AGENT_READY";
    private static final String EXTRA_BINDER = "com.byd.dashcast.extra.AGENT_BINDER";
    private static final long HEARTBEAT_INTERVAL_MS = 2000;

    /** 等代理广播 Binder 的上限；代理静默 5 秒会重播，这里留一倍余量。 */
    private static final long AUTO_BIND_TIMEOUT_MS = 10000;
    /**
     * 补点前的等待策略。不能睡固定时长——启动之后整条 root task 会先跳到主屏，
     * 由看门再搬回仪表屏（看门 1 秒一拍、搬完还有 3 秒静默期），这段时长是变动的。
     * 所以轮询代理给出的真实屏位，确认真回到仪表屏了再点。
     */
    private static final long TASK_POLL_INTERVAL_MS = 250;
    private static final long TASK_SETTLE_TIMEOUT_MS = 8000;
    /** 目标本来就在仪表屏上：没有搬动，就没有跨屏重排，等一小会儿即可。 */
    private static final long SETTLE_STEADY_MS = 250;
    /** 闭环补点：抓一帧判页，最多点几次。 */
    private static final int TAP_MAX_ATTEMPTS = 3;
    /** 点完到下次抓帧之间，给页面切换留的时间。 */
    private static final long TAP_SETTLE_MS = 1200;

    /**
     * 等目标页（歌词播放页）出现的总预算。
     *
     * 这个值是按**重启后立即冷启动**定的，不是按热启动定的：那时网易云进程不存在、
     * RN 包没预热，从启动到画出首页要好几秒。旧实现在这里没有预算概念——固定只检查
     * 3 次、而且把"不是首页"当成"到了目标页"，于是冷启动窗口内必然误报成功。
     */
    private static final long TARGET_DEADLINE_MS = 20000;
    /** 「既不是首页也不是目标页」时的复检间隔。抓帧要拆建一次镜像，不宜太密。 */
    private static final long PAGE_POLL_MS = 500;
    /** 补点落点与判页都按仪表屏原始分辨率。 */
    private static final int CLUSTER_WIDTH = 1920;
    private static final int CLUSTER_HEIGHT = 720;
    /** 刚被看门从主屏搬回来：整窗要重排，等足——实测 400ms 时点击不命中。 */
    private static final long SETTLE_AFTER_MOVE_MS = 1500;

    /** 未镜像时触控板的提示底色；镜像成立后透明，让画面透出来。 */
    /** 提示条自动消失时间。够看完一句话，又不至于挡着预览。 */
    private static final long SNACK_MS = 5000L;

    private TextureView preview;
    private View touchPanel;
    private View appPanel;
    private ListView appList;
    private TextView emptyHint;
    private RadioButton tabFavorite;
    private RadioButton tabAll;
    private TextView status;
    private View statusDot;
    private Button btnQuick;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View snackBar;
    private TextView snackMsg;
    private View cornerHint;

    private QuickLaunch quick;

    /**
     * 本次会话最近一次投到仪表屏的包名。「退出管理」要把它搬回主屏前台——
     * 看门会在投放稳定后自动松开，所以不能靠 watchedPackage 记这件事。
     */
    private String lastCastPackage;

    /** 最近一次镜像失败的原因。只给「查看诊断」用，不进状态行。 */
    private String lastMirrorMessage;

    /** 提示条自动消失。与心跳共用主线程 Handler，语义互不干扰。 */
    private final Runnable hideSnack = new Runnable() {
        @Override
        public void run() {
            snackBar.setVisibility(View.GONE);
        }
    };

    private AutoCast autoCast;

    /**
     * 打开即投屏分支：本实例不建任何界面元素（status / preview / 列表全为 null），
     * 所以 setStatus / refreshStatus 必须先判空，否则后台执行时会 NPE。
     */
    private boolean autoMode;
    private boolean autoStarted;
    /** 复用同一个「一键」脚本；它跑失败时，"本次开机仅一次"的标记要回滚，用户才能再试。 */
    private boolean quickCastOk;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private DashboardSession session;
    private InjectClient injector;
    private Favorites favorites;

    /** 代理补齐只做一次；界面可能被反复 resume，重复提交会重复连 adbd。 */
    private boolean agentBringUpStarted;
    /** 代理补齐还没出结果——状态行据此区分「正在拉起」与「拉起失败」。 */
    private boolean agentBringUpRunning;

    /** 代理下发的全量清单。 */
    private final List<AppRepo.Entry> allApps = new ArrayList<AppRepo.Entry>();
    /** 当前标签下实际展示的清单（收藏优先排序）。 */
    private final List<AppRepo.Entry> shownApps = new ArrayList<AppRepo.Entry>();
    private AppAdapter appAdapter;

    /** 收藏包名的快照，随 rebuildList 刷新——避免 getView 里反复读 SharedPreferences。 */
    private Set<String> favoritePackages = Collections.emptySet();

    private boolean favoritesOnly;
    private boolean panelOpen;
    private Surface previewSurface;
    private boolean mirroring;
    private long downTime;

    /**
     * 正在被看门的包名；null 表示不看门。看门的作用是：应用自己发起的启动不带 display，
     * 会让整条 root task 被系统挪回主屏，代理负责搬回。
     */
    private String watchedPackage;
    /** 代理回执的最新看门状态（含搬回次数），由心跳带回。 */
    private InjectClient.WatchState watchState;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            InjectClient.WatchState state = injector.ping();
            // 只在"搬回次数"变化时刷状态栏，避免心跳每 2 秒把用户刚看到的提示冲掉。
            if (state != null && (watchState == null || state.moves != watchState.moves)) {
                watchState = state;
                refreshStatus();
            }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver agentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            IBinder binder = extras == null ? null : extras.getBinder(EXTRA_BINDER);
            if (binder == null) {
                Log.w(TAG, "广播里没有 Binder extra");
                return;
            }
            injector.attach(binder);
            if (!injector.isAttached()) {
                refreshStatus();
                return;
            }
            if (autoMode) {
                // 打开即投屏：代理一连上就立刻跑脚本。不建界面，也不建镜像——
                // 镜像挂在本界面的 Surface 上，界面不存在就无从谈起。
                if (session.isActive()) {
                    injector.setDisplay(session.displayId());
                }
                runAutoCast();
                return;
            }
            if (session.isActive()) {
                injector.setDisplay(session.displayId());
                requestApps();
                // 代理连上之前 onResume/onSurfaceTextureAvailable 都已经跑过并因
                // "代理未连接"提前返回，所以这里必须补一次。
                // startMirrorIfReady 自带 mirroring 幂等判断，不会造成反复重建——
                // 之前 churn 的元凶是这里多调了一次 applyMirrorVisuals(false) 把标志复位了。
                startMirrorIfReady();
            }
            adoptAgentWatch();
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        autoCast = new AutoCast(this);
        // 本分支（网易云版）的启动策略就是"打开即投屏"：没有开关、不看登录账号、不看是不是首启。
        // 每次打开都跑同一个脚本，跑完就退出，全程不建界面。
        // 桌面入口是 DisclaimerActivity（与母工程一致）：本分支包名不同，SharedPreferences
        // 是独立的，所以本分支自己会单独弹一次免责声明。
        // 透明度由 manifest 的 CastTheme 提供——运行时 setTheme 改不动窗口背景，
        // 实测那样主屏会黑屏约 2 秒。本分支不 setContentView，窗口整块透明。
        autoMode = true;
        super.onCreate(savedInstanceState);
        prepareSession();
        registerReceiver(agentReceiver, new IntentFilter(ACTION_AGENT_READY));
        // 代理是 uid 2000 的独立进程，会被系统回收；每次打开都补齐一次，不假定它还活着。
        ensureAgent();
        startAutoCast();
    }

    /** 界面元素的绑定。 */
    private void bindUi() {
        preview = (TextureView) findViewById(R.id.preview);
        touchPanel = findViewById(R.id.touchPanel);
        emptyState = findViewById(R.id.emptyState);
        emptyTitle = (TextView) findViewById(R.id.emptyTitle);
        emptyBody = (TextView) findViewById(R.id.emptyBody);
        snackBar = findViewById(R.id.snackBar);
        snackMsg = (TextView) findViewById(R.id.snackMsg);
        cornerHint = findViewById(R.id.cornerHint);
        appPanel = findViewById(R.id.appPanel);
        appList = (ListView) findViewById(R.id.appList);
        emptyHint = (TextView) findViewById(R.id.emptyHint);
        tabFavorite = (RadioButton) findViewById(R.id.tabFavorite);
        tabAll = (RadioButton) findViewById(R.id.tabAll);
        status = (TextView) findViewById(R.id.status);
        statusDot = findViewById(R.id.statusDot);
        btnQuick = (Button) findViewById(R.id.btnQuick);
    }

    /** 界面与后台都要有的东西：仪表盘屏定位 + 代理客户端 + 收藏 + 一键收藏目标。 */
    private void prepareSession() {
        session = new DashboardSession(this);
        session.resolve();
        injector = new InjectClient();
        favorites = new Favorites(this);
        quick = new QuickLaunch(this);
    }

    /**
     * 补齐注入代理。
     *
     * 代理是 {@code app_process} 起在 uid 2000 下的独立进程，不是本应用的组件：
     * 系统回收、用户手动杀、开机时接收器还没跑完，都会让"界面活着、代理没了"。
     * 所以"代理必须在跑"这条不变量属于界面自己，而不是某一个入口——
     * 无论从桌面图标、最近任务还是 {@code am start} 进来，都必须自愈。
     *
     * requestAuthorization 传 false：这里绝不能弹授权框。没授权就连不上，
     * 立刻把用户交给 {@link GuideActivity} 走引导，不会留下收不到输入的孤儿对话框。
     * 整条链最慢要等 adbd 握手 + 上传 jar + 轮询 pidof，所以整体放后台线程。
     */
    private void ensureAgent() {
        if (agentBringUpStarted) {
            return;
        }
        agentBringUpStarted = true;
        agentBringUpRunning = true;
        refreshStatus();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbBootstrap.Result result =
                        AdbBootstrap.provision(CastActivity.this,
                                AdbBootstrap.TIMEOUT_BACKGROUND_MS, false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        agentBringUpRunning = false;
                        if (result.state == AdbClient.State.READY && result.agentRunning) {
                            // 代理起来后会广播 AGENT_READY，界面在接收器里续上，这里不用管。
                            Log.i(TAG, "代理补齐成功：" + result.message);
                            refreshStatus();
                            return;
                        }
                        Log.w(TAG, "代理补齐失败：" + result);
                        // 没有授权就没有任何界面能救，交给引导页。
                        if (result.state != AdbClient.State.READY) {
                            startActivity(new Intent(CastActivity.this, GuideActivity.class));
                        }
                        refreshStatus();
                    }
                });
            }
        }, "dashcast-agent-bringup");
        worker.setDaemon(true);
        worker.start();
    }

    /** 统一的事件绑定。顶栏那两个是 ImageButton，所以按 View 取而不是按 Button 取。 */
    private void click(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(listener);
    }

    // ---- 一键启动 ----------------------------------------------------------

    /** 按钮文案：选过目标就带上应用名，没选过就是「设置一键启动」。 */
    private void applyQuickLabel() {
        QuickLaunch.Target target = quick.target();
        btnQuick.setText(target == null
                ? getString(R.string.quick_set)
                : getString(R.string.quick_button) + "：" + target.label);
    }

    /**
     * 按下「一键启动」。
     *
     * 选过目标：直接投。
     * 没选过：**不替用户猜一个应用**，只给提示条和「去选择」——猜错的话用户会看到
     * 一个自己没选过的应用出现在仪表屏上，比什么都不做更糟。
     */
    private void onQuickButton() {
        QuickLaunch.Target target = quick.target();
        if (target == null) {
            showSnackBar(R.string.quick_no_target);
            return;
        }
        runQuickCast(target);
    }

    /** 点亮/取消这个收藏项作为一键启动目标。单选：点亮一个就覆盖上一个。 */
    private void selectQuickTarget(AppRepo.Entry entry) {
        if (quick.isTarget(entry.packageName)) {
            quick.clear();
            rebuildList();
            applyQuickLabel();
            refreshStatus();
            setStatus(getString(R.string.quick_target_off));
            return;
        }
        quick.set(entry);
        rebuildList();
        applyQuickLabel();
        toast(getString(R.string.quick_target_on) + entry.label);
        refreshStatus();
        setStatus(getString(R.string.quick_target_on) + entry.label);
    }

    /**
     * 一键投屏：把目标送到仪表屏，**只做这一件事**。
     *
     * 不补点、不判页、不代替目标应用做任何操作。看门要留：启动会让整条 root task 先跳到
     * 主屏，再由代理搬回仪表屏；不留看门，这次投屏当场就会丢。看门由本界面掌握，
     * 「退出管理」时撤销。
     */
    private void runQuickCast(final QuickLaunch.Target target) {
        if (!session.isActive()) {
            String text = getString(R.string.status_no_display);
            setStatus(text);
            toast(text);
            return;
        }
        final int display = session.displayId();
        // TOUCH 不带 display，代理用的是它自己 SET_DISPLAY 记下的那块屏。
        injector.setDisplay(display);

        // 先开看门再启动，顺序反了就会出现"启动那一刻没人把它拉回来"。
        watchedPackage = target.packageName;
        lastCastPackage = target.packageName;
        watchState = null;
        injector.watch(target.packageName, display);

        setStatus(getString(R.string.launching) + target.label);
        // 屏位检查放后台线程：这是 binder 调用。
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int at = injector.taskDisplay(target.packageName);
                // 只有真的找到任务（屏位 >= 0）才走搬屏。-1 表示"没有该任务"、-2 表示
                // 原语不可用，这两种都必须去启动。
                if (at >= 0) {
                    // 已经有任务：搬过去，不要再 am start。`am start-activity --display N`
                    // 在目标屏上没有该包的任务时会新建一条 root task，结果同一个应用在
                    // 两块屏上各跑一份、各有各的页面和动画。
                    int[] moved = injector.moveToDisplay(target.packageName, display);
                    Log.i(TAG, "目标已有任务（在 display " + at + "），搬屏结果：原屏="
                            + moved[0] + " 搬动=" + moved[1]);
                    reportQuickCast(target, true, null);
                    return;
                }
                // 目标当前没有任务（屏位=-1/-2），走启动。
                injector.launch(display, target.packageName, target.activityName,
                        new InjectClient.LaunchCallback() {
                            @Override
                            public void onResult(boolean success, String message) {
                                reportQuickCast(target, success, message);
                            }
                        });
            }
        }, "dashcast-quick");
        worker.setDaemon(true);
        worker.start();
    }

    private void reportQuickCast(final QuickLaunch.Target target, final boolean success,
            final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatus(success ? getString(R.string.cast_ok) + target.label
                        : getString(R.string.cast_failed) + message);
            }
        });
    }

    /**
     * 认领代理侧残留的看门。代理在客户端失联后要过一会儿才撤看门，
     * 本实例重新打开时并不知道它在看谁；不认领的话「退出管理」撤不掉它。
     */
    private void adoptAgentWatch() {
        if (watchedPackage != null) {
            return;
        }
        InjectClient.WatchState state = injector.lastWatch();
        if (state != null && state.watching && state.packageName.length() > 0) {
            watchedPackage = state.packageName;
            watchState = state;
            Log.i(TAG, "认领代理侧看门：" + state.packageName);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ---- 应用列表 / 收藏 ----------------------------------------------------

    private void setFavoritesOnly(boolean only) {
        favoritesOnly = only;
        // 选中态由 RadioGroup 管，但本方法还可能被提示条那条路径调用，
        // 所以在这里把勾选补上，保证"看到的和生效的"始终一致。
        ((RadioGroup) findViewById(R.id.seg)).check(only ? R.id.tabFavorite : R.id.tabAll);
        rebuildList();
    }

    private void setPanelOpen(boolean open) {
        panelOpen = open;
        appPanel.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    /** 收藏优先，其次按名称；标签计数与实际排序都从这里出，保证两边一致。 */
    private void rebuildList() {
        final Set<String> marked = favorites.all();
        favoritePackages = marked;
        shownApps.clear();
        for (AppRepo.Entry entry : allApps) {
            if (favoritesOnly && !marked.contains(entry.packageName)) {
                continue;
            }
            shownApps.add(entry);
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(shownApps, new Comparator<AppRepo.Entry>() {
            @Override
            public int compare(AppRepo.Entry a, AppRepo.Entry b) {
                boolean favoriteA = marked.contains(a.packageName);
                boolean favoriteB = marked.contains(b.packageName);
                if (favoriteA != favoriteB) {
                    return favoriteA ? -1 : 1;
                }
                return collator.compare(a.label, b.label);
            }
        });
        appAdapter.notifyDataSetChanged();
        updateTabs(marked.size());
        emptyHint.setText(allApps.isEmpty()
                ? getString(R.string.empty_all) : getString(R.string.empty_favorite));
    }

    private void updateTabs(int favoriteCount) {
        tabFavorite.setText(getString(R.string.tab_favorite) + " " + favoriteCount);
        tabAll.setText(getString(R.string.tab_all) + " " + allApps.size());
    }

    private void toggleFavorite(AppRepo.Entry entry) {
        boolean added = favorites.toggle(entry.packageName);
        // 取消收藏时把一键启动目标一起清掉：选择项只长在收藏项上，
        // 留着目标会变成一个界面上看不见、却还在生效的选择。
        if (!added && quick.isTarget(entry.packageName)) {
            quick.clear();
            applyQuickLabel();
        }
        rebuildList();
        // 先刷新状态行再写提示，否则提示会被 refreshStatus 立刻覆盖掉。
        refreshStatus();
        setStatus((added ? getString(R.string.favorite_on) : getString(R.string.favorite_off))
                + entry.label);
    }

    /**
     * 应用清单由代理枚举：uid 2000 不受包可见性过滤，App 自己查只能拿到一小撮。
     */
    private void requestApps() {
        injector.listApps(new InjectClient.AppsCallback() {
            @Override
            public void onApps(final List<AppRepo.Entry> loaded) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        rebuildList();
                    }
                });
            }
        });
    }

    /**
     * 启动必须交给 uid-2000 代理：AMS 会拒绝普通应用把非多屏应用送上副屏
     * （报错为 "Permission Denial: ... with launchDisplayId=N"）。
     */
    private void launchOnDashboard(final AppRepo.Entry entry) {
        if (!session.isActive()) {
            setStatus(getString(R.string.status_no_display));
            return;
        }
        setPanelOpen(false);
        setStatus(getString(R.string.launching) + entry.label);
        injector.launch(session.displayId(), entry.packageName, entry.activityName,
                new InjectClient.LaunchCallback() {
                    @Override
                    public void onResult(final boolean success, final String message) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus(success ? getString(R.string.cast_ok) + entry.label
                                        : getString(R.string.cast_failed) + message);
                                if (success) {
                                    // 投上去之后立刻看住它：应用自己发起的启动不带 display，
                                    // 会把整条 root task 挪回主屏。
                                    watchedPackage = entry.packageName;
                                    lastCastPackage = entry.packageName;
                                    watchState = null;
                                    injector.watch(entry.packageName, session.displayId());
                                }
                            }
                        });
                    }
                });
    }

    // ---- 预览 Surface 与仪表盘镜像 ------------------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(TAG, "预览 Surface 就绪 " + width + "x" + height);
        previewSurface = new Surface(surfaceTexture);
        startMirrorIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(TAG, "预览尺寸变化 " + width + "x" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.i(TAG, "预览 Surface 销毁");
        injector.stopMirror();
        applyMirrorVisuals(false);
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // 每帧回调，无需处理。
    }

    /**
     * 镜像成立时把画面透出来，未镜像时让空状态接管预览区。
     *
     * 空状态自带 preview_bg 底色、盖住整个预览区，所以这里不必去猜"TextureView 在还没有帧
     * 的时候会画出什么颜色"——浅色主题下那个猜测必然出错。角标只在真的有画面时才显示，
     * 否则「可直接触控」会出现在一张什么都没有的图上。
     */
    private void applyMirrorVisuals(boolean active) {
        mirroring = active;
        if (autoMode) {
            // 打开即投屏分支没有界面元素（cornerHint 为 null），而 onPause 一定会走到这里；
            // 不判空就是一次空指针崩溃：进程当场死亡、脚本中断，实测踩过。
            return;
        }
        cornerHint.setVisibility(active ? View.VISIBLE : View.GONE);
        refreshStatus();
    }

    /** 三样都齐了才建镜像：Surface 就绪、代理已连、仪表盘屏已定位。 */
    private void startMirrorIfReady() {
        if (mirroring || previewSurface == null || !injector.isAttached() || !session.isActive()) {
            return;
        }
        injector.startMirror(previewSurface, new InjectClient.MirrorCallback() {
            @Override
            public void onResult(final boolean success, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lastMirrorMessage = success ? null : message;
                        applyMirrorVisuals(success);
                        if (!success) {
                            setStatus(getString(R.string.mirror_failed) + message);
                        }
                        refreshStatus();
                    }
                });
            }
        });
    }

    // ---- 生命周期 ----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        heartbeatHandler.removeCallbacks(heartbeat);
        heartbeatHandler.post(heartbeat);
        // 镜像跟着可见性走：前台才建，后台就拆，与心跳的生命周期保持一致，
        // 否则会出现"心跳停了→代理以为客户端走了→重建镜像"的循环。
        startMirrorIfReady();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        heartbeatHandler.removeCallbacks(heartbeat);
        injector.stopMirror();
        applyMirrorVisuals(false);
        // 界面不再可见就撤销看门。这是"投屏后应用还能被拉回前台"的关键：
        // 用户在主屏点该应用图标时，本界面必然 pause，看门必须在那一刻松手，
        // 否则代理会在 1 秒内把任务又搬回仪表屏，表现为"按了回不到前台"。
        // 撤销看门不会把已经投上去的应用搬走——它留在仪表屏上，直到有人动它。
        stopWatching();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopWatching();
        injector.stopMirror();
        heartbeatHandler.removeCallbacks(hideSnack);
        try {
            unregisterReceiver(agentReceiver);
        } catch (Throwable t) {
            Log.w(TAG, "注销代理广播接收器失败", t);
        }
        super.onDestroy();
    }

    /**
     * 撤销看门。看门的生命周期必须由界面掌握：不撤销的话，用户手动把任务搬回主屏后
     * 会被代理反复拽回仪表屏，而界面已经关了、没人能关掉它。
     */
    private void stopWatching() {
        if (watchedPackage == null) {
            return;
        }
        Log.i(TAG, "停止看门：" + watchedPackage);
        watchedPackage = null;
        watchState = null;
        injector.unwatch();
    }

    /**
     * 退出管理：把这次投上去的应用搬回主屏前台，然后关闭本界面。
     *
     * 移动任务需要 uid 2000（普通应用不能移动任务），走代理的 moveRootTaskToDisplay：
     * 它会把任务移到目标屏并置顶，所以搬完就是前台。
     *
     * 搬不动也照样关掉界面——「退出管理」首先得能退出。没投过东西时也一样只关界面。
     */
    private void exitManagement() {
        stopWatching();
        final String pkg = lastCastPackage;
        if (pkg == null || !session.isActive() || !injector.isAttached()) {
            finish();
            return;
        }
        setStatus(getString(R.string.exit_moving) + pkg);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] moved = injector.moveToDisplay(pkg, Display.DEFAULT_DISPLAY);
                Log.i(TAG, "退出管理：把 " + pkg + " 搬回主屏，原屏=" + moved[0]
                        + " 搬动=" + moved[1]);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }, "dashcast-exit");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 状态行是唯一的用户可见反馈，任何一处变化都要走这里。
     *
     * 正常时**只说一句话**：「仪表盘正在显示：<应用>」。display 编号、pid、镜像帧率
     * 这些都进日志（见 {@link #diagnosis()}），不进界面 —— 用户要知道的是"现在仪表屏上
     * 是什么"，不是"第几号屏"。
     *
     * 圆点和文案一起变，不允许只改一个：圆点是给余光看的，文案是给正眼看的，
     * 两者不一致时用户会先信圆点，然后被文案打脸。
     */
    private void refreshStatus() {
        if (status == null) {
            // 打开即投屏分支不 setContentView，界面元素全部为 null。refreshStatus 会被
            // ensureAgent / 广播接收器 / 心跳从后台路径调到，不判空就是一次空指针崩溃。
            return;
        }
        if (!session.isActive()) {
            setStatus(R.color.danger, getString(R.string.status_no_display));
            showEmpty(R.string.empty_display_title, R.string.empty_display_body);
            return;
        }
        if (!injector.isAttached()) {
            // 「正在拉起」和「拉起失败」是两种不同的用户可见状态，不能都糊成"未连接"：
            // 前者用户该等，后者用户该去看引导页。见 §用户可见产品。
            setStatus(agentBringUpRunning ? R.color.warn : R.color.danger,
                    getString(agentBringUpRunning
                            ? R.string.status_agent_connecting : R.string.status_agent_offline));
            showEmpty(R.string.empty_mirror_title, R.string.empty_mirror_body);
            return;
        }
        if (!mirroring) {
            setStatus(R.color.warn, getString(R.string.status_mirror_down));
            showEmpty(R.string.empty_mirror_title, R.string.empty_mirror_body);
            return;
        }
        hideEmpty();
        String tail = watchedPackage != null ? getString(R.string.status_watching) : "";
        setStatus(R.color.ok, statusShowing(shownAppLabel(), tail));
    }

    /** 正常态的那一句话：应用名加粗并用 t1，其余用 t2（对应预览页 `.status b`）。 */
    private CharSequence statusShowing(String name, String tail) {
        String prefix = getString(R.string.status_showing);
        String text = prefix + name + tail;
        SpannableString span = new SpannableString(text);
        int start = prefix.length();
        int end = start + name.length();
        span.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(getColor(R.color.t1)), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    /**
     * 仪表屏上现在跑的是谁。
     *
     * 看门目标优先于"最近投过的包"：用户可能自己把别的应用搬到仪表屏上去了，
     * 这时候看门目标才是真相。两者都没有就照实说"空空如也"，不编一个应用名。
     */
    private String shownAppLabel() {
        String pkg = watchedPackage != null ? watchedPackage : lastCastPackage;
        if (pkg == null) {
            return getString(R.string.status_nothing);
        }
        for (AppRepo.Entry entry : allApps) {
            if (pkg.equals(entry.packageName)) {
                return entry.label;
            }
        }
        // 清单还没回来或者该包不在清单里：显示包名，总比显示"未知应用"有用。
        return pkg;
    }

    // ---- 预览区空状态 / 提示条 ----------------------------------------------

    private void showEmpty(int titleRes, int bodyRes) {
        emptyTitle.setText(titleRes);
        emptyBody.setText(bodyRes);
        emptyState.setVisibility(View.VISIBLE);
    }

    private void hideEmpty() {
        emptyState.setVisibility(View.GONE);
    }

    /** 提示条。带一个动作时用户能立刻把话接下去，而不是记住它再去找按钮。 */
    private void showSnackBar(int messageRes) {
        snackMsg.setText(messageRes);
        snackBar.setVisibility(View.VISIBLE);
        heartbeatHandler.removeCallbacks(hideSnack);
        heartbeatHandler.postDelayed(hideSnack, SNACK_MS);
    }

    private void hideSnackBar() {
        heartbeatHandler.removeCallbacks(hideSnack);
        snackBar.setVisibility(View.GONE);
    }

    /**
     * 空状态里的「重新连接」。
     *
     * 代理不在时先补齐代理（那才是根因），代理在时直接重建镜像通道。
     * 不重解析 session：屏位是启动时解析的，重解析会打断正在跑的任务。
     */
    private void reconnect() {
        lastMirrorMessage = null;
        if (!injector.isAttached()) {
            // 手动重试要能真的重试：把"已经试过"的闩打开，否则这里会静默什么都不做。
            agentBringUpStarted = false;
            ensureAgent();
            return;
        }
        applyMirrorVisuals(false);
        startMirrorIfReady();
        refreshStatus();
    }

    /**
     * 「查看诊断」的内容。状态行里刻意不出现的那些信息（屏位、代理、镜像通道、
     * 最近一次失败原因）在这里一次性给全 —— 它不是设计的一部分，是给排查用的。
     */
    private String diagnosis() {
        StringBuilder text = new StringBuilder();
        text.append("仪表盘屏：")
                .append(session.isActive() ? "display " + session.displayId() : "未定位");
        text.append("\n注入代理：").append(injector.isAttached() ? "已连接" : "未连接");
        text.append("\n镜像通道：").append(mirroring ? "已建立" : "未建立");
        if (!mirroring && lastMirrorMessage != null) {
            text.append("\n失败原因：").append(lastMirrorMessage);
        }
        text.append("\n预览 Surface：").append(previewSurface == null ? "未就绪" : "就绪");
        return text.toString();
    }

    // ---- 动作 --------------------------------------------------------------

    private void sendKey(int keyCode) {
        if (!session.isActive()) {
            setStatus(getString(R.string.status_no_display));
            return;
        }
        injector.key(keyCode);
    }

    /** 带语义圆点的状态。圆点颜色与文案必须成对出现，所以两个参数缺一不可。 */
    private void setStatus(int dotColorRes, CharSequence text) {
        Log.i(TAG, "状态：" + text);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(dotColorRes)));
        status.setText(text);
    }

    /** 瞬时提示：只改文案，不动圆点 —— 圆点表达的是"连接成不成立"，不是"刚发生了什么"。 */
    private void setStatus(CharSequence text) {
        Log.i(TAG, "状态：" + text);
        if (status == null) {
            // 打开即投屏分支没有状态栏，日志就是唯一的落点。
            return;
        }
        status.setText(text);
    }

    /**
     * 应用图标占位块的底色：按包名算一个稳定的色相。
     *
     * 清单来自 uid 2000，本应用受包可见性过滤、拿不到别的包的 icon；又不能全画成同一个灰块，
     * 那样一屏方块扫视时认不出谁是谁。用包名哈希定色相，保证同一应用每次同色、
     * 不同应用大概率不同色；饱和度与明度固定，白色字形在深浅两种主题下都够对比度。
     */
    private static int tileColor(String packageName) {
        int hash = packageName == null ? 0 : packageName.hashCode();
        return Color.HSVToColor(new float[] { Math.abs(hash % 360), 0.62f, 0.72f });
    }

    /** 占位块上的字形：应用名首字。按码点取，避免把代理对字符劈成半个。 */
    private static String glyphOf(String label) {
        if (label == null || label.isEmpty()) {
            return "?";
        }
        return new String(Character.toChars(label.codePointAt(0)));
    }

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View row = recycled != null ? recycled
                    : getLayoutInflater().inflate(R.layout.item_app, parent, false);
            final AppRepo.Entry entry = shownApps.get(position);
            boolean marked = favoritePackages.contains(entry.packageName);
            boolean isTarget = quick.isTarget(entry.packageName);

            // 是当前一键启动目标的行给一块底色 + 左侧竖条：一眼能看出"按下去的是谁"。
            row.setBackgroundResource(isTarget ? R.drawable.row_bg_on : R.drawable.row_bg_off);

            // 图标占位块：底色按包名算，字形取名称首字（拿不到真实图标，理由见 tileColor）。
            View tile = row.findViewById(R.id.rowTile);
            tile.setBackgroundTintList(ColorStateList.valueOf(tileColor(entry.packageName)));
            TextView glyph = (TextView) row.findViewById(R.id.rowGlyph);
            glyph.setText(glyphOf(entry.label));

            TextView label = (TextView) row.findViewById(R.id.rowLabel);
            label.setText(entry.label);

            TextView pkg = (TextView) row.findViewById(R.id.rowPackage);
            pkg.setText(entry.packageName);

            TextView star = (TextView) row.findViewById(R.id.rowStar);
            star.setText(marked ? R.string.star_on : R.string.star_off);
            star.setTextColor(getColor(marked ? R.color.warn : R.color.t3));
            star.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggleFavorite(entry);
                }
            });

            // 一键启动选择项：只长在收藏项上，单选。
            // 非收藏项留**空格子**而不是灰色禁用圈：先收藏才能设目标是一条因果，
            // 画一个点不动的圈会被读成"坏了"。列头「一键启动」+ 底部说明已经把这条讲清楚了。
            View quickCell = row.findViewById(R.id.rowQuick);
            View quickDot = row.findViewById(R.id.rowQuickDot);
            if (marked) {
                quickDot.setBackgroundResource(isTarget ? R.drawable.radio_on : R.drawable.radio_off);
                quickCell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        selectQuickTarget(entry);
                    }
                });
                quickCell.setVisibility(View.VISIBLE);
            } else {
                quickCell.setOnClickListener(null);
                quickCell.setVisibility(View.INVISIBLE);
            }
            return row;
        }
    }

    /** 打开即投屏分支：等代理广播 Binder；代理静默 5 秒会重播，超时就如实报告并退出。 */
    private void startAutoCast() {
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoStarted) {
                    return;
                }
                Log.w(TAG, "打开即投屏：等注入代理超时");
                // 回滚标记：失败不该吃掉"本次开机仅一次"的机会。
                autoCast.unmarkRan();
                toast(getString(R.string.auto_agent_offline));
                finish();
            }
        }, AUTO_BIND_TIMEOUT_MS);
    }

    private void runAutoCast() {
        if (autoStarted) {
            return;
        }
        autoStarted = true;
        AutoCast.Target target = autoCast.target();
        Log.i(TAG, "打开即投屏：执行 " + target.packageName);
        runQuickCast(target, true, new Runnable() {
            @Override
            public void run() {
                if (!quickCastOk) {
                    // 没投上去就等于这次没用成：把标记还给用户，下次打开可以再试。
                    autoCast.unmarkRan();
                }
                finish();
            }
        });
    }

    /**
     * 一键脚本：投到仪表屏 → 补一次点按 → 结束。
     *
     * background 只影响文案；后台分支没有状态栏，提示一律走 Toast。
     *
     * 形参与旧版的唯一差别：旧版还有 persistentWatch（旧 InjectClient.watch 的第三个参数）。
     * 新骨架的 watch(packageName, displayId) 没有 persistent 概念，新代理自己会在目标稳定
     * WATCH_STABLE_MS 之后松开看门（agent/src/com/byd/dashcast/agent/Agent.java 的
     * releaseWatchIfStable）。所以本分支不再需要 keepWatchAfterTarget() 这个概念，
     * 这个形参也随之取消——看门"留不留"不再是本端的选择题。
     */
    private void runQuickCast(final AutoCast.Target target, final boolean background,
            final Runnable onDone) {
        quickCastOk = false;
        if (!session.isActive()) {
            String text = getString(R.string.status_no_display);
            setStatus(text);
            toast(text);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final int display = session.displayId();
        // TOUCH 不带 display，代理用的是它自己 SET_DISPLAY 记下的那块屏。
        injector.setDisplay(display);

        // 先开看门再启动。`am start-activity --display N` 会把整条 root task 挪到主屏，
        // 得先有人在后面把它拉回来；顺序反了就会出现"补点那一刻应用还在主屏"。
        watchedPackage = target.packageName;
        watchState = null;
        injector.watch(target.packageName, display);

        setStatus(getString(background ? R.string.auto_running : R.string.quick_running)
                + target.label);
        // 启动前的屏位检查放后台线程：这是 binder 调用。
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int at = injector.taskDisplay(target.packageName);
                // 只有真的找到任务（屏位 >= 0）才走搬屏。-1 表示"没有该任务"、-2 表示
                // 原语不可用，这两种都必须去启动——上一版把 -1 也当成"有任务"，
                // 结果该启动的时候搬了个空，脚本静默失败。
                if (at >= 0) {
                    // 已经有任务：搬过去，不要再 am start。
                    // `am start-activity --display N` 在目标屏上没有该包的任务时会新建一条
                    // root task，结果同一个应用在两块屏上各跑一份、各有各的页面和动画
                    // （实测踩过：网易云在 display 0 和 display 2 上同时活着）。
                    Log.i(TAG, "目标已有任务（在 display " + at + "），改为搬屏而非新启动");
                    int[] moved = injector.moveToDisplay(target.packageName, display);
                    Log.i(TAG, "搬屏结果：原屏=" + moved[0] + " 搬动=" + moved[1]);
                    quickCastOk = true;
                    settleThenTap(target, display, onDone,
                            at == display ? SETTLE_STEADY_MS : SETTLE_AFTER_MOVE_MS);
                    return;
                }
                Log.i(TAG, "目标当前没有任务（屏位=" + at + "），走启动");
                injector.launch(display, target.packageName, target.activityName,
                        new InjectClient.LaunchCallback() {
                            @Override
                            public void onResult(boolean success, String message) {
                                if (success) {
                                    quickCastOk = true;
                                    settleThenTap(target, display, onDone, SETTLE_AFTER_MOVE_MS);
                                    return;
                                }
                                final String text = getString(R.string.cast_failed) + message;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        setStatus(text);
                                        toast(text);
                                        if (onDone != null) {
                                            onDone.run();
                                        }
                                    }
                                });
                            }
                        });
            }
        }, "dashcast-quick");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 闭环补点：抓仪表屏一帧**正面判页**，只有确认到了歌词播放页才算成功。
     *
     * 为什么要闭环：补点是盲的坐标点击，既不知道仪表屏上现在是不是目标应用，
     * 也不知道点完有没有反应。实测踩过：一次点在车机导航上（应用还没被搬回来），
     * 一次点了页面没动——两次脚本都照样报"已展开"。
     *
     * 为什么要三分类（见 {@link DashboardEye.Page}）：旧实现把"不是首页"当成
     * "到了目标页"，而重启后冷启动的加载黑屏、启动白屏、车机地图全都"不是首页"，
     * 于是它**一次都不点就报成功**。现在只有 {@code LYRICS} 算成功，
     * {@code OTHER} 一律继续等（既不能点——点在加载画面上是白费，也不能判成功）。
     *
     * 预算用总时长而不是固定次数：冷启动的加载窗口长短不定，固定次数会在慢的时候
     * 提前放弃、在快的时候多等。超时就返回 false，让界面如实报"未生效"。
     *
     * @return true 仅当确认已到歌词播放页
     */
    private boolean tapUntilTargetPage(AutoCast.Target target, int display) {
        float[] point = AutoCast.tapFor(CastActivity.this, target.packageName);
        if (point == null) {
            return true;
        }
        long deadline = SystemClock.uptimeMillis() + TARGET_DEADLINE_MS;
        int taps = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap frame = DashboardEye.grab(injector, CLUSTER_WIDTH, CLUSTER_HEIGHT);
            DashboardEye.Page page = DashboardEye.classify(frame);
            if (frame != null) {
                frame.recycle();
            }

            if (page == DashboardEye.Page.LYRICS) {
                Log.i(TAG, "补点闭环：已到歌词播放页（共点 " + taps + " 次）");
                return true;
            }

            if (page == DashboardEye.Page.HOME) {
                if (taps >= TAP_MAX_ATTEMPTS) {
                    Log.w(TAG, "补点闭环：点了 " + taps + " 次仍停在首页");
                    return false;
                }
                taps++;
                Log.i(TAG, "补点闭环：第 " + taps + " 次点击 " + point[0] + "," + point[1]
                        + " → " + target.packageName);
                injector.tap(display, point[0], point[1]);
                sleepQuietly(TAP_SETTLE_MS);
                continue;
            }

            // OTHER：多半是冷启动还没画出来，或者是车机自己的页面。继续等，别乱点也别报成功。
            Log.i(TAG, "补点闭环：还没到目标页（已点 " + taps + " 次），继续等");
            sleepQuietly(PAGE_POLL_MS);
        }
        Log.w(TAG, "补点闭环：等满 " + TARGET_DEADLINE_MS + " ms 仍未到歌词播放页");
        return false;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 先等目标包的 root task 真的落在仪表屏上，再等窗口重排完，然后补点。
     *
     * 这一步是本次修复的核心。原版按固定时长等，实测点在空处：代理日志里
     * "注入"早于"看门：搬回 display 2"，那一刻应用还在主屏。
     *
     * settleMs 传两档：没搬动（本来就在仪表屏）给一小档就够，刚跨屏搬回来要给足——
     * 跨屏会触发整窗重排，重排没完点击不会命中。
     */
    private void settleThenTap(final AutoCast.Target target, final int display,
            final Runnable onDone, final long settleMs) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = SystemClock.uptimeMillis() + TASK_SETTLE_TIMEOUT_MS;
                int actual = -2;
                while (true) {
                    actual = injector.taskDisplay(target.packageName);
                    if (actual == display || actual == -2
                            || SystemClock.uptimeMillis() >= deadline) {
                        break;
                    }
                    try {
                        Thread.sleep(TASK_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                boolean ok = actual == display;
                if (actual == display) {
                    try {
                        Thread.sleep(settleMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ok = tapUntilTargetPage(target, display);
                } else {
                    Log.w(TAG, "投屏未生效：" + target.packageName
                            + " 屏位=" + actual + "，目标=" + display);
                }
                final boolean reached = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reached) {
                            setStatus(getString(R.string.quick_done) + target.label);
                        } else {
                            // 没到目标页就如实说，不能报"已投屏并展开"。
                            quickCastOk = false;
                            setStatus(getString(R.string.quick_incomplete) + target.label);
                        }
                        // 抓帧会把预览镜像拆掉（同一个镜像通道），这里重建。
                        if (!autoMode) {
                            applyMirrorVisuals(false);
                            startMirrorIfReady();
                        }
                        refreshStatus();
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                });
            }
        }, "dashcast-settle");
        worker.setDaemon(true);
        worker.start();
    }
}
