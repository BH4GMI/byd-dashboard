package com.byd.dashcast;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import com.byd.dashcast.adb.AdbBootstrap;
import com.byd.dashcast.adb.AdbClient;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 主屏上的仪表盘管理界面。三件事：
 *   1. 把选中的应用投到仪表盘那块物理屏（display 2）上；
 *   2. 把仪表屏画面实时镜像回本界面的预览区；
 *   3. 把预览上的手指动作 1:1 注入到仪表屏。
 *
 * 前端规划（为什么是这个形状，完整版见设计预览页 dashcast-design-preview.html）：
 *   - 界面按**作用域**分三层，不看说明也能猜出按下去会怎样：
 *       顶栏 = 关于仪表盘的（状态行 + 作用于仪表盘的返回/主页）
 *       中间 = 仪表盘本身（1920x720 预览 + 触控）
 *       底栏 = 关于应用的（应用列表 / 一键启动 / 退出管理）
 *   - 这是车机，**不设搜索框**——输入法是否弹出不可靠；应用靠「收藏 + 快速滚动」定位，
 *     纯触摸即可，不依赖键盘。
 *   - 状态行只说一句话（`仪表盘正在显示：<应用>`），出问题时同时说清"发生了什么"和
 *     "下一步做什么"；display 编号这类信息进「查看诊断」，不进状态行。
 *   - 状态行圆点与文案成对更新：圆点给余光，文案给正眼，两者不一致用户会先信圆点。
 *   - 收藏存包名（{@link Favorites}），列表里收藏永远排在最前并带星标，另有「收藏」分段只看收藏。
 *   - 星标点一下即收藏/取消，点应用名才是投屏——两个动作分开，避免误投。
 *   - 列表开着时不转发触摸到仪表屏，防止误触；镜像没建立时同样不转发（空状态挡住触控板）。
 *
 * 预览区的尺寸与仪表屏一致（1920x720），所以触控板的 View 局部坐标就是仪表屏坐标。
 *
 * 一键启动（{@link QuickLaunch}）：
 *   - 目标在应用列表里选：**收藏项**的右侧有一个圆圈，点亮即选中，单选（点亮一个就覆盖
 *     上一个），再点一下取消；非收藏项那一格留空。列头与底部说明写清了这条因果。
 *   - 按下「一键启动」把目标投到仪表屏。**只投屏**：不补点、不判页、不代应用做任何操作。
 *     没选过目标就只给提示条和「去选择」，不替用户猜一个应用。
 */
public final class CastActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "dashcast";

    /** 代理侧常量，必须与 com.byd.dashcast.agent.Agent 保持一致。 */
    private static final String ACTION_AGENT_READY = "com.byd.dashcast.action.AGENT_READY";
    private static final String EXTRA_BINDER = "com.byd.dashcast.extra.AGENT_BINDER";
    private static final long HEARTBEAT_INTERVAL_MS = 2000;

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
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast);
        bindUi();
        prepareSession();

        registerReceiver(agentReceiver, new IntentFilter(ACTION_AGENT_READY));
        ensureAgent();

        preview.setSurfaceTextureListener(this);

        touchPanel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                // 列表开着、或画面还没建立时，把触摸留给界面，别盲注到仪表屏。
                if (panelOpen || !session.isActive() || !mirroring) {
                    return false;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downTime = event.getDownTime();
                }
                if (action != MotionEvent.ACTION_MOVE) {
                    Log.i(TAG, "转发触摸 action=" + action + " (" + event.getX()
                            + "," + event.getY() + ") 代理已连接=" + injector.isAttached());
                }
                injector.touch(action, event.getX(), event.getY(),
                        downTime, event.getEventTime());
                return true;
            }
        });

        appAdapter = new AppAdapter();
        appList.setAdapter(appAdapter);
        appList.setEmptyView(emptyHint);
        appList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shownApps.size()) {
                    launchOnDashboard(shownApps.get(position));
                }
            }
        });

        tabFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setFavoritesOnly(true);
            }
        });
        tabAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setFavoritesOnly(false);
            }
        });

        click(R.id.btnApps, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setPanelOpen(!panelOpen);
            }
        });
        click(R.id.btnClosePanel, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setPanelOpen(false);
            }
        });
        click(R.id.btnBack, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendKey(KeyEvent.KEYCODE_BACK);
            }
        });
        click(R.id.btnHome, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendKey(KeyEvent.KEYCODE_HOME);
            }
        });
        click(R.id.btnExit, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exitManagement();
            }
        });
        click(R.id.btnReconnect, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                reconnect();
            }
        });
        click(R.id.btnDiagnose, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toast(diagnosis());
            }
        });
        click(R.id.snackGo, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideSnackBar();
                setFavoritesOnly(true);
                setPanelOpen(true);
            }
        });

        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onQuickButton();
            }
        });

        applyQuickLabel();

        rebuildList();
        refreshStatus();
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
}
