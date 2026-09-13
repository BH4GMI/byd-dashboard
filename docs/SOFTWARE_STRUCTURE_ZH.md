# dashcast 软件结构技术文档

> **读者**：二次开发本工程的人。
> **范围**：模块划分、进程与信任边界、跨进程契约、两侧内部流程、构建链、扩展点。
> **锚点约定**：`文件:行号` 对应本仓库当前 HEAD 的源码树。
> 「未确认」= 源码里找不到依据，不做推测。

---

## 1. 交付形态

一套两件、共两个进程：

| 件 | 产物 | 装在哪 | 进程身份 |
| --- | --- | --- | --- |
| 应用 | `apk/dashcast.apk`（`com.byd.dashcast`） | 车机普通安装 | `untrusted_app`，uid 10xxx |
| 代理 | `dashcast-agent.jar`（`classes.dex`） | `/data/local/tmp/` | `shell`，uid 2000 |

代理 jar 由 `agent/build.ps1` 编译，在 `apk/build.ps1:32-43` 被拷进 `apk/assets/` 随 APK 分发。
运行期由 App 自己用 shell 通道写到 `/data/local/tmp/dashcast-agent.jar`（`AgentLauncher.java:24`）。

APK 清单极简，只有两个 Activity、一个 Receiver、两个权限（`apk/AndroidManifest.xml:12-14,21-53`）：
`INTERNET`（回环 TCP 也受 paranoid networking 约束）与 `RECEIVE_BOOT_COMPLETED`。

---

## 2. 为什么必须是两个进程

这是整个工程唯一不可绕过的约束，也是读代码前必须先建立的模型。

- 把应用投到仪表屏要用 `am start-activity --display N`，或 `RootTask` 搬迁；把触摸送进副屏要用
  `InputManager.injectInputEvent()`，后者需要 `android.permission.INJECT_EVENTS`，
  它是 `signature|privileged`，普通应用拿不到（`Agent.java:42-49`）。
- uid 2000（`com.android.shell`）在 DiLink 5 上持有该权限（`Agent.java:46-47`）。
- 其余路径都不通：`AccessibilityService.dispatchGesture` 无法指定 `displayId`；
  `UiAutomation` 只对 instrumentation 开放；`input` shell 命令本身也要 uid 2000
  （`Agent.java:48-49`）。

**代理刻意做小**（`Agent.java:51-53`）：它不建窗口、不建 `VirtualDisplay`、不碰 `ContentResolver`。
那些由已安装 App 在自己的真实进程里做（字体、provider、`ProcessRecord` 齐全）。
代理只做四件事：坐标注入、用 `am` 把应用送上副屏、接管显示做镜像、把跑掉的任务搬回副屏。

---

## 3. 仓库模块地图

```
apk/          车机端应用（运行期主体）
  src/com/byd/dashcast/       界面、会话、镜像、账号、目标
  src/com/byd/dashcast/adb/   ADB 自举：线协议、密钥、代理上传
  assets/                     构建期填入代理 jar 与可选 ADB 私钥（不入库）
  res/                        布局、字符串
  build.ps1                   aapt2 → javac → d8 → aapt add → zipalign → apksigner
agent/        uid-2000 代理（唯一特权部分）
  src/com/byd/dashcast/agent/Agent.java
  build.ps1 / run.ps1         编译为 dex 装进 jar；run.ps1 是 PC 侧的启停/日志工具
tools/        dashcast.ps1 —— 纯 adb 命令版驱动，不安装任何软件
```

---

## 4. 进程与信任边界

### 4.1 信任锚

代理只服务**与锚点同签名**的应用，而不是只服务锚点本身
（`Agent.java:84-91`，`ANCHOR_PACKAGE = "com.byd.dashcast"`）：

- 启动时枚举已安装包，取签名与锚点一致的那些（`Agent.java:233-260`），
  由包名集合推出信任 uid 集合（`Agent.java:286-301`）。
- 每次事务入口做 `Binder.getCallingUid()` 检查，不在集合里直接 `return false`
  （`Agent.java:317-321`）。
- 解析失败时退化成只信任锚点本身（`Agent.java:256-258`）——宁可少服务，不可多服务。

**这条设计的直接后果**：派生的共存版（不同包名、同一个签名）只要与主工程同签名，
装上即被信任，不需要改代理、不需要重启代理（`Agent.java:86-89`）。换签名就等于换信任域。

### 4.2 为什么是 Binder 而不是 LocalSocket

原版走的就是这条路（`Agent.java:55-57` 引用 `b.smali:2403-2460` 的
`sendBroadcast(intent.putExtra(IBinder))`）。理由：`LocalSocket` 从 `untrusted_app` 连
`shell` 域是 SELinux 敏感路径，而 Binder 由内核直接裁决，且能顺带用
`Binder.getCallingUid()` 把调用方钉死在本应用（`Agent.java:55-59`）。

