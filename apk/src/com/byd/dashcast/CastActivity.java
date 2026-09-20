package com.byd.dashcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
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
 * 前端规划（为什么是这个形状）：
 *   - 这是车机，**不设搜索框**——输入法是否弹出不可靠；74 个应用靠「收藏 + 快速滚动」定位，
 *     纯触摸即可，不依赖键盘。
 *   - 收藏存包名（{@link Favorites}），列表里收藏永远排在最前并带星标，另有「收藏」标签只看收藏。
 *   - 星标点一下即收藏/取消，点应用名才是投屏——两个动作分开，避免误投。
 *   - 列表开着时不转发触摸到仪表屏，防止误触。
 *
 * 预览区的尺寸与仪表屏一致（1920x720），所以触控板的 View 局部坐标就是仪表屏坐标。
 *
 * 一键与首开自动（两个新入口，共用同一个「快捷目标」）：
 *   - 「一键」按钮 / 列表行里的「一键」= 投到仪表屏 → 补一次点按 → 看门。
 *     补点落点是实测数据，存 res/values/quick_taps.xml，因为有些应用把目标页面做成了
 *     应用内部页面，既不开放深链也不响应语音广播（网易云 6.2.01 就是）。
 *   - 「首开自动」开关 = 记住"当前这个登录账号"，此后本次开机第一次打开本程序时
 *     不显示界面、直接后台跑同一个脚本，第二次打开才是正常界面。
 */
public final class CastActivity extends Activity {

    private static final String TAG = "dashcast";

    private static final long HEARTBEAT_INTERVAL_MS = 2000;
    /** 首开自动等 ADB 通道就绪的上限。超时就如实报告并退出，**不能默默什么都不做**。 */
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
    private static final int TOUCH_PANEL_IDLE_COLOR = 0x1A2E9BFF;
    private static final int TAB_ACTIVE_COLOR = 0xFF6FB3FF;
    private static final int TAB_IDLE_COLOR = 0xFFCCCCCC;

    private TextureView preview;
    /** 降级预览（screencap 抓帧）。与 {@link #preview} **互斥显示**，绝不叠加。 */
    private ImageView previewFallback;
    private View touchPanel;
    private TextView touchHint;
    private View appPanel;
    private ListView appList;
    private TextView emptyHint;
    private Button tabFavorite;
    private Button tabAll;
    private TextView status;
    private Switch switchAuto;
    private Button btnQuick;

    private AutoCast autoCast;
    /** 当前登录的 DiLink 账号；null 表示还没读到或读不到。 */
    private CarAccount.Info account;

    /**
     * 首开自动分支：本实例不建任何界面元素（status / preview / 列表全为 null），
     * 所以 setStatus / refreshStatus 必须先判空，否则后台执行时会 NPE。
     */
    private boolean autoMode;
    private boolean autoStarted;
    /** 复用同一个「一键」脚本；它跑失败时，"本次开机仅一次"的标记要回滚，用户才能再试。 */
    private boolean quickCastOk;
    /** 程序化 setChecked 期间置位，避免被当成用户操作再触发一次绑定。 */
    private boolean suppressAutoToggle;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private DashboardSession session;
    private InjectClient injector;
    /**
     * 特权通道（uid 2000 常驻进程）。预览画面与触摸注入都走它。
     *
     * <p>它不是"可选加速"：没有它，触摸只能退回 shell 的 {@code input} 命令
     * （本车实测 40~140 ms/次，拖拽必然积压），预览只能退回 screencap 抓帧（3.2~3.5 fps）。
     */
    private PrivilegedClient privileged;
    /**
     * 交给特权进程的预览 Surface —— 由 {@link #preview} 的 SurfaceTexture 包出来。
     *
     * <p>特权进程把它作为仪表屏那块屏的输出 BufferQueue：SurfaceFlinger 是生产者、
     * 本视图的 SurfaceTexture 是消费者，App 侧不参与搬运。SurfaceTexture 一旦重建
     * （界面重建、视图重新 attach）旧 Surface 就失效，所以这里要留着引用重新交一次。
     */
    private Surface previewSurface;
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
    /**
     * 预览**画面已经真的在上屏**。触控板据此决定透明（看得见画面）还是盖上提示底色。
     *
     * <p>直通路径下这个信号来自 {@code TextureView.SurfaceTextureListener}：只有
     * SurfaceFlinger 真的开始往这块 BufferQueue 里出帧了，{@code onSurfaceTextureUpdated}
     * 才会回调。所以它不是"请求已发出"的乐观推断，而是"画面上屏了"的事实 ——
     * 这正是触控板敢变透明的前提（早一秒透明就是一秒黑屏）。
     *
     * <p>降级路径下这个信号来自"第一帧 PNG 解码上屏"，见 {@link #previewFrameBusy}。
     */
    private boolean previewing;
    /**
     * 预览**已启动**（请求已发出）。与 {@link #previewing} 分开：两件事要分别控制 ——
     * 防重复启动靠前者，触控板透明靠后者。合成一个标志就会出现"第一帧还没到、
     * 触控板已经透明成黑屏"或者"重复启动两条抓帧线程"。
     */
    private boolean previewStarted;
    /**
     * **降级路径专用**：有一帧正在解码/上屏。多条抓帧通道并发，帧会来得比界面画得快 ——
     * 忙的时候**直接丢掉这一帧**：排队只会让屏幕上的画面越来越旧，宁可掉帧也要保证
     * "这一帧是最近抓到的"。直通路径不经过这里。
     */
    private final java.util.concurrent.atomic.AtomicBoolean previewFrameBusy =
            new java.util.concurrent.atomic.AtomicBoolean();
    private long downTime;