### 4.3 方向性

两个进程之间**只有代理 → App 的广播**和**App → 代理的 Binder 调用**两个方向。
代理不主动调 App 的任何东西；它需要的 `Context` 来自 `ActivityThread.systemMain()`
（`Agent.java:163-170`）。

---

## 5. 跨进程契约

**这是改代码时唯一必须两侧同时改、同时安装的部分。**

### 5.1 广播：句柄分发

| 项 | 值 | 证据 |
| --- | --- | --- |
| action | `com.byd.dashcast.action.AGENT_READY` | `Agent.java:67` · `CastActivity.java:69` |
| extra key | `com.byd.dashcast.extra.AGENT_BINDER`（`Bundle.putBinder`） | `Agent.java:68` · `CastActivity.java:70` |

- 代理**逐个点名**发给每个信任客户端（`intent.setPackage(client)`），不用隐式广播
  （`Agent.java:209-224`）。
- 心跳：主循环里客户端静默超过 `CONTACT_TIMEOUT_MS = 5000` 就重播一次
  （`Agent.java:98,187-192`），所以不依赖"谁先启动"。
- 客户端收到后调 `attach(binder)`：发一次同步 `TRANSACT_PING`，回执里带回代理 uid、
  目标 display、看门状态，成功才把句柄留下（`InjectClient.java:90-117`）。
  同一句柄重复送达直接忽略（`InjectClient.java:91-93`）。

### 5.2 Binder 事务表

descriptor 是 `com.byd.dashcast.agent.AgentBinder`（`Agent.java:69` · `InjectClient.java:28`）。
代理侧是手写 `onTransact`（`Agent.java:305-457`），没有 AIDL，也没有生成代码。
每个 case 前有 `data.enforceInterface(DESCRIPTOR)`（`Agent.java:323`）。

| code | 常量 | 常量行 | 入参（Parcel 顺序） | 回执 | 处理体 |
| --- | --- | --- | --- | --- | --- |
| +0 | `TRANSACT_PING` | `Agent.java:71` | — | `int uid`、`int displayId`、`int watching`、`int moves`、`String note`、`String watchedPackage` | `Agent.java:326-339` |
| +1 | `TRANSACT_SET_DISPLAY` | `:72` | `int displayId` | — | `:340-347` |
| +2 | `TRANSACT_TOUCH` | `:73` | `int action`、`float x`、`float y`、`long downTime`、`long eventTime` | — | `:348-359` |
| +3 | `TRANSACT_KEY` | `:74` | `int keyCode` | — | `:360-366` |
| +4 | `TRANSACT_LAUNCH` | `:75` | `int display`、`String pkg`、`String activity` | `int 退出码`、`String 输出` | `:367-378` |
| +5 | `TRANSACT_LIST_APPS` | `:76` | — | `StringList`，每 3 个一组 `label,pkg,activity` | `:379-385` |
| +6 | `TRANSACT_START_MIRROR` | `:77` | `Surface` | `String error`（`null` = 成功） | `:406-414` |
| +7 | `TRANSACT_STOP_MIRROR` | `:78` | — | — | `:415-421` |
| +8 | `TRANSACT_WATCH` | `:79` | `String pkg`、`int display` | — | `:422-438` |
| +9 | `TRANSACT_UNWATCH` | `:80` | — | — | `:440-452` |
| +10 | `TRANSACT_TASK_DISPLAY` | `:81` | `String pkg` | `int display`（无任务为负） | `:386-394` |
| +11 | `TRANSACT_MOVE_TO_DISPLAY` | `:82` | `String pkg`、`int target` | `int 当前屏位`、`int 搬动数` | `:395-405` |

客户端常量镜像在 `InjectClient.java:30-41`（`FIRST_CALL_TRANSACTION + n`，逐条对齐）。

### 5.3 oneway 与同步的取舍

客户端有两条发送路径，选哪条是刻意的：

- **fire-and-forget**：`transact(code, data)` 走 `IBinder.FLAG_ONEWAY`，没有回执
  （`InjectClient.java:454-465`）。`SET_DISPLAY` / `TOUCH` / `KEY` 用它。
  触摸 240Hz，UI 线程发完即走，不需要写线程和队列（`InjectClient.java:22-23`）。
- **同步阻塞**：自己 `transact(..., 0)` 并 `reply.readException()`。
  `PING`（`attach`，`:98`）、`LAUNCH`（`:264-304`）、`LIST_APPS`（`:359-393`）、
  `START_MIRROR`（`:402-431`）、`STOP_MIRROR`（`:433-452`）用它。
  `STOP_MIRROR` 必须同步的理由写在 `InjectClient.java:442-443`：oneway 在进程即将退出时
  可能来不及送达，镜像显示就会留在 SurfaceFlinger 里泄漏到重启。

`TOUCH` 不带 display：代理用自己 `SET_DISPLAY` 记下的那块屏，所以调用前必须先 `setDisplay`
（`InjectClient.java:336-337`）。`tap()` 就是 DOWN/UP 一对，间隔 `TAP_DURATION_MS = 50`
（`:44,339-343`）。

### 5.4 契约升级规则

- 改任何方法签名、字段顺序或 code 编号，**必须同时改 `Agent.java` 与 `InjectClient.java`
  并同时安装**。`PING` 的回执已经承载看门状态，注释明确写了"App 与代理必须成对升级"
  （`Agent.java:331`）。
- 旧代理 + 新 App 的症状是字段错位读出来的垃圾值，不会报错。这是本工程最容易踩的坑。

---

## 6. App 侧结构

### 6.1 类职责

| 类 | 职责 | 关键锚点 |
| --- | --- | --- |
| `CastActivity` | 主界面 + 全部编排：投屏、镜像预览、触控转发、一键启动、看门状态显示 | `CastActivity.java:42-58`，一键启动 `:337-433` |
| `GuideActivity` | ADB 授权引导页，兼"快速通道"：已就绪时几百毫秒内直接转走 | `GuideActivity.java:21-35`，`onCreate:50-55` |
| `InjectClient` | 与代理通信的客户端（唯一 IPC 出口） | `InjectClient.java:14-24` |
| `AgentLauncher` | 上传代理 jar 并拉起进程 | `AgentLauncher.java:10-18` |
| `AdbBootstrap` | 把"载密钥 → 连 adbd → 拿 shell → 拉起代理"收成一个入口，引导页与开机接收器共用 | `AdbBootstrap.java:6-9` |
| `AdbClient` | ADB 线协议客户端（CNXN/AUTH/OPEN/WRTE/CLSE + shell v2 分帧） | `AdbClient.java:59-67,252-281` |
| `AdbKeyStore` | ADB 身份密钥的生成、落盘、指纹、公钥 blob 编码 | `AdbKeyStore.java:45-49,281-315` |
| `BootReceiver` | 开机拉起代理，按固定退避重试 | `BootReceiver.java:12-22,37,60-88` |
| `DashboardSession` | 解析仪表屏 displayId | `DashboardSession.java:32,45,55-80` |
| `QuickLaunch` | 「一键启动」目标的持久化（单选） | `QuickLaunch.java:46,58,64,74` |
| `AppRepo` | 可投屏应用条目（`label`/`pkg`/`activity`） | `AppRepo.java:3-11` |
| `Favorites` | 收藏包名集合 | `Favorites.java:10-15,38-48` |

### 6.2 启动流程

```
BOOT_COMPLETED / QUICKBOOT_POWERON
  → BootReceiver.onReceive            BootReceiver.java:39-58
      goAsync + 后台线程 "dashcast-boot"
      → AdbBootstrap.provision(..., TIMEOUT_BACKGROUND_MS=8000, false)
                                       AdbBootstrap.java:18,48-91
          失败且非 NEED_AUTHORIZATION → AlarmManager 退避重试
                                       BootReceiver.java:37,80-105
          已授权但连不上             → 不重试，交引导页
                                       BootReceiver.java:73-78

用户点图标
  → CastActivity（LAUNCHER）          AndroidManifest.xml:21-31
```

`GuideActivity` 是"已授权就看不见"的页，触发条件是**能力探测**而不是"首次运行"标记
（`GuideActivity.java:29-34`）：万一授权丢了（恢复出厂、手动撤销），引导会自动回来。
快速通道用 `FAST_PROBE_MS = 3000` 做一次极短探测（`:41,59`），就绪时经
`EXTRA_NEXT = com.byd.dashcast.extra.NEXT_ACTIVITY`（`:39`）转走。

`CastActivity.onCreate`（`:161`）的执行序列：`bindUi`（`:279`）→ `prepareSession`（`:293`）
→ `ensureAgent`（`:312`）。`ensureAgent` 在后台跑 `AdbBootstrap.provision`，成功后
`prepareSession` → `requestApps`（`:538`）向代理要清单（App 自己查不到，见 §7.5）。

### 6.3 镜像与触控

- 预览区尺寸与仪表屏一致（1920×720），所以触控板的 View 局部坐标就是仪表屏坐标
  （`CastActivity.java:55`）。
- `CastActivity` 实现 `TextureView.SurfaceTextureListener`（`:64`），
  `onSurfaceTextureAvailable`（`:590`）后 `startMirrorIfReady`（`:626`）把该 `Surface`
  交给代理接管；`onPause`（`:661`）主动 `stopMirror`（`:669` 的 `onDestroy` 亦然）。