    /**
     * 正在被看门的包名；null 表示不看门。看门的作用是：应用自己发起的启动不带 display，
     * 会让整条 root task 被系统挪回主屏（实测在 B 站里点视频卡就会发生），代理负责搬回。
     */
    private String watchedPackage;
    /** 代理回执的最新看门状态（含搬回次数），由心跳带回。 */
    private InjectClient.WatchState watchState;

    /**
     * 心跳必须跑在**独立线程**上：ping() 会顺着 ADB socket 跑 dumpsys / move-stack，
     * 在主线程上做网络 IO 会直接抛 NetworkOnMainThreadException（实测踩过）。
     * 旧架构走 Binder 调用不涉及网络，所以那时放主线程没事——换成 shell 通道后必须搬走。
     * 只有回写状态栏那一步需要切回主线程。
     */
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;

    /**
     * 触摸转发线程。**必须单线程**：DOWN/MOVE/UP 依赖先后顺序，
     * 用线程池并发下发会让手势乱序、彻底失效。
     */
    private final java.util.concurrent.ExecutorService touchExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            // ping() 现在兼任看门巡检：发现目标被应用自己拽回主屏就搬回来。
            InjectClient.WatchState state = injector.ping();
            // 预览通道也要巡检：它死了画面会静止在最后一帧，不巡检就是一个哑掉的黑屏。
            checkPreviewAlive();
            // 只在"搬回次数"变化时刷状态栏，避免心跳每 2 秒把用户刚看到的提示冲掉。
            if (state != null && (watchState == null || state.moves != watchState.moves)) {
                watchState = state;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                });
            }
            Handler h = heartbeatHandler;
            if (h != null) {
                h.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        autoCast = new AutoCast(this);
        // 账号读取是跨进程的 ContentResolver.query，压在 UI 线程上不合适，
        // 所以只在开关打开时才同步做——那时必须在建窗口之前就知道该不该后台执行。
        // 开关关着（默认）完全不碰账号服务。
        boolean autoWanted = autoCast.enabled();
        CarAccount.Info startupAccount = autoWanted ? CarAccount.read(this) : null;
        Log.i(TAG, "启动决策：开关=" + autoWanted
                + " 绑定账号长度=" + autoCast.userId().length()
                + " 开机标记=" + AutoCast.bootMarker()
                + " 读到账号=" + (startupAccount != null && startupAccount.error == null));
        if (autoCast.shouldRun(startupAccount)) {
            autoCast.markRan();
            autoMode = true;
            // 透明度由 manifest 的 CastTheme 提供——运行时 setTheme 改不动窗口背景，
            // 实测那样主屏会黑屏约 2 秒。本分支不 setContentView，窗口整块透明。
            super.onCreate(savedInstanceState);
            prepareSession();
            ensureAgent();
            startAutoCast();
            return;
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast);
        bindUi();
        prepareSession();

        ensureAgent();

        touchPanel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                // 列表开着时把触摸留给列表，别误投到仪表屏。
                if (panelOpen || !session.isActive()) {
                    return false;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downTime = event.getDownTime();
                }
                if (action != MotionEvent.ACTION_MOVE) {
                    // 只读状态字段，**不在这里做 Binder 往返**：
                    // 这条回调在 UI 线程上，对端若正在 createDisplay 就会把界面卡住。
                    Log.i(TAG, "转发触摸 action=" + action + " (" + event.getX()
                            + "," + event.getY() + ") 预览模式=" + previewMode);
                }
                // 转发必须离开主线程：降级路径下每条触摸都是一次 shell 命令（socket IO），
                // 主线程上做会抛 NetworkOnMainThreadException（实测）。
                // 单线程是为了保证顺序——并发的 DOWN/MOVE/UP 一旦乱序，手势就废了。
                final int a = action;
                final float x = event.getX();
                final float y = event.getY();
                final long dt = downTime;
                final long et = event.getEventTime();
                touchExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        forwardTouch(a, x, y, dt, et);
                    }
                });
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

        button(R.id.btnApps, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setPanelOpen(!panelOpen);
            }
        });
        button(R.id.btnBack, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendKey(KeyEvent.KEYCODE_BACK);
            }
        });
        button(R.id.btnHome, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendKey(KeyEvent.KEYCODE_HOME);
            }
        });
        button(R.id.btnExit, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopWatching();
                finish();
            }
        });

        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runQuickCast(autoCast.target(), false, false, null);
            }
        });

        applyTargetLabel();
        // 监听器最后挂：applyTargetLabel / applyAutoSwitch 里的 setChecked 不应该被当成用户操作。
        switchAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (suppressAutoToggle) {
                    return;
                }
                onAutoToggled(checked);
            }
        });
        loadAccount();

        rebuildList();
        refreshStatus();
    }

    /** 界面元素的绑定。首开自动分支不调用它，所以那些字段会一直是 null。 */
    private void bindUi() {
        preview = (TextureView) findViewById(R.id.preview);
        preview.setSurfaceTextureListener(previewSurfaceListener);
        previewFallback = (ImageView) findViewById(R.id.previewFallback);
        touchPanel = findViewById(R.id.touchPanel);
        touchHint = (TextView) findViewById(R.id.touchHint);
        appPanel = findViewById(R.id.appPanel);
        appList = (ListView) findViewById(R.id.appList);
        emptyHint = (TextView) findViewById(R.id.emptyHint);
        tabFavorite = (Button) findViewById(R.id.tabFavorite);
        tabAll = (Button) findViewById(R.id.tabAll);
        status = (TextView) findViewById(R.id.status);
        switchAuto = (Switch) findViewById(R.id.swAuto);
        btnQuick = (Button) findViewById(R.id.btnQuick);
    }

    /** 两条路径都要有的东西：仪表盘屏定位 + 特权通道客户端 + 收藏。 */
    private void prepareSession() {
        session = new DashboardSession(this);
        session.resolve();
        injector = new InjectClient(this);
        privileged = PrivilegedClient.get(this);
        // 预览与触控共用的兜底屏：解析不出"窗口挂在哪块屏"时指仪表盘本身，
        // 而不是指投屏槽位（抓槽位全黑、往槽位注入会被 InputDispatcher 丢掉）。
        injector.setProjectionDisplay(session.projectionDisplayId());
        favorites = new Favorites(this);
    }

    /**
     * 建立（或复用）shell 通道。
     *
     * <p>2026-09-20 起不再有 uid-2000 常驻代理：投屏、触控、看门全部走
     * {@link ShellChannel} 的一条长连接。旧实现的"代理必须在跑"这条不变量、
     * 以及配套的广播/心跳自愈，都随代理一起消失了。
     *
     * <p>requestAuthorization 传 false：这里绝不能弹授权框。没授权就连不上，
     * 立刻把用户交给 {@link GuideActivity} 走引导，不会留下收不到输入的孤儿对话框。
     * 整条链要等 adbd 握手，所以放后台线程。
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
                        if (result.isReady() && injector.isAttached()) {
                            Log.i(TAG, "shell 通道就绪：" + result.message);
                            onChannelReady();
                            return;
                        }
                        Log.w(TAG, "shell 通道建立失败：" + result);
                        // 没有授权就没有任何界面能救，交给引导页；首开自动那条隐性路径
                        // 本来就不建界面，交给它自己的超时如实报告。
                        if (!autoMode && !result.isReady()) {
                            startActivity(new Intent(CastActivity.this, GuideActivity.class));
                        }
                        refreshStatus();
                    }
                });
            }
        }, "dashcast-channel-bringup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 通道就绪后的续接动作。
     *
     * <p>旧实现这段逻辑挂在广播接收器里（代理广播 Binder → 界面续上）；
     * 现在通道是被本界面主动建立的，所以直接调用即可。
     */
    private void onChannelReady() {
        if (autoMode) {
            // 首开自动：通道一通就立刻跑脚本。不建界面，也就没有预览与触控板。
            if (session.isActive()) {
                injector.setDisplay(session.displayId());
            }
            runAutoCast();
            return;
        }
        if (session.isActive()) {
            injector.setDisplay(session.displayId());
            requestApps();
            startPreviewIfReady();
        }
        adoptAgentWatch();
        refreshStatus();
    }

    private void button(int id, View.OnClickListener listener) {
        Button button = (Button) findViewById(id);
        button.setOnClickListener(listener);
    }

    // ---- 一键投屏 / 首开自动 ------------------------------------------------

    private void applyTargetLabel() {
        btnQuick.setText(getString(R.string.quick_prefix) + autoCast.target().label);
    }

    /** 账号读取放后台线程：跨进程 query，不该占 UI 线程。 */
    private void loadAccount() {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final CarAccount.Info info = CarAccount.read(CastActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        account = info;
                        applyAutoSwitch();
                    }
                });
            }
        }, "dashcast-account");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyAutoSwitch() {
        suppressAutoToggle = true;
        switchAuto.setChecked(autoCast.enabled());
        suppressAutoToggle = false;
    }

    /**
     * 开关只管"绑定还是解绑当前账号"。绑定时把 userId 记下来，
     * 之后只有这个账号在本次开机首次打开才触发；换人登录就不会触发。
     */
    private void onAutoToggled(boolean checked) {
        if (!checked) {
            autoCast.disable();
            setStatus(getString(R.string.auto_off));
            return;
        }
        // 绑定的依据必须是"此刻"的账号，不能拿界面上那份可能过期的缓存；
        // 这是用户主动操作，允许同步读一次。
        CarAccount.Info info = CarAccount.read(this);
        account = info;
        if (info.error != null || !info.loggedIn()) {
            setStatus(getString(R.string.auto_need_account));
            toast(getString(R.string.auto_need_account));
            applyAutoSwitch();
            return;
        }
        if (!autoCast.enable(info)) {
            setStatus(getString(R.string.auto_bind_failed));
            toast(getString(R.string.auto_bind_failed));
            applyAutoSwitch();
            return;
        }
        applyAutoSwitch();
        applyTargetLabel();
        setStatus(getString(R.string.auto_on) + info.display());
    }

    /**
     * 送到目标页之后还要不要留着看门。
     *
     * **母工程留 `true`**：用户接下来要继续用那个应用（B 站里点一张视频卡会让它自己发
     * 不带 display 的启动，整条 root task 被挪回主屏），需要看门把它拽回来。
     *
     * **网易云分支取 `false`**（见该分支的 `apk/fork/CastActivity.overrides.txt`）：
     * 那条链路的目标就是"送到歌词播放页"，送到即结束。
     * 留着看门会把网易云钉死在仪表屏上——用户在桌面上点它、从最近任务拉它都没用，
     * 一秒内被搬走，表现为"按了回不到前台"。看门自己虽然有硬上限（首次窗口 20 s、
     * 每搬回一次延长 8 s、总计不超过 45 s），但用户反复点会一次次把窗口续上
     * （{@code InjectClient} 的 {@code watchDeadline}），所以不能指望它替我们收场。
     * 既然链路已经结束，就别让兜底去承担这件事。
     */
    protected boolean keepWatchAfterTarget() {
        return true;
    }

    /**
     * 首开自动分支：一进来就挂一个超时。通道在 {@link #AUTO_BIND_TIMEOUT_MS} 内没就绪，
     * 就回滚"本次开机仅一次"的标记、如实提示并退出 —— 静默失败会让用户以为功能没生效。
     */
    private void startAutoCast() {
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoStarted) {
                    return;
                }
                Log.w(TAG, "首开自动：ADB 通道迟迟未就绪");
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
        Log.i(TAG, "首开自动：执行 " + target.packageName);
        // 看门要留多久，由子类决定：母工程留（用户还要继续用），网易云分支不留（送到即结束）。
        // 注意形参顺序是 (target, persistentWatch, background, onDone)——两个相邻的 boolean
        // 很容易传反（这里就传反过一次，因为原来是 true,true 看不出来）。
        runQuickCast(target, keepWatchAfterTarget(), true, new Runnable() {
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
     * 一键脚本：投到仪表屏 → 补一次点按 → 看门。
     *
     * persistentWatch 只有首开自动用：那时本界面跑完就结束，非持久看门会在 60 秒后
     * 被代理按"客户端失联"撤掉，之后再动应用就又跑回主屏了。
     * background 只影响文案；后台分支没有状态栏，提示一律走 Toast。
     */
    private void runQuickCast(final AutoCast.Target target, final boolean persistentWatch,
            final boolean background, final Runnable onDone) {
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
        injector.watch(target.packageName, display, persistentWatch);

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
                    if (moved[1] == 1) {
                        quickCastOk = true;
                        settleThenTap(target, display, onDone,
                                at == display ? SETTLE_STEADY_MS : SETTLE_AFTER_MOVE_MS);
                        return;
                    }
                    // 搬屏没生效。**绝不能在这里报成功** —— 那会变成"点了没反应、
                    // 状态栏却说已投屏"，而用户完全不知道发生了什么。
                    // （搬屏成不成功由 ShellChannel 回查屏位判定，不看命令有没有回话。）
                    final String fail = getString(R.string.cast_failed)
                            + getString(R.string.cast_move_failed);
                    Log.w(TAG, "搬屏未生效，原屏=" + moved[0] + " 目标屏=" + display);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(fail);
                            toast(fail);
                            if (onDone != null) {
                                onDone.run();
                            }
                        }
                    });
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
                tapOnCluster(point[0], point[1]);
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
                        // 投屏这一刻 session 才变成 active，预览要在条件刚齐时补一次启动。
                        // 判页抓帧走主连接、预览走预览连接，两者互不干扰 —— 所以这里不再是
                        // 旧架构那种"重建被拆掉的镜像"，只是一次普通的按需启动（幂等）。
                        if (!autoMode) {
                            startPreviewIfReady();
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

    /**
     * 认领代理侧残留的看门。首开自动会留下一个持久看门，之后用户打开界面时
     * 本实例并不知道它在看谁；不认领的话「退出管理」撤不掉它。
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
        rebuildList();
    }

    private void setPanelOpen(boolean open) {
        panelOpen = open;
        appPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btnApps))
                .setText(open ? R.string.app_list_close : R.string.app_list);
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
        tabFavorite.setTextColor(favoritesOnly ? TAB_ACTIVE_COLOR : TAB_IDLE_COLOR);
        tabAll.setTextColor(favoritesOnly ? TAB_IDLE_COLOR : TAB_ACTIVE_COLOR);
    }

    private void toggleFavorite(AppRepo.Entry entry) {
        boolean added = favorites.toggle(entry.packageName);
        rebuildList();
        setStatus((added ? getString(R.string.favorite_on) : getString(R.string.favorite_off))
                + entry.label);
        refreshStatus();
    }

    /**
     * 应用清单由代理枚举：uid 2000 不受包可见性过滤，App 自己查只能拿到一小撮
     * （实测 24 vs 74）。
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
     * （实测 "Permission Denial: ... with launchDisplayId=N"）。
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
                                    // 会把整条 root task 挪回主屏（实测：B 站点视频卡）。
                                    watchedPackage = entry.packageName;
                                    watchState = null;
                                    injector.watch(entry.packageName, session.displayId(), false);
                                }
                            }
                        });
                    }
                });
    }

    // ---- 仪表盘预览 --------------------------------------------------------

    /**
     * 预览走哪条路。用枚举而不是布尔：两条路的**信号源完全不同** ——
     * 直通看 {@code onSurfaceTextureUpdated}（SurfaceFlinger 出帧），降级看 PNG 解码上屏。
     * 混成一个标志就会出现"降级时被 TextureView 的回调误判成画面已上屏"。
     */
    private enum PreviewMode {
        /** 还没开始，或已经停掉。 */
        IDLE,
        /** 特权进程把仪表屏画进本界面的 TextureView：GPU 直通，满帧。 */
        PASSTHROUGH,
        /** 特权通道不可用，退回 screencap 抓帧（实测 3.2~3.5 fps）。 */
        FALLBACK
    }

    private PreviewMode previewMode = PreviewMode.IDLE;
    /**
     * 画面**已经接上**特权进程（`setDisplaySurface` 成功）。
     *
     * <p>与 {@link #previewing} 不同：接通不等于出帧 —— 仪表屏内容静止时
     * SurfaceFlinger 不会重复合成，回调可能长时间不来。心跳靠这个字段分辨
     * "画面接好了但内容没变"和"对端已经死了、画面静止在最后一帧"。
     */
    private boolean previewBound;
    /** 走到降级路径的原因，展示在状态行里。**不允许静默降级**。 */
    private String fallbackReason = "";

    /**
     * 预览 Surface 的生命周期回调。**直通路径的关键一环**：
     * Surface 只能从视图系统给的 SurfaceTexture 包出来，没有别的来源。
     */
    private final TextureView.SurfaceTextureListener previewSurfaceListener =
            new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            Log.i(TAG, "预览 Surface 就绪 " + width + "x" + height);
            attachPreviewSurface(texture);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            Log.i(TAG, "预览 Surface 尺寸变化 " + width + "x" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            detachPreviewSurface();
            // true = 允许视图系统回收。**必须归还**：留着的话重建界面时新旧
            // SurfaceTexture 会同时存在，旧的还挂在特权进程上。
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            // 每出一帧回调一次。只在"从没有画面到有画面"这一跳上动作 ——
            // 触控板变透明意味着盖掉自己的提示底色，不能每帧都重来一遍。
            if (previewing || previewMode != PreviewMode.PASSTHROUGH) {
                return;
            }
            applyPreviewVisuals(true);
            refreshStatus();
        }
    };

    /**
     * 预览成立时把画面透出来，未成立时盖上提示底色（提示用户"这里本来会显示仪表盘"）。
     *
     * <p>只动"画面已上屏"这一个状态；"是否已启动"由 {@link #previewStarted} 单独表示。
     */
    private void applyPreviewVisuals(boolean active) {
        previewing = active;
        if (touchPanel == null) {
            // 首开自动分支不 setContentView，界面元素全部为 null。而 onPause 一定会走到这里
            // （网易云被拉到前台时本界面就 pause 了），不判空就是一次空指针崩溃：
            // 进程当场死亡、脚本中断、网易云留在前台。实测踩过。
            return;
        }
        touchPanel.setBackgroundColor(active ? Color.TRANSPARENT : TOUCH_PANEL_IDLE_COLOR);
        touchHint.setVisibility(active ? View.GONE : View.VISIBLE);
    }

    /**
     * 切换预览模式：两个预览视图**互斥显示**。
     *
     * <p>切到直通时要补一次 {@link #attachPreviewSurface}：视图从 GONE 回到 VISIBLE 时
     * SurfaceTexture 不一定被重建（是否重建取决于视图系统），那样就不会再有
     * {@code onSurfaceTextureAvailable} 回调，Surface 也就永远交不出去 ——
     * 表现是"切一次模式之后预览再也不出画面"。
     */
    private void applyPreviewMode(PreviewMode mode) {
        previewMode = mode;
        if (preview == null) {
            // 首开自动分支不 setContentView，界面元素全为 null。
            return;
        }
        boolean fallback = mode == PreviewMode.FALLBACK;
        preview.setVisibility(fallback ? View.GONE : View.VISIBLE);
        previewFallback.setVisibility(fallback ? View.VISIBLE : View.GONE);
        if (!fallback && previewSurface == null && preview.isAvailable()) {
            attachPreviewSurface(preview.getSurfaceTexture());
        }
    }

    /**
     * 把仪表屏的画面接到这块 Surface 上（直通路径）。
     *
     * <p>特权进程那边是 {@code createDisplay → setDisplayLayerStack(仪表屏的 layerStack)
     * → setDisplaySurface(这块 Surface)}：SurfaceFlinger 直接往本视图的 BufferQueue 出帧，
     * 所以 App 侧没有抓帧、没有编解码、没有回读。
     */
    private void attachPreviewSurface(SurfaceTexture texture) {
        releasePreviewSurface();
        previewSurface = new Surface(texture);
        Log.i(TAG, "预览 Surface 已创建，模式=" + previewMode);
        if (previewMode == PreviewMode.PASSTHROUGH) {
            sendPreviewSurface();
        }
    }

    /**
     * 把 {@link #previewSurface} 交给特权进程。只有**真的接不上**才降级。
     *
     * <p>"通道还没就绪"不算失败：SurfaceTexture 通常比特权进程先好，
     * 那种情况什么都不做，等 {@code onReady} 里补交一次。
     */
    private void sendPreviewSurface() {
        Surface surface = previewSurface;
        if (surface == null || previewMode != PreviewMode.PASSTHROUGH || privileged == null) {
            return;
        }
        if (previewBound) {
            // 同一块 Surface 已经交过了。重复交会让对端 destroyDisplay 再 createDisplay，
            // 白闪一下 —— 而 onReady 与 onSurfaceTextureAvailable 的先后顺序是不确定的，
            // 两条路都会走到这里。
            return;
        }
        if (!privileged.isReady()) {
            Log.i(TAG, "预览 Surface 已备好，等特权通道就绪后再交");
            return;
        }
        if (privileged.setPreviewSurface(surface)) {
            previewBound = true;
            Log.i(TAG, "仪表屏画面已接到预览 Surface");
            return;
        }
        // 通道在、但接不上（跨进程传 Surface 被拒 / 对端已死）—— 退回抓帧。
        Log.w(TAG, "直通预览接屏失败，转抓帧降级");
        enterFallback(getString(R.string.preview_fallback_no_surface));
    }

    /** 通知特权进程松开画面，并放掉本地 Surface 引用。 */
    private void detachPreviewSurface() {
        previewBound = false;
        if (privileged != null) {
            privileged.clearPreviewSurface();
        }
        releasePreviewSurface();
    }

    /** 只放掉本地引用，不动特权进程。 */
    private void releasePreviewSurface() {
        Surface surface = previewSurface;
        previewSurface = null;
        if (surface != null) {
            surface.release();
        }
    }

    /**
     * 转降级路径：换成 ImageView + screencap 抓帧。
     *
     * <p>为什么值得留这条路：直通依赖"跨进程把 Surface 交给 uid 2000 进程"，其中
     * 任何一环被系统拒绝就只剩黑屏；抓帧只依赖 shell 通道，独立得多。
     * 3 fps 难用，但用户的原话是"能在仪表盘上可靠地看到画面最重要"。
     */
    private void enterFallback(String reason) {
        if (previewMode == PreviewMode.FALLBACK || !previewStarted) {
            return;
        }
        Log.w(TAG, "预览降级为抓帧：" + reason);
        fallbackReason = reason;
        detachPreviewSurface();
        applyPreviewMode(PreviewMode.FALLBACK);
        applyPreviewVisuals(false);
        startFallbackPreview();
        refreshStatus();
    }

    /**
     * 条件齐了就开预览：shell 通道已连、仪表盘屏已定位、界面可见。
     *
     * <p>先试**直通**（原版 Just Dashboard 的做法：SurfaceControl 复用仪表屏的 layerStack，
     * 再把输出接到本界面的 Surface），起不来才退到抓帧降级。
     *
     * <p>两条路预览的都必须是 {@link InjectClient#inputDisplay()} 那块屏 ——
     * "看到的"和"点到的"是同一块屏，这正是预览存在的意义。
     */
    private void startPreviewIfReady() {
        if (previewStarted || preview == null || !injector.isAttached() || !session.isActive()) {
            return;
        }
        previewStarted = true;
        previewBound = false;
        fallbackReason = "";
        applyPreviewMode(PreviewMode.PASSTHROUGH);
        applyPreviewVisuals(false);
        // **必须是主投影屏（display 2），不是投屏槽位**：应用投到槽位，
        // 容器服务再把槽位的 layerStack 镜像到主投影屏上才看得见。
        // 复用槽位的 layerStack 只会拿到全黑（实测非黑像素 0%）。
        // 这也正是 InjectClient#inputDisplay() 指向的那块屏 —— 看到的与点到的同一块。
        int previewDisplay = session.projectionDisplayId();
        Log.i(TAG, "预览目标屏 display=" + previewDisplay
                + "（投屏槽位=" + session.displayId() + "）");
        privileged.start(previewDisplay, new PrivilegedClient.Listener() {
            @Override
            public void onReady() {
                if (!previewStarted) {
                    // 等的时候就绪太晚了：界面已经 pause（用户抢回了控制权），
                    // 这时把画面接上去等于后台占着仪表屏。
                    return;
                }
                // SurfaceTexture 通常早就绪了（onReady 比 onSurfaceTextureAvailable 晚），
                // 这里补交一次；已经交过的话 sendPreviewSurface 是幂等的。
                sendPreviewSurface();
                refreshStatus();
            }

            @Override
            public void onFailed(String reason) {
                if (!previewStarted) {
                    return;
                }
                enterFallback(reason);
            }
        });
    }

    /**
     * 降级路径的抓帧循环：多条通道并发抓、谁先抓到谁上屏。
     *
     * <p>抓的屏取自 {@link InjectClient#inputDisplay()}，与触控注入的屏完全一致。
     */
    private void startFallbackPreview() {
        if (previewFallback == null || !injector.isAttached()) {
            // 连 shell 通道都没有，降级也无从谈起。松掉"已启动"，
            // 让下一次 onResume 还能再试一次。
            previewStarted = false;
            setStatus(getString(R.string.preview_failed) + fallbackReason);
            return;
        }
        injector.startPreview(new InjectClient.FrameCallback() {
            @Override
            public void onFrame(final byte[] png) {
                // 多条通道并发抓帧，帧会来得比界面画得快。**忙就直接丢掉这一帧**：
                // 排队只会让屏幕上的画面越来越旧，宁可掉帧也要保证"看到的是最近的"。
                if (!previewFrameBusy.compareAndSet(false, true)) {
                    return;
                }
                // 这里在**预览线程**上。PNG 解码是纯 CPU 活（1920x720 约几十毫秒），
                // 放到 UI 线程会直接把界面卡住，所以就地解完、只把 Bitmap 递过去。
                Bitmap frame = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (frame == null) {
                    previewFrameBusy.set(false);
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        previewFallback.setImageBitmap(frame);
                        if (!previewing) {
                            // 只在"从没有画面"变成"有画面"时刷一次状态行，
                            // 否则每帧都刷等于每秒重排好几次状态栏。
                            applyPreviewVisuals(true);
                            refreshStatus();
                        }
                        previewFrameBusy.set(false);
                    }
                });
            }

            @Override
            public void onFailed(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 松掉"已启动"，让下一次 onResume 或投屏完成还能再试一次。
                        previewStarted = false;
                        applyPreviewVisuals(false);
                        setStatus(getString(R.string.preview_failed) + message);
                        refreshStatus();
                    }
                });
            }
        });
    }

    /**
     * 心跳巡检：直通画面接好后对端若退出，画面会**静止在最后一帧** ——
     * 看起来像画面卡住而不是断线，用户会以为界面坏了。所以这里发现断了就转降级，
     * 至少给出一张会动的画面，并把原因写到状态行。
     */
    private void checkPreviewAlive() {
        if (previewMode != PreviewMode.PASSTHROUGH || !previewBound || privileged == null) {
            return;
        }
        if (privileged.isReady()) {
            return;
        }
        Log.w(TAG, "特权通道已断，转抓帧降级");
        // 心跳跑在自己的线程上，而这条路径要动视图（换预览视图、刷状态行），
        // 必须切回主线程 —— 非 UI 线程 setText 会抛 CalledFromWrongThreadException。
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                enterFallback(getString(R.string.preview_fallback_priv_exited));
            }
        });
    }

    /**
     * 停掉预览、复位标志位并**断开预览连接**。界面不可见、或要重开时必须先走这里。
     *
     * <p>顺序有讲究：先断画面（让特权进程 destroyDisplay）再停进程。反过来的话
     * 画面先断、display 还挂着，中间那一瞬是黑屏。
     */
    private void stopPreviewIfRunning() {
        previewStarted = false;
        previewBound = false;
        // 门闩也要复位：否则"上一帧还没上屏就被停掉"会把下一轮降级预览永久锁死。
        previewFrameBusy.set(false);
        detachPreviewSurface();
        injector.stopPreview();
        if (privileged != null) {
            // 无条件停：App 被系统杀掉时走不到这里，上一次会话可能留下一个
            // 仍占着 display 的 uid 2000 进程，必须每次收尾都清掉。
            privileged.stop();
        }
        fallbackReason = "";
        applyPreviewMode(PreviewMode.IDLE);
        applyPreviewVisuals(false);
    }

    // ---- 触摸转发 ----------------------------------------------------------

    /**
     * 把一次触摸送到仪表屏。**只在 {@link #touchExecutor} 单线程上调用**，
     * 顺序由那条线保证。
     *
     * <p>优先走特权通道：一次 Binder oneway 调用（微秒级），拖拽时每秒几十个 MOVE
     * 也跟得上。特权通道不可用时退回 shell 的 {@code input motionevent} ——
     * 慢（本车实测 40~140 ms/次）但语义相同，属于"能操作，只是慢"。
     */
    private void forwardTouch(int action, float x, float y, long downTime, long eventTime) {
        if (privileged != null && privileged.touch(action, x, y, downTime, eventTime)) {
            return;
        }
        injector.touch(action, x, y, downTime, eventTime);
    }

    /**
     * 点一下仪表屏。给"补点闭环"用，跑在脚本线程上。
     *
     * <p>优先特权通道；降级路径走 {@code input tap}（合成完整手势），
     * 与 {@link #forwardTouch} 的逐事件注入是两套语义，所以分开写而不是共用。
     */
    private void tapOnCluster(float x, float y) {
        if (privileged != null && privileged.tap(x, y)) {
            return;
        }
        injector.tap(x, y);
    }

    // ---- 生命周期 ----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        // 心跳线程按需起：onDestroy 会把它收掉，重建界面时这里再拉起来。
        if (heartbeatHandler == null) {
            heartbeatThread = new HandlerThread("dashcast-heartbeat");
            heartbeatThread.start();
            heartbeatHandler = new Handler(heartbeatThread.getLooper());
        }
        heartbeatHandler.removeCallbacks(heartbeat);
        heartbeatHandler.post(heartbeat);
        // 镜像跟着可见性走：前台才建，后台就拆，与心跳的生命周期保持一致，
        // 否则会出现"心跳停了→代理以为客户端走了→重建镜像"的循环。
        startPreviewIfReady();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacks(heartbeat);
        }
        // 界面不再可见就松开看门 —— 这是"用户随时能夺回控制权"的第一道保证。
        // 用户在主屏点该应用图标、或从最近任务拉它时，本界面必然 pause；
        // 看门必须在那一刻松手，否则会把任务又搬回副屏，表现为"按了回不到前台"。
        // 第二道保证是 InjectClient 里看门窗口的硬上限（绝不允许无限期钉住）。
        stopWatching();
        stopPreviewIfRunning();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 看门完全由界面掌握：界面没了就没人能松开它，所以这里一并撤销。
        stopWatching();
        stopPreviewIfRunning();
        // 心跳线程不主动收会一直挂着，连带它的 Looper 和 ADB 连接。
        if (heartbeatThread != null) {
            heartbeatThread.quitSafely();
            heartbeatThread = null;
        }
        heartbeatHandler = null;
        touchExecutor.shutdown();
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

    /** 状态行是唯一的用户可见反馈，任何一处变化都要走这里。 */
    private void refreshStatus() {
        if (status == null) {
            return;
        }
        if (!session.isActive()) {
            setStatus(getString(R.string.status_no_display));
            return;
        }
        String text = getString(R.string.status_ready) + " · display " + session.displayId();
        if (previewMode == PreviewMode.FALLBACK) {
            // 降级**必须让用户看见**：3 fps 与满帧是两种体验，不说清楚用户只会以为程序坏了。
            text += " · " + getString(R.string.preview_fallback)
                    + (fallbackReason.isEmpty() ? "" : "（" + fallbackReason + "）");
        } else if (previewing) {
            text += " · 预览中";
        }
        if (watchedPackage != null) {
            // 代理的备注（看门中 / 已搬回 N 次）优先；还没取到就显示看门中。
            text += " · " + (watchState == null || watchState.note.isEmpty()
                    ? "看门中" : watchState.note);
        }
        if (!injector.isAttached()) {
            // 「正在拉起」和「拉起失败」是两种不同的用户可见状态，不能都糊成"未连接"：
            // 前者用户该等，后者用户该去看引导页。见 §用户可见产品。
            text += "（" + getString(agentBringUpRunning
                    ? R.string.agent_starting : R.string.agent_offline) + "）";
        }
        setStatus(text);
    }

    // ---- 动作 --------------------------------------------------------------

    private void sendKey(int keyCode) {
        if (!session.isActive()) {
            setStatus(getString(R.string.status_no_display));
            return;
        }
        injector.key(keyCode);
    }

    private void setStatus(String text) {
        Log.i(TAG, "状态：" + text);
        if (status == null) {
            // 首开自动分支没有状态栏，日志就是唯一的落点。
            return;
        }
        status.setText(text);
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

            TextView label = (TextView) row.findViewById(R.id.rowLabel);
            label.setText(entry.label);

            TextView star = (TextView) row.findViewById(R.id.rowStar);
            boolean marked = favoritePackages.contains(entry.packageName);
            star.setText(marked ? R.string.star_on : R.string.star_off);
            star.setTextColor(marked ? 0xFFFFC107 : 0x66FFFFFF);
            star.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggleFavorite(entry);
                }
            });
            return row;
        }
    }
}