- 触摸转发：`onTouch` 分支把事件转成 `InjectClient.touch`，但**列表开着时不转发**，
  防止误触（`:53`）。
- 键注入走 `sendKey`（`:1042`）。

### 6.4 一键启动

目标由用户在列表里选，存在 SharedPreferences 里（`QuickLaunch`），不在资源里写死。**单选**。

- **选择入口在列表行上，不在按钮上**：`item_app.xml` 的每行右侧有一个 `rowQuick`
  文本选择项（`○ 一键启动` / `● 一键启动`），**只在收藏项上可见**（非收藏项 `View.GONE`），
  点它即选中；再点已选中的那个即取消（`selectQuickTarget:357`）。
  取消收藏时会顺带清掉目标（`toggleFavorite:510`）——否则会留下一个界面上看不见、
  却还在生效的选择。
- **按下「一键启动」**：`onQuickButton:345` → `runQuickCast:381` 把目标投到仪表屏。
  **只投屏**：不补点、不判页、不代替目标应用做任何操作。
  启动前先开看门（见 §7.4）——`am start-activity --display N` 会让整条 root task
  先跳到主屏、再由代理搬回，不留看门这次投屏当场就丢。
- **没选过目标按下按钮**：只提示「还没有选一键启动的应用」，不改任何状态
  （`onQuickButton:345`）。
- **目标已有任务时不重新启动**：`injector.taskDisplay` 拿到屏位 ≥ 0 就改用
  `moveToDisplay` 把它搬过去。因为 `am start-activity --display N` 在目标屏上没有该包
  任务时会新建一条 root task，同一个应用会在两块屏上各跑一份、各有各的页面和动画。
  屏位为 -1（没有任务）或 -2（原语不可用）都走启动。
- 按钮文案：选过目标显示「一键启动：应用名」，没选过只显示「一键启动」
  （`applyQuickLabel:337`）。
- **「退出管理」= 把最近投上去的那个应用搬回主屏前台，然后关闭本界面**
  （`exitManagement:705`）。移动任务需要 uid 2000，走代理的 `moveRootTaskToDisplay`
  （`InjectClient.moveToDisplay`）——它把任务移到目标屏并置顶，所以搬完就是前台。
  搬不动也照样关界面。记住"最近投上去的那个"用界面字段 `lastCastPackage:91`，
  不能用看门状态：看门在投放稳定后会自动松开。
- 与「关闭软件」（返回键 / 被系统杀掉）的区别：后者只撤看门、**不动**已投上去的应用，
  它继续留在仪表屏上（`onPause` / `onDestroy` → `stopWatching:687`）。

### 6.5 仪表屏定位

- 仪表屏是**车机自带**的投影屏，不自建：`Display 2 = fission_bg_XDJAScreenProjection`，
  `FLAG_PRESENTATION`，1920×720，density 320，owner `com.byd.containerservice`
  （`DashboardSession.java:11-19`）。
- 不要自建 `VirtualDisplay`：那样应用根本不会上仪表盘
  （`DashboardSession.java:20-22`）。那是把"投屏"理解成了"造一块屏"。
- 解析策略：先按 `Display.getName()` 命中名字；枚举不到就用常量
  `CLUSTER_DISPLAY_ID = 2`（`DashboardSession.java:32,45,55-80`）。
  为什么不能只靠枚举：车机对该投影屏做了可见性过滤，应用侧只返回 display 3/4 两个
  shared 变体（`:40-43`）。
- 该屏 `touch NONE`（`tools/dashcast.ps1:19`），所以输入必须注入。

### 6.6 状态与持久化

| 位置 | 内容 | 锚点 |
| --- | --- | --- |
| `SharedPreferences "dashcast"` | `quick_package` / `quick_activity` / `quick_label`（一键启动目标） | `QuickLaunch.java:19-21,46,64,74` |
| `SharedPreferences "dashcast"` | `favorite_packages`（`StringSet`，包名） | `Favorites.java:18-19` |
| `SharedPreferences "dashcast_boot"` | `attempt`（开机重试计数） | `BootReceiver.java:30-31` |
| 私有文件 `files/adb_identity` | ADB 身份（私钥 + 公钥），原子写 | `AdbKeyStore.java:45,281-315` |

设计取舍：收藏与一键目标都**存包名不存 label**（label 随语言/版本变，包名稳定）
（`Favorites.java:13-14`、`QuickLaunch.java:9-13`）。

---

## 7. Agent 侧结构

### 7.1 启动与主循环

`main`（`Agent.java:159`）：

1. `bypassHiddenApi()`（`Agent.java:969`，实现未逐行核读）——它全程用反射调 `@hide` API，
   必须绕开隐藏 API 限制。
2. `Looper.prepareMainLooper()` + `ActivityThread.systemMain()` 拿到 `Context`
   （`Agent.java:163-170`）。注意本进程不是 zygote 孵化的应用进程，这些都得自己做。
3. 解析信任包与 uid（`:172-178`），初始化三组原语（`:180-182`）。
4. 进主循环（`:187-206`），节拍 `WATCH_INTERVAL_MS = 1000`：

```
每 1 秒：
  客户端静默 > 5s        → 重播 Binder（:190-192）
  镜像建立且静默 > 60s   → 回收镜像显示（:193-196，MIRROR_IDLE_TIMEOUT_MS:121）
  非持久看门且静默 > 60s → 停止看门（:199-203）
  checkWatch(now)        → 看门巡检（:204）
```

看门巡检挂在同一个节拍上，不额外引入线程或定时器（`:99-103`）。
1 秒是"点视频后多久回到仪表屏"的直接延迟（`:101`）。

### 7.2 三组反射原语

| 组 | 目的 | 初始化 | 关键 API |
| --- | --- | --- | --- |
| 输入 | 注入触摸/按键 | `initInput:461-477` | `InputManager.getInstance`、`injectInputEvent`、`MotionEvent/KeyEvent.setDisplayId`（`@hide`） |
| 镜像 | 接管一块屏做镜像 | `initMirrorPrimitives:615-634` | `SurfaceControl.createDisplay/destroyDisplay/openTransaction/setDisplaySurface/setDisplaySize/setDisplayProjection/setDisplayLayerStack` |
| 任务 | 查/搬 root task | `initTaskPrimitives:773` | `IActivityManager.getAllRootTaskInfos`、`IActivityTaskManager.moveRootTaskToDisplay`（`Agent.java:760-765`，已在 `framework.jar` 上核实） |

任务组有一个已记录在案的坑（`Agent.java:767-771`）：`RootTaskInfo` 自身**没有**
`taskId`/`topActivity`/`displayId`，它们声明在父类 `android.app.TaskInfo` 上，
读字段必须沿父类链走。

### 7.3 投屏：`launch` 与 `moveTasksOf`

- `launch(target, pkg, activity)`（`Agent.java:722-745`）执行
  `am start-activity --display <target> -n <pkg>/<activity>`，`redirectErrorStream`，
  返回 `{退出码, 输出}`。
- `moveTasksOf(pkg, target)`（`Agent.java:912`）把该包**所有** root task 搬到目标屏。
  为什么不用 `am start` 代替：`am start-activity --display N` 在目标屏上没有该包任务时
  会**新建**一条 root task，同一个应用在两块屏上各跑一份（`Agent.java:905-908`）。
  看门巡检复用这个方法，所以看门天然管住重复任务。
- 返回 `{当前屏位, 搬动数}`：没有任务为 `-1`，原语缺失为 `-2`（`Agent.java:910`）。

### 7.4 看门状态机

存在理由（`Agent.java:749-758`）：应用**自己**发起的 Activity 启动不带 display，
AMS 会判定缓存的 display 失效并把整条 root task 挪到默认屏，投屏当场丢失。
这是应用侧行为，本程序无法预防——只能事后搬回。

```
WATCH_SETTLE_MS = 3000    搬完后的静默期，防止与应用自己搬走打成每秒一次的拉锯
```
（`Agent.java:105-109`）

`checkWatch`（`:819-846`）每拍：静默期内直接返回 → `moveTasksOf` →
搬动了就计数、进静默期 → 没搬动就返回。「目标当前不存在」也是正常状态：
目标没启动时看门就是空转，不该报失败。

### 7.5 应用枚举

`listApps(context)`（`Agent.java:532`）：代理是 uid 2000，**不受包可见性过滤**。
App 侧查不到的原因写在 `AppRepo.java:3-9`：Android 11 起的包可见性过滤按 uid 生效，
App 侧只能看到其中一小部分，而 uid 2000 不受该过滤约束。
回执把 `label,pkg,activity` 三元组扁平化成一个 `StringList`（`InjectClient.java:375-380`）。

### 7.6 镜像

`startMirror(surface)`（`Agent.java:637-678`）：

1. 读目标 display 的 `DisplayInfo`（`logicalWidth`/`logicalHeight`/`layerStack`），
   `DisplayInfo` 与字段都是 `@hide`，只能反射（`:694-697`）。
2. `SurfaceControl.createDisplay("dashcast-mirror", false)` 建一块**傀儡**屏，
   在一个 transaction 里设 surface / size / projection / layerStack（`:659-669`）。
3. 失败返回给用户看的字符串，成功返回 `null`（`:636`）。

`stopMirror`（`:680-692`）`destroyDisplay`。客户端失联 60 秒也会自动回收（`:193-196`），
不能设短：App 切后台心跳就停，10 秒会误杀正常后台的镜像（`:117-119`）。

---

## 8. 提权链：uid 2000 从哪来

这是"不 root、不刷机、不需要电脑"的全部含金量所在。

### 8.1 线协议客户端

`AdbClient` 连 `127.0.0.1:5555`（`AdbClient.java:77,136`），
24 字节小端消息头 + `CNXN`/`AUTH`/`OPEN`/`OKAY`/`WRTE`/`CLSE`
（`AdbClient.java:59-66`），`A_VERSION = 0x01000001`，`MAX_PAYLOAD = 4096`（`:66-67`）。

握手（`:150-193`）：发 `CNXN` 带 `"host::\0"` → 若收到 `AUTH arg0=1`（20 字节 token），
先用私钥签 `sha1DigestInfo(token)` 回 `AUTH arg0=2`；若还收 `AUTH arg0=1`，
再发 `AUTH arg0=3` 带公钥 blob（`:172-193`）。收到 `CNXN` 即握手完成。
`State` 四态（`:80-89`）供调用方决定是否弹授权引导。

签名必须自己做 PKCS#1 v1.5，不能用 `SHA1withRSA`，因为 adbd 收到的 token **本身就是摘要**
（`AdbClient.sha1DigestInfo:233` 手工构造 DigestInfo，而不是把 token 再哈希一次）。

### 8.2 shell 服务与 v2 分帧

服务名是 `shell,v2,raw:`（`AdbClient.java:281`），**不是**裸 `shell:`
（`shellServiceName:287`）。理由：`shell:` 会分配 pty，adbd 在会话回收时连带杀掉后台进程；
`setsid` 也必须配合它用（`Agent.java`/`AgentLauncher.java:91-93`）。

v2 帧格式：`[id:1][length:4 LE][payload]`，帧头 5 字节
（`AdbClient.java:252`，`SHELL_HEADER_BYTES = 5`），id 含义
`1=stdout`、`2=stderr`、`3=exit`（`:255-259`），且**一帧会横跨两条 WRTE**。
按 1 字节帧头解析会出问题：症状是"同一条连接上第二条命令起静默失效、大输出被截断"
（`AgentLauncher.java:103-106` 记录了这次修复）。

`writeRemoteFile` 的收尾是 `awaitRemoteSize` → `syncRemote()` → `verifyRemoteDigest`
（`AdbClient.java:409,480,509,525`）。

### 8.3 密钥生命周期

`AdbKeyStore`（`AdbKeyStore.java:40`）：

- 存放：私有目录 `files/adb_identity`（`:45`）。可选内嵌 `assets/adb_identity.pk8`（`:48`），
  由 `apk/build.ps1 -BundledKey` 打入；没有就首次运行现场生成 RSA-2048（`:119-152`）。
- 公钥文本注释是 `dashcast@byd`（`:49`），adbd 的 `adb_keys` 里按这个认。
- 编码必须是 **524 字节**的 `ANDROID_PUBKEY_STRUCT`（`:346-353`）；
  解码长度不是 524 的整条密钥会被 adbd 当垃圾丢掉。
- 一致性自检 `consistent(priv, pub)`（`:162-172`）：签一个 token 再验回来。
- 落盘 `write`（`:281-315`）是**原子写**：临时文件 → `out.getFD().sync()`（`:294`）
  → `renameTo`（`:301-303`，失败则 delete + rename）→ 回读 `Arrays.equals` 校验（`:310`）。
  为什么必须这样：车机掉电后，非 fsync 的覆盖写会以"长度正确、内容全 0"的形态回来，
  旧实现于是静默重新生成密钥，代价是车机重新弹一次授权框。
- 重新生成时 `resetReason` 会带上原因（`:136-138`），并由 `AdbBootstrap` 并进用户可见文案
  （`AdbBootstrap.java:93-97`）。

### 8.4 代理上传与拉起

`AgentLauncher.ensureRunning`（`AgentLauncher.java:65-125`）：

1. `pidof dashcast-agent`，已在跑就直接返回（`:66-69`）。
2. 比对**内容**：本地 `sha256Hex(jar)` vs 远端 `sha256sum`（`:71-83`，远端摘要读取
   `:134-145` 只接受 64 位十六进制）。为什么不能用字节数：远端文件可能以"长度正确、
   内容全 0"的形态出现，长度判据会判定"最新"从而永远跳过上传，`app_process` 永远
   `ClassNotFoundException`（`:129-132`）。
3. 先 `echo … > LOG` 落一行"shell 确实跑到了这里"的凭据，再拉起（`:87-101`）。
   这样能把"命令没送到"和"送到了但没起来"两种失败区分开（`:87-90`）。
4. 用 `/system/bin/setsid` 而非 `nohup`：nohup 只挡 SIGHUP，而 adbd 回收的是**整个会话**；
   `setsid` 让代理另开 session 与进程组（`:91-93`）。
5. 拉起后**轮询回读** `pidof`（20 × 500ms），失败读回日志尾部当诊断（`:109-124`）。
   不能假定成功——被 SELinux 或路径问题挡掉时命令本身仍返回 0（`:62-63`）。

### 8.5 授权持久化

`AdbBootstrap.provision`（`AdbBootstrap.java:48-91`）在拿到 shell 后做两件收尾：

- `adb.syncRemote()`（`:79`）：车机框架把授权**追加**进 `/data/misc/adb/adb_keys` 时
  **没有 fsync**，掉电后那一整行会以"长度正确、内容全 0"回来；adbd 按 `\n` 切行后拿到空串，
  这把钥匙被永久丢弃。App 碰不到框架的 fd，但可以替它落盘（`:75-78`）。
- `makeAuthorizationPermanent(adb)`（`:144-164`）：把
  `settings put global adb_allowed_connection_time 0`，即授权窗口设为永久。
  不改的话取代码默认 604800000 ms（7 天），长期停放的车辆授权会被
  `filterOutOldKeys()` 从 `adb_keys` 删掉、重新弹框（`:121-129`）。
  实现是"读-写-回读"（`:146-159`），写不进去只记日志、绝不影响本次连接（`:135-136`）；
  回滚命令 `settings delete global adb_allowed_connection_time`（`:137`）。

`requestAuthorization` 参数决定是否允许触发车机授权对话框
（`AdbBootstrap.java:44-46`）：只有引导页在用户明确点「开始授权」后才传 `true`；
快速探测（`:100-116`）与开机路径（`BootReceiver.java:65`）必须传 `false`，
否则连接一超时就在车机上留下收不到输入的孤儿对话框。

### 8.6 失败面

| 状态 | 用户可见文案与下一步 | 锚点 |
| --- | --- | --- |
| `NEED_AUTHORIZATION` | 在「允许 USB 调试吗」上点「允许」，并勾「一律允许」 | `AdbBootstrap.java:174-175` |
| `UNREACHABLE` | 连不上 `127.0.0.1:5555`，去「开发者工具」打开无线 ADB | `:176-178` |
| `FAILED` | ADB 握手失败 | `:179-180` |
| 已获得 shell 但代理没起来 | 把 `AgentLauncher` 的 `detail` 原样展示（含日志尾部） | `:81-87` |

`describe` 的契约是"状态 → 面向用户的中文说明 + 可执行的下一步"（`:166-182`）。

---

## 9. 构建链

无 Gradle，纯命令行七步（`apk/build.ps1`）：

| 步骤 | 命令 | 行 |
| --- | --- | --- |
| 1 | `aapt2 compile --dir res -o res.zip` | `:65` |
| 2 | `aapt2 link --manifest ... -R res.zip -A assets --java gen` | `:70` |
| 3 | `javac -source 8 -target 8 -encoding UTF-8 -bootclasspath android.jar` | `:79` |
| 4 | `jar cf` + `d8.bat --min-api 26` | `:83,86` |
| 5 | **`aapt.exe add`** 加 `classes.dex` | `:94-102` |
| 6 | `zipalign -f -p 4` | `:115` |
| 7 | `apksigner sign` | `:129` |

两个必须知道的约束：

- **第 5 步为什么不能用 zip 重打包**（`:90-93`）：`resources.arsc` 必须保持
  `STORED`（method 0）才能在 API 30+ 安装；.NET 的 `CompressionLevel.NoCompression`
  仍会输出 deflate（method 8），只有 `aapt add` 会追加新条目并保持既有条目字节不变。
  脚本第 `:108-112` 行会**校验**这一点并在失败时退出。
- **前置**：`JAVA_HOME` + Android SDK（`build-tools;35.0.0`、`platforms;android-32`）；
  SDK 位置先读 `ANDROID_HOME`，否则退回 `%LOCALAPPDATA%\Android\Sdk`（`:10-13`）。

链接顺序（`agent/build.ps1` 必须先跑）：

```powershell
.\agent\build.ps1 -NoPush     # javac → d8 → jar（内含 classes.dex）
.\apk\build.ps1 -NoInstall    # 会把 agent/out/dashcast-agent.jar 拷进 assets
```

`apk/build.ps1:36-39` 在 jar 缺失时直接退出——它不替你猜。
`-BundledKey <pk8>` 可把固定私钥打进 APK；不打也可以，代价是首次运行弹一次授权框
（`:45-62`）。`apk/debug.keystore` 缺失时脚本自己 `keytool -genkeypair`（`:119-124`）。
产物为 `apk/dashcast.apk`（`:127`）。

`agent/build.ps1` 产物 `agent/out/dashcast-agent.jar`（`:34`）；
`agent/run.ps1` 是 PC 侧的 start/stop/status/log 工具，用
`app_process --nice-name=dashcast-agent` 启动（`:12-32`），
并特意避开 `pgrep/pkill -f`：包裹用的 `sh -c '<整行>'` 自己的 cmdline 里带着同样的文本，
`-f` 会匹配到（并杀掉）即将启动进程的那个 shell；`-x` 在该设备的 toybox 上返回空
（`:13-20`）。

---

## 10. 二次开发扩展点

### 10.1 选「一键启动」目标（不需要碰代码）

先收藏应用（点左边的 ☆），再点该行右侧的「○ 一键启动」把它点亮成「● 一键启动」。
单选：点亮另一个就覆盖上一个；点亮已选中的那个即取消
（`CastActivity.java:357`，读写 `QuickLaunch.java:46,64,74`）。
没选过目标时按「一键启动」只会提示，不会替你选。按钮通过后只投屏
（`CastActivity.java:381`）。

### 10.2 加一个代理能力（需要两侧同时改）

1. `Agent.java`：加 `TRANSACT_*` 常量（`:71-82`）、在 `onTransact` 加 case、
   实现私有方法。注意入口已有 uid 裁决，不要再自己写一遍。
2. `InjectClient.java`：**逐条镜像**常量（`:30-41`），加公开方法。
   fire-and-forget 用 `transact(code, data)`；要回执就自己 `transact(..., 0)` 并
   `reply.readException()`。
3. 两侧同时编译、同时安装。旧代理 + 新 App 会静默读出垃圾值。

### 10.3 改界面/交互

界面代码分布在三个 Activity 与四份布局里，**改之前先读
`dashcast-design-preview.html`（前端规格，不在本仓库内）**：

| 界面 | 代码 | 布局 |
| --- | --- | --- |
| 首次打开的免责声明 | `DisclaimerActivity.java`、`Disclaimer.java` | `res/layout/activity_disclaimer.xml` |
| ADB 授权引导 | `GuideActivity.java` | `res/layout/activity_guide.xml` |
| 主界面（含全高浮层应用列表） | `CastActivity.java`：`bindUi:321`、`refreshStatus:815`、`showEmpty:875`、`showSnackBar:886`、`reconnect:904`、`AppAdapter:977` | `activity_cast.xml`、`panel_apps.xml`、`item_app.xml` |

- 主界面按**作用域**三层：顶栏（关于仪表盘）/ 中间镜像 / 底栏（关于应用）。
- **主题固定深色**，只有 `res/values/colors.xml` 一套令牌，**不要加 `values-night/`**：
  界面不随车机日夜切换（理由见该文件的注释）。
- 尺寸单位一律 `px`（车机固定 1920×1080 @240dpi，1dp = 1.5px），预览页里的数字就是真机 px。
- 状态行是全应用唯一的状态出口，圆点颜色与文案必须成对更新（`refreshStatus`）。

### 10.4 不要做的事

- 不要在 App 进程里查已安装应用（包可见性过滤，见 §7.5）。
- 不要用 `shell:` 代替 `shell,v2,raw:`，也不要用 `nohup` 代替 `setsid`（§8.4）。
- 不要让代理建窗口/建 VirtualDisplay：那需要 App 的真实进程环境（§2）。
- 不要把"投屏"实现成"自建一块 VirtualDisplay"（§6.5）。
- 不要用字节数判断远端文件是否需要重传（§8.4）。
- 不要在非引导路径传 `requestAuthorization = true`（§8.5）。

---

## 11. 辅助模块

| 模块 | 定位 | 代码锚点 |
| --- | --- | --- |
| `tools/dashcast.ps1` | 纯 adb 命令版驱动，不安装任何软件；`list/up/touch/swipe/key/text/shot/down/restore`，默认 `-Display 2` | `dashcast.ps1:2-42` |

---

## 12. 已知边界与未确认项

- 代理与 App 的契约必须成对升级（§5.4）。
- 车机框架把授权写进 `adb_keys` 时没有 fsync，程序只能在事后补一次 sync，无法根治（§8.5）。
- 应用自身发起的 Activity 启动会把任务挪到主屏，看门只能事后搬回（§7.4）。
- `DashboardSession` 依赖车机常量 `displayId = 2`（§6.5）。

**未确认**：

- `BootReceiver` 开机自启在干净条件下是否必然成功，尚未验证。
- `AdbBootstrap.makeAuthorizationPermanent` 的持久性在车机 OTA 后是否仍成立。
- `bypassHiddenApi()`（`Agent.java:969`）的具体手段。
