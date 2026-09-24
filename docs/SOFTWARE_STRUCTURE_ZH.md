# dashcast 软件结构技术文档

> **读者**：二次开发本工程的人。
> **范围**：交付形态、模块划分、进程与信任边界、跨进程契约、两侧内部流程、提权链、构建链、扩展点。
> **锚点约定**：`文件:行号` 对应本发行版当前源码树（`apk/`、`tools/`）。行号只用于定位，不是契约。
> **证据分级**：【实测】真机跑出来、有日志或输出为证 · 【静态】源码/清单/资源里读到、未在真机复现 · 【推断】由前两者推出、未逐行或未实机验证。
> **「未实测」= 没有人量过它**，不等于「大概没问题」。本文不写没有验证过的数字。
> 本文件是**本发行版唯一的结构文档**（单一副本、单一来源）。开发主线另有专题文档（预览机制、提权链、ADB 自举等）与排查脚本，它们**不随本发行版分发**；本文每一节都能在本仓库源码内复核。

---

## 1. 交付形态

**只有一个交付物，而它同时是前端和特权执行体。**

| 项 | 值 |
| --- | --- |
| 产物 | `apk/dashcast.apk`（`apk/dashcast.apk.idsig` 是 `apksigner` 的 v4 副产物，安装用不到） |
| 包名 / 版本 | `com.byd.dashcast` · `versionName="4.2-cast-hold"` · `versionCode="114"`（`AndroidManifest.xml:4-5`） |
| SDK | `minSdk 26` / `targetSdk 32`（`:7-9`） |
| 权限 | `INTERNET`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`（`:12-16`，最后一项给守位服务）；**无 `INJECT_EVENTS`、无 `READ_FRAME_BUFFER`** |
| 安装方式 | 普通应用安装（`untrusted_app`，实测 `userId=10124`）；**无 root、无系统签名** |

特权代码就在这个 APK 的 dex 里：`com.byd.dashcast.privileged.*`，需要时由 App 用
`CLASSPATH=<自己的 APK 路径> nohup app_process /system/bin --nice-name=com.byd.dashcast-priv com.byd.dashcast.privileged.PrivilegedProcess 2` 拉起（`PrivilegedClient.java:232-235`）。

这条设计消掉了**整条载荷分发链路**：

| 旧形态（已废弃，不复存在） | 现在 |
| --- | --- |
| 第二个产物 `dashcast-agent.jar` | 无。特权代码是同一个 dex 里的类 |
| 推到 `/data/local/tmp/`、比对 sha256/字节数 | 不推任何文件到设备，不需要校验 |
| 版本漂移（jar 与 APK 不一致） | 结构性不存在：写进 dex 的类名与命令行里的类名是同一份编译产物 |

【静态】`apk/build.ps1:40-52` 会**主动删除**历史遗留的 `apk/assets/dashcast-agent.jar` 并打印 `agent jar: not shipped (privileged code lives in this APK dex)` —— 留着它会让「这个包到底还带不带代理」变成一件要靠翻源码才知道的事。`apk/assets/` 现在是空的，只在构建时传 `-BundledKey` 才会被填入 `adb_identity.pk8`。

清单里三处必须知道的东西：

| 位置 | 内容 | 为什么 |
| --- | --- | --- |
| `AndroidManifest.xml:28-33` | `<queries>` 声明 `MAIN` + `LAUNCHER` | **没有它，应用列表里一半条目显示包名而不是应用名**：targetSdk 32 在 Android 11+ 默认做包可见性过滤，`getApplicationInfo()` 对未声明的第三方包抛 `NameNotFoundException`。可见性还是**动态**的（只有本进程启动过某应用它才临时可见，进程一重启就没了），所以症状是「昨天还好好的，今天全变包名」【实测】 |
| `AndroidManifest.xml:40-50` | `CastActivity` 是唯一 LAUNCHER 入口，`singleTask` | 桌面图标的落点就是主界面 |
| `:56-72` | `GuideActivity`（无 intent-filter）、`BootReceiver`（`BOOT_COMPLETED` + `QUICKBOOT_POWERON`） | 引导页由本包主动 `startActivity` 拉起；开机接收器自己接通道 |

---

## 2. 为什么必须是两个进程

这是整个工程唯一不可绕过的约束，也是读代码前必须先建立的模型。

**① 界面必须在已安装 App 里。** 纯 `app_process`（uid 2000）进程跑不了常规控件【实测】：`ActivityThread.systemMain()` 不走 `handleBindApplication`，进程级字体初始化从未发生，`Typeface.DEFAULT / SANS_SERIF / MONOSPACE` 全为 `null`，`TextView` / `Button` 构造直接 NPE；该进程没有 `ProcessRecord`，`ContentResolver` 访问 `Settings` 等 provider 会被 AMS 以 `Unable to find app for caller` 拒绝。所以特权进程刻意做小：**不建窗口、不碰 provider**，只做建屏接屏与注入输入两件事（`PrivilegedProcess.java:88-100`）。

**② 三件事只能由 uid 2000 做。**

| 能力 | 为什么普通应用不行 | uid 2000 怎么做 |
| --- | --- | --- |
| 投屏 | `ActivityOptions.setLaunchDisplayId()` 从普通应用发起被 AMS 直接拒：【实测】`SecurityException: Permission Denial: starting Intent {...} from ProcessRecord{...} with launchDisplayId=N` —— 非多屏（`supportsMultiDisplay`）应用上不了副屏 | `am start-activity --display N -n pkg/act`（`am` 传 `caller=null`，AMS 按 pid/uid 处理）；任务已存在则 `am display move-stack <taskId> N` |
| 触摸注入 | `InputManager.injectInputEvent()` 需要 `INJECT_EVENTS`，是 `signature\|privileged` | 特权进程内反射调 `injectInputEvent`（`InputInjector.java:64-82`） |
| 预览 | `SurfaceControl.createDisplay / setDisplayLayerStack / setDisplayProjection / setDisplaySurface` 全是 `@hide`，且要有 shell 身份才通得过 SurfaceFlinger 的权限检查 | 特权进程内反射走同一串调用（`PreviewDisplay.java:72-113`） |

其它路径仍然都不通【静态】：`AccessibilityService.dispatchGesture` 无法指定 `displayId`，`UiAutomation` 只对 instrumentation 开放，`input` 命令本身也要 uid 2000。

**③ 为什么不能用 shell 命令代替常驻特权进程。** 2026-09-20 那轮「去代理」改造的前提是错的：当时认为 ADB shell 命令足以覆盖全部需求，实测下来有两条腿 shell 根本走不了：

| 腿 | shell 唯一替代品 | 实测代价 | 结论 |
| --- | --- | --- | --- |
| 触摸 | `input -d N tap\|motionevent` | **40~140 ms/次**（`mksh` 内建 `time` 的 5 次样本：`0.14 / 0.11 / 0.05 / 0.09 / 0.04 s`）—— 每个事件 fork 一个 ART 虚拟机；一次连续拖拽上百个 MOVE ⇒ `100 × ~90 ms ≈ 9 秒` | 拖拽不可用 |
| 预览 | `screencap -d N -p` 周期抓帧 | 单连接 **422 ms/帧 = 2.4 fps**；降级路径实测帧间隔 288~366 ms ≈ **3.1 fps**（3 条并发 177 ms/帧、6 条 131 ms/帧） | 3 fps 只能是兜底 |

**④ 原版 Just Dashboard 自己就是这么做的**【实测·反编译】：真机抓到的进程画像是 `/system/bin/app_process64`、uid/gid `2000/2000`、`CapEff=0`、`PPid=1`、`CLASSPATH` 指向它自己的 APK；它的 manifest 全文只声明 6 个权限，**没有 `INJECT_EVENTS`、没有 `READ_FRAME_BUFFER`**。从权限层面就证明主应用不可能读屏或注入 —— 特权执行体是结构性的必需品，不是实现偏好。

**⑤ 和上一版「代理 jar」架构的差别。** 旧架构的失败点不在「有没有代理」，而在**多客户端争抢同一个代理**：代理用全局 `lastContact` 判断「最近没人调用过」才发广播，于是先连上的客户端把代理钉住、后启动的永远收不到句柄，界面永远停在「注入代理未连接」【实测】：车机重启后 netease 分支先自启占住代理，母工程晚了 18 分钟启动就再也连不上。这一轮的结构差别有两条（`PrivilegedClient.java:26-58`）：**回传是显式定向广播**（`Intent(ACTION_READY).setPackage(APP_PACKAGE)`，只有本包收得到；句柄用公开 API `Bundle.putBinder/getBinder` 递），**拉起前先清场**（`pkill -f com.byd.dashcast-priv` 之后才启动，App 被系统杀掉时留下的旧实例不会把死句柄留在手里）。隐藏状态从结构上不再存在。

---

## 3. 仓库模块地图

```
apk/                              车机端应用（唯一交付物）
  src/com/byd/dashcast/
    CastActivity.java             主界面 + 预览三态状态机 + 触控转发 + 投屏链路（闭环归位）
    CastGuardService.java         守位前台服务：投屏成立后持有屏位不变量，界面退出也继续（含「收回投屏」通知）
    AppLog.java                   关键日志落盘（车机日志策略会丢弃第三方应用的 Log.*）
    GuideActivity.java            ADB 授权引导（兼"快速通道"）
    DashboardSession.java         仪表屏定位（投屏槽位 + 主投影屏两个 id）
    DashboardEye.java             抓帧判页（两维三分类；固定抓主投影屏，不跟窗口走）
    ShellChannel.java             ADB shell 长连接通道 + 搬屏原语
    InjectClient.java             降级路径编排 + 应用枚举 + 归位原语 ensureOnDisplay + 抓帧
    PrivilegedClient.java         App 侧 Binder 客户端（进程内单例）
    AppRepo.java / Favorites.java 应用条目 / 收藏（存包名）
    AutoCast.java                 「一键目标」与「首开自动」的持久化与判定
    CarAccount.java               读取当前登录的 DiLink 账号
    adb/                          ADB 自举
      AdbClient.java              线协议：握手、AUTH、shell v2 分帧、exec
      AdbKeyStore.java            ADB 身份（RSA-2048）的生成、加载、公钥编码
      AdbBootstrap.java           「载密钥 → 连 adbd → 交通道」的唯一入口
      BootReceiver.java           开机接通道，按固定退避重试
    privileged/                   uid 2000 特权执行体（就在同一个 dex 里）
      PrivilegedProcess.java      IPC 服务端：main + Channel.onTransact + 生命周期
      PreviewDisplay.java         SurfaceControl 反射封装：attach / detach
      InputInjector.java          InputManager.injectInputEvent 反射封装
      PrivilegedProtocol.java     两侧共享的通道契约（常量、事务号）
  res/                            布局、字符串、一键落点表、主题
  AndroidManifest.xml             包名 / 版本 / 权限 / <queries> / 组件
  build.ps1                       七步构建 + 安装
tools/dashcast.ps1                纯 adb 命令版驱动（不安装任何软件）
docs/SOFTWARE_STRUCTURE_ZH.md     本文件（发行版唯一结构文档）
README.md / CHANGELOG.md / LICENSE / .editorconfig / .gitattributes / .gitignore
```

**发行版刻意保持简洁**：没有 `agent/`、`scripts/`、`probe/`、`probe-apk/`、`replica/`、`preview-probe/`，也没有反编译产物与抓帧证据 —— 那些留在开发主线，不随发行版分发。构建产物（`apk/out/`、`apk/dashcast.apk`、`*.idsig`、`*.class`）由 `.gitignore` 排除。

---

## 4. 进程与信任边界

### 4.1 进程画像

| | App | 特权进程 |
| --- | --- | --- |
| 进程名 | `com.byd.dashcast` | `com.byd.dashcast-priv`（`--nice-name`，由 `APP_PACKAGE + "-priv"` 派生） |
| 身份 | `untrusted_app`，实测 `uid 10124` | `shell`，实测 `Uid: 2000 / Gid: 2000`、`PPid: 1`【实测】 |
| 实体 / 代码来源 | APK 正常安装 | `/system/bin/app_process64`；`CLASSPATH=/data/app/~~…==/com.byd.dashcast-…==/base.apk`【实测】 |
| 能力 | 普通应用权限 | uid 2000 的 shell 权限（**无 root**）；原版同形态进程实测 `CapEff=0`，本进程未单独量过【推断同为零】 |
| 生命周期 | 跟着界面 | 界面可见才拉起；`onPause` 停；App 一死对端自己退（§7.5） |

### 4.2 信任锚：与旧代理完全不同

旧代理的信任模型是「与锚点同签名的应用都服务」（枚举已安装包、取签名一致的、推出信任 uid 集合、每次事务入口做 `Binder.getCallingUid()` 裁决），所以换签名等于换信任域。**当前实现没有这层模型**【静态】：

- `PrivilegedProcess.Channel.onTransact` 里**没有任何调用方 uid 裁决**（只有 `data.enforceInterface(DESCRIPTOR)`，7 处，`PrivilegedProcess.java:148-218`）。
- 唯一的访问约束是**定向广播**：`intent.setPackage(PrivilegedProtocol.APP_PACKAGE)`（`:276-283`），Binder 句柄只交给本包注册的接收器。
- 每个包各拉各的特权进程：`NICE_NAME = APP_PACKAGE + "-priv"`，本包得到 `com.byd.dashcast-priv`、派生包得到 `com.byd.dashcast.netease-priv`，两个模式互不匹配 —— 清理用的 `pkill -f` 不会互杀（`PrivilegedProtocol.java:35-45` 记录了这条修正）。

**含义要写清楚**：拿到这个 Binder 的进程就能注入触摸、把任意图层栈接到自己给的 Surface 上。今天只有本包拿得到它；**若将来把这个特权执行体改成服务多个进程，必须在 `onTransact` 里补 `Binder.getCallingUid()` 裁决**，不能靠「广播别人收不到」—— 那只是当前形态的副产品，不是设计出来的信任边界。

### 4.3 方向性与通道选型

两个进程之间只有两个方向：特权进程 → App（一次显式广播 `ACTION_READY` + Binder、一次死亡通知），App → 特权进程（7 个 Binder 事务，§5.1）。特权进程**不主动调 App 的任何东西**：它的 `Context` 来自 `ActivityThread.systemMain()` + `createPackageContext("com.android.shell", 0)`（`PrivilegedProcess.java:292-305`），不是从 App 借的。

**为什么是 Binder 而不是 LocalSocket**：`Surface` 是 `Parcelable`，但它内部持有 binder/native 句柄，**跨进程只能走 Binder** —— 写进 ADB 流的字节传不过去。反过来，「跑一条 shell 命令看输出」是 shell 通道的强项，Binder 通道不该为它造事务码。这个分工决定了 §5.4 的边界。

---

## 5. 跨进程契约

### 5.1 Binder 契约（唯一需要两侧同时改的地方）

契约写在一份共享文件里 —— `privileged/PrivilegedProtocol.java`。**两侧编译期同源**（特权代码与本类在同一个 dex 里），常量不存在版本漂移。

| 项 | 值 | 锚点 |
| --- | --- | --- |
| descriptor | `com.byd.dashcast.privileged.Channel` | `PrivilegedProtocol.java:30` |
| 回传广播 | `com.byd.dashcast.PRIVILEGED_READY` | `:21` |
| 句柄 extra key | `com.byd.dashcast.privileged.binder`（`Bundle.putBinder/getBinder`，公开 API） | `:24` |
| 目标包名 / 主类 / 进程名 | `com.byd.dashcast` · `com.byd.dashcast.privileged.PrivilegedProcess` · `com.byd.dashcast-priv` | `:27,33,45` |

**事务表**（定义 `PrivilegedProtocol.java:47-83` · 服务端 `PrivilegedProcess.java:143-229` · 客户端 `PrivilegedClient.java`）：

| code | 常量 | 入参（Parcel 顺序） | 回执 | 客户端发送方式 | 服务端 |
| --- | --- | --- | --- | --- | --- |
| +1 | `CODE_PING` | — | `int uid`、`int targetDisplay` | 同步（`:449-466`） | `:147-154` |
| +2 | `CODE_SET_SURFACE` | `Surface`（`writeParcelable(surface, 0)`） | `int ok` | 同步（`:327-346`） | `:155-172` |
| +3 | `CODE_CLEAR_SURFACE` | — | `int ok` | 同步（`:348-365`） | `:173-180` |
| +4 | `CODE_TOUCH` | `int action`、`float x`、`float y`、`long downTime`、`long eventTime` | `int ok` | **oneway**（`:374-394`） | `:181-194` |
| +5 | `CODE_KEY` | `int keyCode`、`int action` | `int ok` | **oneway**（`:429-446`） | `:195-205` |
| +6 | `CODE_SHUTDOWN` | — | `int ok`（调用方不看） | 同步（`:270-295`） | `:217-225` |
| +7 | `CODE_ATTACH_CLIENT` | `IBinder clientToken`（`writeStrongBinder`） | `int ok` | 同步（`:305-324`） | `:206-216` |

触摸动作四态显式定义（`PrivilegedProtocol.java:80-83`）：`TOUCH_DOWN=0` / `UP=1` / `MOVE=2` / `CANCEL=3`，数值与 `MotionEvent.ACTION_*` 一致，但**不引用框架常量**，避免两侧耦合。多指动作（`ACTION_POINTER_DOWN/UP`）在单指协议里没有对应表示，`PrivilegedClient.normalizeAction`（`:417-427`）一律收敛成 `TOUCH_MOVE`。

三个设计决定值得记下来：

- **`SET_SURFACE` 用 `writeParcelable(Surface, 0)` 递 Surface**，对端 `readParcelable(Surface.class.getClassLoader())` 读回。`Surface.writeToParcel` 写的是指向 `IGraphicBufferProducer` 的强 binder，所以跨进程拿到的是一份代理；特权进程把它交给 `setDisplaySurface` 后，SurfaceFlinger 成为这块 BufferQueue 的生产者、App 的 `SurfaceTexture` 是消费者，**App 侧不参与搬运** —— 这就是零拷贝的全部内容。
- **`TOUCH` / `KEY` 走 `IBinder.FLAG_ONEWAY`**：拖拽时每秒几十个 MOVE，每个都等一次往返会把触摸线程变成瓶颈，而触摸没有调用方会拿返回值做决策。有返回值的三个事务（PING / SET_SURFACE / ATTACH_CLIENT）确实被调用方使用。
- **`CODE_ATTACH_CLIENT` 是「不留孤儿进程」的结构性保证**：`am force-stop` 与 LMK 回收都**不走 `onPause`**，`CODE_SHUTDOWN` 永远发不出去。实测踩过：force-stop 之后 `dashcast-priv` 仍然活着，带着它建的 display 一直挂到下次启动 App 被 `pkill`。现在 App 把自己的 token 交给对端 `linkToDeath`，App 一死对端 `System.exit(0)`。实测日志：

```
[priv] 已挂上客户端死亡通知
[priv] SET_SURFACE -> true
[priv] 客户端进程已退出，本进程一并退出
```

之后 `ps -A | grep dashcast-priv` → **NOPROC**【实测】。

### 5.2 拉起与就绪时序（客户端侧）

`PrivilegedClient.doStart`（`:211-260`）的顺序是刻意的，不要调换：

1. `ensureReceiver()`：先注册 `ACTION_READY` 接收器 —— 注册不能晚于启动，否则会漏掉那条广播。
2. `pkill -f com.byd.dashcast-priv`：清掉可能残留的旧实例。
3. 下发启动命令：`CLASSPATH=<APK> nohup app_process /system/bin --nice-name=… <MAIN_CLASS> <displayId> > /data/local/tmp/dashcast-priv.log 2>&1 &`。
4. 等 `CountDownLatch`，上限 `READY_TIMEOUT_MS = 10000`（`:64`）；超时返回的文案带上 `tail -n 6 <日志>`（`:507-517`）—— 「进程起不来」必须有可读的原因，否则用户只能看到一句「未连接」。
5. `attachDeathWatch()`：就绪后立刻交 token。这一步不能省（见 §5.1 第三条）。

APK 路径取自 `ApplicationInfo.sourceDir`（`:498-504`），**不去解析 `pm path` 的输出**：路径本来就是本进程的属性，精确、零开销，也不会因为输出格式或传输层的任何变化而变。

**线程模型**：所有会碰设备的操作排在一条单线程 executor 上（`ioExecutor`，`:108-115`），两条理由缺一不可（`:97-107`）：`ShellChannel.run` 是 socket IO，主线程上做会抛 `NetworkOnMainThreadException`，而 `stop()` 是从 `onPause` 调的；且「拉起」与「停止」**必须保序** —— 若停止的 `pkill` 落在拉起之后，会把刚起来的进程当场杀掉，表现为「预览随机起不来」这种偶发故障。

`stop()`（`:262-295`）先发 `CODE_SHUTDOWN` 让对端自己 `destroyDisplay` 后退出，再用 `pkill` **无条件**兜底 —— 事务可能因为句柄已失效而发不出去，而一个残留的 uid 2000 进程会一直占着它建的 display。App 被系统杀掉时走不到 `stop()`，所以每次收尾都必须清一次。

### 5.3 契约升级规则

- 改任何事务号、字段顺序或常量值，**两侧在同一个文件里，编译一次就同时改掉**：`PrivilegedProtocol` 是唯一来源，不存在「旧对端 + 新客户端」的错位读。
- 唯一会碰到「旧代码对端」的场景是**覆盖安装**（`adb install -r`）之后旧特权进程还活着：新 App 的 `doStart` 会先 `pkill` 再拉起，所以下一条会话必然是同一份代码。在此之前若事务号对不上，服务端落到 `super.onTransact`（默认实现返回 false，即「这个事务没人处理」），客户端这一侧的返回值和异常本来也不检查、一律吞掉，**最终由无条件的 `pkill` 兜底**。
- 不要新增「第二个客户端」；真要那样做，先补 §4.2 的 uid 裁决。

### 5.4 两条通道的能力边界

| 通道 | 形态 | 承担 | 用什么 |
| --- | --- | --- | --- |
| Binder | 广播回传的 `IBinder` | 递预览 `Surface`、注入触摸/按键、查状态 | `PrivilegedClient` + `PrivilegedProcess` |
| ADB shell | 回环 TCP 到 adbd（uid 2000） | 投屏、按键、应用清单、任务归属/搬迁、降级抓帧的预览 | `ShellChannel` + `AdbClient` |

`Surface` 跨进程只能走 Binder，所以预览必须走上一行；而「跑命令看输出」不该为它造事务码，所以投屏/清单/看门留在 shell 通道。协议细节见 §8。

---

## 6. App 侧结构

### 6.1 类职责

| 类 | 职责 | 关键锚点 |
| --- | --- | --- |
| `CastActivity` | 主界面 + 全部编排：投屏、预览三态状态机、触控转发、看门、一键脚本 | `CastActivity.java:64`，`onCreate:245-383` |
| `GuideActivity` | ADB 授权引导，兼「快速通道」：已就绪时几百毫秒内直接转走 | `GuideActivity.java:59-79` |
| `DashboardSession` | 定位投屏槽位（`displayId`）与主投影屏（`projectionDisplayId`） | `DashboardSession.java:94-146` |
| `ShellChannel` | shell 长连接 + 全部 shell 原语（投屏/输入/清单/任务/搬屏/抓帧） | `ShellChannel.java:55` |
| `InjectClient` | 降级路径编排：应用清单、触摸兜底、抓帧预览、**看门状态机** | `InjectClient.java:44` |
| `PrivilegedClient` | App 侧 Binder 客户端（进程内单例）：拉起、递 Surface、注入触摸 | `PrivilegedClient.java:59`，单例 `:157-171` |
| `DashboardEye` | 抓帧判页（两维三分类） | `DashboardEye.java:105-126` |
| `AutoCast` / `CarAccount` | 一键目标（读资源）与首开自动开关；读取当前登录账号 | `AutoCast.java:20`、`CarAccount.java:136-162` |
| `AppRepo` / `Favorites` | 应用条目 / 收藏包名集合 | `AppRepo.java:11`、`Favorites.java:16` |
| `adb/AdbClient` / `AdbKeyStore` / `AdbBootstrap` / `BootReceiver` | 线协议 · ADB 身份 · 通道唯一入口 · 开机接通道 | `AdbClient.java:48`、`AdbKeyStore.java:40`、`AdbBootstrap.java:58-84`、`BootReceiver.java:23` |

### 6.2 启动流程

```
桌面图标 / 最近任务 / am start
  → CastActivity.onCreate                                   CastActivity.java:245
       ├─ autoCast.enabled() ? CarAccount.read()             :250-251（开关默认关，关着完全不碰账号服务）
       ├─ shouldRun(account) ─► 首开自动分支：不 setContentView，
       │                          prepareSession + ensureAgent + startAutoCast   :256-266
       └─ 正常分支：setContentView → bindUi → prepareSession → ensureAgent → 接线  :268-383

  ensureAgent（每实例只跑一次）                              :425-460
    → 后台线程 AdbBootstrap.provision(this, TIMEOUT_BACKGROUND_MS=8000, false)
        → AdbKeyStore.loadOrCreate → AdbClient.connect → ShellChannel.adopt
                                                            AdbBootstrap.java:58-84
    → 成功且 injector.isAttached() → onChannelReady         :442-445
    → 失败且非 autoMode            → startActivity(GuideActivity)  :450-452

  onChannelReady                                            :468-484
    → autoMode ? runAutoCast() : { setDisplay / requestApps / startPreviewIfReady }
    → adoptAgentWatch() → refreshStatus()
```

三个要点：

- **`requestAuthorization` 必须是 `false`**（`CastActivity.java:437`）。发公钥会让车机弹「允许 USB 调试吗」，而那个对话框**绑在发起它的那条连接上**：连接一断，窗口还留在屏幕上却收不到任何输入，还挡住后面真正需要点的对话框。只有引导页在用户明确点过「开始授权」之后才传 `true`（`GuideActivity.java:129-131`）。
- **`GuideActivity` 的触发条件是能力探测，不是「首次运行」标记**：先跑一次 `FAST_PROBE_MS = 3000` 的短探测，就绪就 `goNext()` 转走（用户看不见这一页）；万一授权真丢了（恢复出厂、手动撤销），引导会自动回来，不会出现「标记说完成、实际连不上」的哑状态。
- **状态行是全应用唯一的状态出口**（`refreshStatus`，`:1415-1443`）：`已投到仪表盘 · display N` +（` · 预览中` | ` · 预览已降级为抓帧（原因）`）+（看门备注）+（`（正在建立 ADB 通道…` | `（ADB 通道未连接）`）。字段名 `agentBringUpRunning` 是历史命名，文案已经是「ADB 通道」（`strings.xml:24-25`）。

### 6.3 显示拓扑与两个 displayId（最容易搞错的一处）

仪表盘是**车机自带**的投影屏，不要自建。实测拓扑【实测】：

| display | 名字 | 尺寸 | layerStack | 属性 | 角色 |
| --- | --- | --- | --- | --- | --- |
| 0 | `内置屏幕` | 1920×1080 | 0 | `type INTERNAL`、`touch INTERNAL`、`FLAG_SECURE` | 主屏，**唯一 HWC 屏** |
| 2 | `fission_bg_XDJAScreenProjection` | 1920×720 @320 | 2 | `type VIRTUAL`、`touch NONE`、owner `com.byd.containerservice`(uid 1000)、`FLAG_PRESENTATION` | **这就是仪表盘**（实际显示内容的那块屏） |
| 3 / 4 | `shared_fission_bg_XDJAScreenProjection_0/1` | 1920×720 | 3 / 4 | 共享变体 | **投屏槽位**，`screencap` 抓它恒为全黑 |

投屏链路：应用投到 display 3（槽位）→ 车机自己的 `com.byd.containerservice` 把槽位镜像到 display 2 → 仪表盘可见。判据是同一时刻的 PNG 体积：display 3/4 恒为 **7131 B（全黑）**，display 2 同一时刻 **170514 B**（网易云歌词页那帧）/ **485299 B**（2026-09-20 复核、导航地图那帧）—— 体积随内容变化，**只有「3/4 恒为全黑」这一条是稳定的**，也正是它的诊断价值【实测】。

由此推出代码里两个不同的 id，`DashboardSession` 同时解析它们（`:94-146`）：

| 字段 | 用途 | 解析方式 | 兜底常量 |
| --- | --- | --- | --- |
| `displayId()` | **往哪里投** | 枚举共享变体，优先名字以 `_0` 结尾的那个（通常是 3） | `SHARED_DISPLAY_FALLBACK_ID = 3`（`:59`） |
| `projectionDisplayId()` | **投完在哪儿看得见**（预览/抓帧目标） | 枚举到 `fission_bg_XDJAScreenProjection` 就用它 | `PROJECTION_DISPLAY_FALLBACK_ID = 2`（`:71`） |

两条都必须有兜底：**应用进程枚举不到主投影屏** —— 实测 `DisplayManager.getDisplays()` 只回 display 3/4（主投影屏被可见性过滤），而 `screencap` 跑在 uid 2000 里、按 id 直接就能抓到。日志会把两条都打出来便于对照：`预览目标屏 display=2（投屏槽位=3）`。

**不要投 display 2**：实测投它会失败 —— 应用窗口（`BASE_APPLICATION`，layer 21000）被车机导航的 `Presentation`（layer 31000）盖住，`screencap -d 2` 三帧 SHA 全同，画面根本没上屏；投共享变体（3/4）内容才真的出现在物理仪表屏上，且盖过导航【实测】。**也不要投 display 4**：`resolve()` 只在枚举不到 `_0` 时才退到「第一个共享变体」，正常运行只用 display 3【静态】。仪表屏 `touch NONE`，所以所有输入都必须注入。

### 6.4 触控转发

```
touchPanel.onTouch（UI 线程）                                CastActivity.java:275-308
  → 列表开着 或 仪表屏未定位 → 返回 false（把触摸留给列表，防误触）
  → 只读状态字段打日志，**不在 UI 线程做 Binder 往返**
  → 投到单线程 touchExecutor（顺序 = 手势的生命线；并发下发会让 DOWN/MOVE/UP 乱序）
      → forwardTouch                                         :1332-1337
          ├─ privileged.touch(...) 成功 → return              （一次 Binder oneway，微秒级）
          └─ 否则 injector.touch(...)                         （降级：input -d N motionevent）
```

- 触控板是一块 1920×720 的 `FrameLayout`（`R.id.touchPanel`），与仪表屏**同尺寸同坐标系**，所以 View 局部坐标就是仪表屏坐标，**不需要任何换算**（`activity_cast.xml:61-77`）。
- 触控板的透明与否只认「画面已上屏」这一个状态（§6.5）：未上屏时盖一层 `#1A2E9BFF` 提示底色，上屏后 `Color.TRANSPARENT`（`applyPreviewVisuals`，`:1049-1059`）。
- 补点用 `tapOnCluster`（`:1345-1350`）：优先 `privileged.tap()`（DOWN + UP 两次 oneway），降级用 `injector.tap()`（`input tap`，合成完整手势）。两套语义不同，所以分开写而不共用。
- 降级路径下「触摸该发到哪块屏」由 `InjectClient.inputDisplay()`（`:335-348`）决定：优先按包名从 `dumpsys input` 解析窗口真实挂在哪个 display，解析不出来才兜底到主投影屏。**绝不回退到投屏槽位**：槽位是黑的，往它注入会被 InputDispatcher 丢掉（`Dropping event because there is no touched foreground window in display 3`）【实测】。

### 6.5 预览：三态状态机

用**枚举** `PreviewMode`（`IDLE` / `PASSTHROUGH` / `FALLBACK`，`CastActivity.java:986-993`）而不是布尔量，因为两条路的**信号源完全不同**：直通看 `onSurfaceTextureUpdated`（SurfaceFlinger 出帧），降级看 PNG 解码上屏。混成一个标志就会出现「降级时被 TextureView 的回调误判成画面已上屏」。

四个状态字段各管一件事，不要合并：

| 字段 | 含义 | 谁写 |
| --- | --- | --- |
| `previewStarted` | **请求已发出**（防重复启动） | `startPreviewIfReady` / `stopPreviewIfRunning` |
| `previewBound` | **已接上特权进程**（`setDisplaySurface` 成功） | `sendPreviewSurface` / `detachPreviewSurface` |
| `previewing` | **画面真的在上屏**（触控板据此变透明） | `onSurfaceTextureUpdated`（直通）/ 首帧上屏（降级） |
| `fallbackReason` | 走到降级路径的原因，写进状态行 | `enterFallback` |

**`onSurfaceTextureUpdated` 才是「画面已上屏」的判据**：`setDisplaySurface` 返回 true 只说明「接上了」，不等于「在出帧」—— 仪表屏内容静止时 SurfaceFlinger 不重复合成，回调可能长时间不来。所以 `previewBound` 与 `previewing` 必须分开：心跳靠前者分辨「画面接好了但内容没变」和「对端已经死了、画面静止在最后一帧」。

Surface 生命周期（`previewSurfaceListener`，`:1011-1042`）：`onSurfaceTextureAvailable` → `attachPreviewSurface` 包装 `new Surface(texture)` 并（若模式已是 PASSTHROUGH）交出去；`onSurfaceTextureDestroyed` → `detachPreviewSurface` 并**返回 true** 把 SurfaceTexture 还给视图系统（留着的话重建界面时新旧会同时存在，旧的还挂在特权进程上）。`sendPreviewSurface`（`:1105-1128`）是**幂等**的：`previewBound` 挡住重复递交，否则对端会 `destroyDisplay` 再 `createDisplay`（白闪一下）—— 而「特权进程就绪」与「SurfaceTexture 就绪」的先后顺序是不确定的，两条路都会走到它。

**降级绝不允许静默**，三个入口都带原因，写进状态行（`strings.xml:21-23`）：

| 触发 | 原因文案 | 锚点 |
| --- | --- | --- |
| `onFailed`（特权进程起不来） | 具体原因（超时 / 拿不到 APK 路径 / 日志尾巴） | `:1207-1213` |
| `setPreviewSurface` 返回 false | `特权通道拒绝接收预览画面` | `:1125-1128` |
| 心跳发现对端已退出 | `特权进程已退出` | `:1281-1297` |

切模式时两个预览视图**互斥显示**（`preview` → GONE、`previewFallback` → VISIBLE），不靠透明度叠加 —— 空 TextureView 是否遮挡底下那个 ImageView 取决于厂商实现，不能赌。切回直通时补一次 `attachPreviewSurface`（`:1069-1081`）：视图从 GONE 回到 VISIBLE 时 SurfaceTexture 不一定被重建，那样就再也不会收到 `onSurfaceTextureAvailable`，Surface 也就永远交不出去。

**降级路径的抓帧循环**（`startFallbackPreview`，`:1222-1274`）：4 条预览连接并发抓帧（`ShellChannel.PREVIEW_CHANNELS = 4`），谁先抓到谁上屏，帧在**预览线程**上解码（PNG 解码是纯 CPU 活，放 UI 线程会直接卡住界面），只把 `Bitmap` 递到 UI 线程；`previewFrameBusy` 忙时**直接丢掉这一帧** —— 排队只会让画面越来越旧。**心跳巡检**：直通画面接好后对端若退出，画面会静止在最后一帧（看起来像卡住而不是断线），所以心跳里 `checkPreviewAlive()` 发现句柄不活了就转降级。

### 6.6 看门：把应用钉在仪表屏

**为什么要它**【实测】：应用**自己**发起的 Activity 启动不带 display。在 B 站里点一张视频卡播放，它自己发 `bilithings://player`，AMS 侧判定缓存的 display 已失效（`LaunchParamsPersister: needRemoveTaskCacheParams display =null`），整条 root task 被挪到默认屏（display 0）—— 投屏当场丢失。**这是应用侧行为，无法预防**：那个 Intent 是应用自己发的，塞不进 `launchDisplayId`，所以只能事后搬回。

**实现完全搬到了 App 侧**（旧架构由代理主循环承担）：

```
心跳线程（HandlerThread "dashcast-heartbeat"，间隔 HEARTBEAT_INTERVAL_MS = 2000）
  → InjectClient.ping()                                    CastActivity.java:220-242
      ├─ 窗口到期（now > watchDeadline 或超过 WATCH_MAX_TOTAL_MS）
      │    → 松开，note = "看门已松开"                      InjectClient.java:172-180
      └─ shell.taskDisplay(pkg) → 不在目标屏 → shell.moveToDisplay(pkg, 目标屏)
           → 成功：watchMoves++，顺延窗口（不越硬上限），note = "已搬回 N 次"   :181-196
```

| 常量 | 值 | 含义 | 锚点 |
| --- | --- | --- | --- |
| `WATCH_WINDOW_MS` | 20000 | 初始窗口，只覆盖「启动之后应用可能自己跳走」的自适应期 | `InjectClient.java:104` |
| `WATCH_EXTEND_MS` | 8000 | 窗口内又发生一次搬回（说明还在乱跳）就顺延这么多 | `:107` |
| `WATCH_MAX_TOTAL_MS` | 45000 | 无论怎么顺延都不越过的硬上限 | `:110` |

**看门必须有界，这是设计而不是限制**：看门无法区分「应用自己跑掉」和「用户手动搬走」，无限期看门的后果是用户在桌面上点该应用、从最近任务拉它都会被搬回副屏，表现为「按了回不到前台」【实测踩过】。第二道保证是界面本身：`onPause` 里 `stopWatching()` 立即撤销（`:1380`）—— 用户在主屏点该应用图标、或从最近任务拉它时本界面必然 pause，看门必须在那一刻松手；`onDestroy` 也撤一次（`:1388`），因为界面没了就没人能松开它。

两条看门原语（`ShellChannel` 独占）：

| 能力 | 命令 | 锚点 |
| --- | --- | --- |
| 任务归属 | `dumpsys activity activities \| grep -E 'Display #\|Task\{'`（设备端先过滤：全量约 12000 行 → ~50 行，实测设备端 0.03 s） | `ShellChannel.java:605-623,641-642` |
| 搬到目标屏 | `am display move-stack <rootTaskId> <display>`，**搬完回查屏位才算成功** | `:656-666` |

回查那一步不能省：`am display move-stack` 失败时也会正常回话（甚至只回一句 "Nothing to do"），所以「命令有输出」根本不能当成功判据 —— 早期实现就是拿它当判据（`out != null`），`runQuickCast` 又无条件置 `quickCastOk = true`，于是搬屏没生效时上层会一路当成投屏成功【实测】。**「不知道」不能算达成。**

### 6.7 一键投屏与首开自动

两个入口共用同一份「快捷目标」：底栏「一键」按钮（`runQuickCast(target, false, false, null)`，`CastActivity.java:361-366`）与首开自动（后台跑同一个脚本、不显示界面，`:591-611`）。**目标写在资源里，不在代码里**（`res/values/quick_taps.xml`）：

```xml
<string name="quick_package">com.netease.cloudmusic.iot</string>
<string name="quick_activity">com.netease.cloudmusic.iot.app.LoadingActivity</string>
<string name="quick_label">网易云</string>
<string-array name="quick_taps"><item>com.netease.cloudmusic.iot:318:309</item></string-array>
```

`AutoCast.target()`（`:60-65`）按 package/activity/label 顺序读三元组，`tapFor()`（`:176-193`）解析「包名:横坐标:纵坐标」的补点落点表。为什么需要落点表（资源文件注释就是原因）：有些车机应用把「播放页」做成应用内部页面，既不开放深链也不响应语音广播 —— 网易云车机版 6.2.01 实测 `voice://` / `com.byd.action.AUTOVOICE_FULL_SCREEN` / `ncmcar://` 三条路全部回落首页，唯一稳定的入口是「点首页左栏封面」。

**一键脚本**（`runQuickCast`，`:620-711`）的顺序是刻意的：

1. `injector.watch(pkg, display, persistentWatch)` **先开看门再启动** —— `am start-activity --display N` 会让整条 root task 先跳到主屏，得先有人在后面拉回来；顺序反了就会出现「补点那一刻应用还在主屏」【实测：代理日志里"注入"早于"看门：搬回 display 2"】。
2. 后台线程查屏位 `injector.taskDisplay(pkg)`：`>= 0`（已有任务）→ `moveToDisplay` 搬过去，**不再 `am start`** —— `am start-activity --display N` 在目标屏上没有该包任务时会**新建**一条 root task，同一个应用会在两块屏上各跑一份、各有各的页面和动画【实测：网易云在 display 0 和 2 上同时活着】；搬屏返回 `moved[1] == 0` 时明确报失败（`cast_move_failed`），**绝不在这里报成功**。`< 0`（没有任务）→ `injector.launch(...)`。
3. `settleThenTap`（`:787-846`）：先轮询等目标包的 root task 真回到仪表屏（`TASK_POLL_INTERVAL_MS = 250`，上限 `TASK_SETTLE_TIMEOUT_MS = 8000`），再等窗口重排完才补点 —— 两档静默期：没搬动 `SETTLE_STEADY_MS = 250`，刚跨屏搬回来 `SETTLE_AFTER_MOVE_MS = 1500`（跨屏触发整窗重排，重排没完点击不会命中，实测 400 ms 时点击不命中）。
4. `tapUntilTargetPage`（`:730-768`）：闭环补点，见下。

**闭环补点：正面识别目标页。** 补点是盲的坐标点击，既不知道仪表屏上现在是不是目标应用，也不知道点完有没有反应。旧判据只回答「是不是首页」，调用方把「不是首页」当成「到了目标页」，而「不是首页」至少混了四种画面（目标页、启动白屏、加载黑屏、车机自己的地图），于是重启后冷启动时**一次都不点就报「已投屏并展开」**【实测踩过】。现在的判据是**两维三分类**（`DashboardEye.classify`，`:105-126`，标定为 1920×720 实拍帧）：

| 画面 | 导航带亮像素（y≈28..62、x≈650..1400） | 封面彩色像素（x≈290..645、y≈100..455） | 判定 |
| --- | --- | --- | --- |
| 歌词播放页（目标） | 0 ~ 21 | 4380 ~ 11621 | `LYRICS` |
| 网易云首页 | 3157 ~ 3160 | 79 ~ 3648 | `HOME`（79 那帧是首页画到一半，判 `OTHER`） |
| 启动白屏 | 25500 | **0** | `OTHER` |
| 车机地图 / 前一页 | 402 ~ 413 | 6486 | `OTHER` |

阈值 `BAND_HOME_MIN = 1500`、`BAND_TARGET_MAX = 50`、`COVER_ALIVE_MIN = 200`、`COVER_TARGET_MIN = 3000`（`DashboardEye.java:98-102`）。**分工要分明**：区分「首页还是歌词页」只由**导航带**承担（版式特征，稳健）；**封面彩色只用来证明「这一帧真的有内容」**（白屏整块高亮但没有颜色，实测 0）。早先把 `COVER_HOME_MIN` 定成 1000，等于让「封面有多鲜艳」参与版面判断，而同一个首页会随推荐封面在 958~3648 之间浮动，于是首页被判成 `OTHER`、补点闭环一次都不点【实测踩过】。

调用方规则（`:730-768`）：只有 `LYRICS` 算成功；`HOME` 才点，且 ≤ `TAP_MAX_ATTEMPTS = 3` 次；`OTHER` **继续等**（既不能点 —— 点在加载画面上是白费，也不能判成功）；预算用**总时长** `TARGET_DEADLINE_MS = 20000` 而不是固定次数；每轮之间 `PAGE_POLL_MS = 500` 轮询，点完一次等 `TAP_SETTLE_MS = 1200`；超时或抓帧失败（`frame == null` 归 `OTHER`）一律返回 false，界面如实报「未生效」。**「不知道」永远不能当成「到了」。**

**首开自动**（`AutoCast`）四个条件缺一不可（`shouldRun:133-155`）：开关开着、账号读得到且已登录、账号就是记下的那个、本次开机还没执行过。三个设计点：绑定的是**账号身份键**（`account.identity()`，优先 `userId`，读不到退 `photoUrl`、再退昵称）而不是昵称 —— 车机 Android 层只有一个用户，两个人开同一辆车在 Android 上是同一个人，区别只在 DiLink 账号；「本次开机」用 `bootMarker() = currentTimeMillis() - elapsedRealtime()` 判定（`:166-168`），同一次开机内稳定、重启后必然改变，不需要任何权限，容差 `BOOT_SAME_TOLERANCE_MS = 60000` 容忍校时；失败必须**回滚**「本次开机已执行」标记（`unmarkRan`，`:118-120`），否则用户只能重启车机才能再试，而且看不出发生了什么 —— 通道在 `AUTO_BIND_TIMEOUT_MS = 10000` 内没就绪就回滚 + 如实提示 + 退出（`CastActivity.java:575-589`）。

### 6.8 状态与持久化

| 位置 | 内容 | 锚点 |
| --- | --- | --- |
| `SharedPreferences "dashcast"` | `favorite_packages`（`StringSet`，包名）；`auto_enabled` / `auto_user_id` / `auto_user_label` / `auto_last_boot` | `Favorites.java:18-19`、`AutoCast.java:25-28` |
| `SharedPreferences "dashcast_boot"` | `attempt`（开机重试计数） | `BootReceiver.java:30-31` |
| 私有文件 `files/adb_identity` | ADB 身份（两行 base64：PKCS#8 私钥 + X509 公钥） | `AdbKeyStore.java:45,242-257` |
| 设备侧 `/data/local/tmp/dashcast-priv.log` | 特权进程 stderr（每次启动截断覆写，不累积） | `PrivilegedClient.java:78` |

设计取舍：收藏与一键目标都**存包名不存 label**（label 随语言/版本变，包名稳定）；账号存身份键不存昵称。**账号数据不落盘**：`CarAccount` 只在本机内存与状态栏使用，日志只记字段名与长度、不记值（`CarAccount.java:30-33,154-160`）。

### 6.9 应用清单管线

Android 11 起的包可见性过滤**按 uid 生效**：App 侧 `queryIntentActivities(MAIN/LAUNCHER)` 实测只得到 24 个，而 uid 2000 拿到的清单是 **18428 字节**输出 → **126 条组件行、按包去重 80 个**，排除 `com.android.*` / `android` 后界面实际展示 **70 条**【实测】。

```
InjectClient.listApps                                      InjectClient.java:404-421
  → shell.apps()                                           ShellChannel.java:566-592
      cmd package query-activities --brief -a android.intent.action.MAIN
      （COMPONENT_LINE 正则解析 `    pkg/activity`，按包去重，跳过 com.android.*）
  → 本进程 PackageManager 尽力补 label（labelOf，:424-435）；补不到就显示包名
      —— 能不能补到，取决于 §1 的 <queries> 声明
```

`dumpsys package` 不含 label 文本，解析 `resources.arsc` 又要 aapt，所以 label 只能在 App 侧补 —— 这也正是 `<queries>` 必须声明的原因。

---

## 7. 特权进程侧结构

### 7.1 启动流程

`PrivilegedProcess.main`（`:47-136`）逐段都带失败降级：任何一步抛异常都不能让进程「起来但什么都不能做」却装作正常。

| 步 | 动作 | 失败时的行为 |
| --- | --- | --- |
| 1 | 解析参数：`<targetDisplayId>`（默认 2）、`selftest`（只自检并退出，零副作用） | 非法参数沿用默认值，不因此起不来（`:56-63`） |
| 2 | `bypassHiddenApi()`：`VMRuntime.getRuntime()` + `setHiddenApiExemptions(new String[]{"L"})`，即全量豁免 | 只记日志，继续（`:69-74,307-316`） |
| 3 | `initContext()`：`Looper.prepareMainLooper()` + `ActivityThread.systemMain()` + `currentApplication()` + **`createPackageContext("com.android.shell", 0)`** | 拿不到就退出（`:76-86`）。为什么要 shell 的 package context：`systemMain()` 造的 Application 包名是 `android`，而 uid 2000 实际拥有的包是 `com.android.shell`，只有用它才有正确的 `opPackageName` 与权限归属 |
| 4 | `InputInjector.create(targetDisplay)` | 只记日志，触摸不可用（`:88-93`） |
| 5 | `new PreviewDisplay(targetDisplay)`：读目标屏 `DisplayInfo`，拿不到尺寸/图层栈就抛 | 只记日志，预览不可用（`:95-100`） |
| 6 | 两条腿都不可用 → 退出 | 不让一个什么都做不了的进程常驻（`:109-112`） |
| 7 | 注册 shutdown hook：**无论如何退出都 `destroyDisplay`** | 不在 SurfaceFlinger 里留垃圾（`:115-119`） |
| 8 | `broadcastReady(channel)` → `Looper.loop()` | 广播失败只记日志（`:121-134`） |

日志一律走 **stderr**（`[priv] ` 前缀，`:319-322`）：stdout 保持干净，将来若要接二进制流不会串。实测启动日志【实测】：

```
[priv] 启动 pid=20725 uid=2000 targetDisplay=2 selftest=false
[priv] hidden-api bypass OK
[priv] Context OK: packageName=com.android.shell
[priv] InputManager OK，注入目标 display=2
[priv] 预览目标 OK: display 2 (fission_bg_XDJAScreenProjection) 1920x720 layerStack=2
[priv] 已广播 com.byd.dashcast.PRIVILEGED_READY，等待 App 连接
[priv] SET_SURFACE -> true
```

### 7.2 IPC 服务端

`Channel extends Binder`（`:141-230`），手写 `onTransact`，没有 AIDL、没有生成代码；每个 case 第一句都是 `data.enforceInterface(DESCRIPTOR)`，事务号与入参见 §5.1 表。三个细节：`CODE_PING` 回的是 `Process.myUid()` 与 `targetDisplay`，客户端据此确认「对端是 uid 2000 的活进程」而不是一个已经死掉的句柄（`PrivilegedClient.ping:449-466` 要求 `reply.readInt() == 2000`）；`CODE_SET_SURFACE` 允许 `Surface` 为 `null`（等价 detach）并返回布尔结果，这个返回值就是 App 决定「继续直通还是转降级」的依据；**曾经注册过一个 `ACTION_STOP` 广播接收器，已删除** —— 实测在 `app_process` 进程里**必然抛 `SecurityException`**（`Unable to find app for caller`，这个进程没有向 AMS 注册过 app 记录），是一条永远走不通的死路（`:123-126`）。

### 7.3 预览：建屏、接屏、回收

`PreviewDisplay`（`PreviewDisplay.java:31`）的全部工作就是复刻原版的画面通路：**让 SurfaceFlinger 把仪表屏那块屏的图层栈同时合成到另一块 Surface 上**。

```
① 构造：DisplayManagerGlobal.getDisplayInfo(2)（反射）
        → layerStack = 2、logicalWidth/Height = 1920/720、name
② attach(surface)：
        SurfaceControl.createDisplay("dashcast-preview", false) → token
        openTransaction
          setDisplayLayerStack(token, 2)                  ← 复用同一批 layer
          setDisplayProjection(token, 0, src, dst)         ← src = dst = (0,0,1920,720)
          setDisplaySurface(token, App 递来的 Surface)      ← 输出改道
        closeTransaction
③ detach()：destroyDisplay(token)
```

四个必须守住的细节：**全部走反射**（这七个方法在 SDK 32 的 `android.jar` 里是 `@hide`；`getDisplayInfo` 走 `DisplayManagerGlobal.getInstance()`，`layerStack` / `logicalWidth` / `logicalHeight` / `name` 虽然是 `DisplayInfo` 的公开字段，但类型本身 `@hide`，所以也要反射读，`:28-30,131-151`）；**提交失败必须回收**（`committed` 标志兜底：三步中任何一步抛异常都要 `destroyDisplay` 掉刚建的 display，否则它会一直挂在 SurfaceFlinger 里，`:85-109`）；**`attach` 可重入**（一进来先 `detach()`，TextureView 重建走的就是这条路，语义是「先销毁旧的再建新的」，不是叠加，`:70-73`）；**`detach` 对目标屏没有任何副作用**（`layerStack` 是共享的，只销毁自己建的那块）。

实测：绑定期间抓 display 2 画面完整正常；解绑后 `dumpsys SurfaceFlinger --display-id` 只剩 `HWC display 0`，无残留虚拟屏【实测】。**这不是截图、不是 readback、不是编码**：没有 CPU 回读、没有 PNG 编码、没有传输协议 —— 所以 App CPU 实测 **1.5%**（`/proc/<pid>/stat` 计 9 ticks / 6 s，8 核），特权进程 **0 ticks（≈0%）**，App 线程总数 21【实测】。

### 7.4 输入注入

`InputInjector`（`InputInjector.java:21`）反射封装 `InputManager`：

| 项 | 值 | 为什么 |
| --- | --- | --- |
| 注入模式 | `INJECT_MODE_ASYNC = 0`（`InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`） | 不等待分发完成，延迟最低（`:23-24`） |
| 取 manager / 注入 | `InputManager.getInstance()`、`injectInputEvent(InputEvent, int)`（反射） | `@hide`；注入需要只有 uid 2000 才有的 `INJECT_EVENTS` |
| 目标屏 | `InputEvent.setDisplayId(int)`（反射；定义在 @hide 的 `InputEvent` 基类上，MotionEvent/KeyEvent 都能用） | 一次注入只送到目标屏 |
| 触摸事件 | `MotionEvent.obtain(downTime, eventTime, action, x, y, 0)` + `setSource(SOURCE_TOUCHSCREEN)`，`finally` 里 `recycle()` | 注入的是**真实触摸屏语义**的事件；不回收会持续占用对象池 |
| 按键事件 | `new KeyEvent(now, now, action, keyCode, 0)` | `KeyEvent` 不是从对象池 obtain 出来的，不需要 recycle |
| 时间戳基准 | `SystemClock.uptimeMillis()` | App 与特权进程在同一台设备上，**App 直接把收到的 `getDownTime()` / `getEventTime()` 原样传过来即可**（`:18-19`） |

`PrivilegedClient.tap(x, y)`（`:403-408`）一次完整点按 = DOWN + UP 两次 oneway，共用同一个 `downTime`、`eventTime` 相差 `TAP_HOLD_MS = 40`。**两个时间戳必须不同**：完全相同的手势在 InputDispatcher 侧会被看作长度为 0 的异常事件，部分应用会判成长按而漏掉点击（`:396-402`）。

### 7.5 生命周期：跟着界面可见性走，且不留孤儿

```
onResume   → heartbeat 起来 + startPreviewIfReady → PrivilegedClient.start(displayId)
onPause    → stopWatching() + stopPreviewIfRunning() → stop()
              stop() = CODE_SHUTDOWN（让它自己 destroyDisplay 再退出）+ 无条件 pkill 兜底
onDestroy  → 同上，外加收掉心跳线程与 touchExecutor
```

**进程被强杀（`am force-stop` / LMK）走不到 `onPause`**，所以还有第三道保证：App 把 token 交给对端 `linkToDeath`（§5.1 的 `CODE_ATTACH_CLIENT`），**App 一死对端自己退出**，退出走 `System.exit` → shutdown hook → `destroyDisplay`。`shutdownSoon()`（`:265-274`）之所以要 `Handler(Looper.getMainLooper()).post`：Binder 线程里不能直接 `System.exit`。`watchClientDeath`（`:241-256`）处理了一个容易漏的细节：`linkToDeath` 抛 `RemoteException` 的含义就是「对端已经死了」（而不是「挂载失败」），这时应当直接收工退出。

### 7.6 「画面已上屏」的判据住在 App 侧

特权进程只回答「接上了」（`SET_SURFACE -> true`），**「在出帧」由 App 侧的 `onSurfaceTextureUpdated` 判定**（§6.5）。这条分工是有意的：出帧是 SurfaceFlinger 的行为，只有消费者这一侧能看到。实测 `SET_SURFACE -> true` 之后回调随即触发【实测】。

---

## 8. 提权链：uid 2000 从哪来

这是「不 root、不刷机、不需要电脑」的全部含金量所在。链路：`AdbKeyStore`（取密钥）→ `AdbClient`（握手 + 通道）→ `ShellChannel`（全部特权操作的唯一出口）；`AdbBootstrap.provision()` 是唯一入口，引导页 / 界面 / 开机接收器共用。

### 8.1 线协议客户端

| 项 | 值 | 锚点 |
| --- | --- | --- |
| 目标 | `127.0.0.1:5555`（车内回环，全程不需要 PC） | `AdbClient.java:80-81` |
| 消息头 | 24 字节小端：`command, arg0, arg1, data_length, crc32, magic` | `:38-44` |
| 命令 / 版本 | `CNXN` `AUTH` `OPEN` `OKAY` `WRTE` `CLSE`；`A_VERSION = 0x01000001` | `:52-59` |
| `MAX_PAYLOAD` | **1 MB**（对齐 AOSP 的 `MAX_PAYLOAD`） | `:71` |
| 握手 | 发 `CNXN` 带 `"host::\0"`；收到 `AUTH arg0=1` 回 `arg0=2` 签名；收到 `AUTH arg0=3` 才回公钥 | `:154-200` |
| 结果分类 | `State` 四态：`READY` / `NEED_AUTHORIZATION` / `UNREACHABLE` / `FAILED` | `:84-93` |

`MAX_PAYLOAD` 那条注释值得单独读（`:60-71`）：**这是 170 KB 级抓帧流的吞吐命门**。声明 4096 时 adbd 每包只发 4 KB，一帧 PNG 要拆成 42 个 `A_WRTE`，而 ADB 协议**每个包都要回一次 `A_OKAY`** —— 帧时间被 42 次往返吃掉。实测症状很典型：App 进程 CPU 只有 4.4%，帧率却卡在 3 fps，也就是「根本没在算，全在等」。adbd 取两端较小值，所以声明大了不会被拒。

**签名必须自己做 PKCS#1 v1.5**：adbd 发来的 20 字节 token **本身就是一个 SHA-1 摘要**，客户端必须做 `RSA_sign(NID_sha1, token, 20, ...)` —— 把 token 直接当作 digest 塞进 PKCS#1 v1.5 的 DigestInfo，**不能再哈希一次**（`signToken:230-234` + `sha1DigestInfo:237-242`，前缀 `30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14`，`:75-78`）。用 JCA 的 `"SHA1withRSA"` 会先算 `SHA1(token)` 再签，必然验签失败，现象是 adbd 反复重发同一个 token 直到放弃。

**`requestAuthorization` 是安全阀，不是可选项**：

| 传 `false`（界面 / 探测 / 开机路径） | 传 `true`（只有引导页在用户点过「开始授权」之后） |
| --- | --- |
| 只签名。连试三次都不认就报 `NEED_AUTHORIZATION` 并收手，**绝不发送公钥**（`:167-176`） | 签名被拒时发送公钥，让车机弹「允许 USB 调试吗」（`:177-190`） |

为什么必须区分：发公钥会弹出车机对话框，而那个对话框**绑在发起它的那条连接上**。若调用方随后超时断开（快速探测、开机路径都可能），窗口就变成一具**收不到任何输入的孤儿**（点、BACK、ESC、`am force-stop com.android.systemui` 全部无效），还会挡住后面真正需要点的对话框。

### 8.2 ADB 身份

`AdbKeyStore.loadOrCreate`（`:101-129`）的优先级：

| # | 来源 | 说明 |
| --- | --- | --- |
| 1 | 资产 `assets/adb_identity.pk8`（PKCS#8 DER） | 只有构建时传 `-BundledKey` 才存在；公钥由私钥推导，包里只需放一份私钥 |
| 2 | 私有文件 `files/adb_identity` | 首次自建的身份，两行 base64（PKCS#8 私钥 + X509 公钥） |
| 3 | 现场生成 RSA-2048 | 车机上约需数百毫秒到 1 秒，**不要在主线程调用** |

三条配套事实：**公钥编码必须是 524 字节的 `ANDROID_PUBKEY_STRUCT`**（全小端：`nwords`、`n0inv`、模数 `n`、`rr`，**不带指数** —— adb 约定指数恒为 65537，`:262-295`），人可读形式是 `<base64> dashcast@byd`（`:297-300`），adbd 的 `adb_keys` 里按这个注释名认；**自检 `consistent(priv, pub)`**（`:138-153`）用**真实的签名路径**签一个假 token 再用公钥还原比对 —— 私钥与公钥一旦不配对，我们发出去的公钥会被 adbd 存下，但每次签名都验不过，现象是「每一条连接都重新弹授权框、却又总能连上」，表面上功能正常，实际上一直在借系统自动放行，极度难查；**`tryLoad` 失败即删档重建**（`:109-116`），代价是车机重新弹一次授权框。

> **【静态·必须知道的现状】** `AdbKeyStore.write`（`:242-257`）是**直接覆写**（`new FileOutputStream(file)` → `write` → `flush` → `close`），**没有临时文件、没有 `getFD().sync()`、没有回读校验**。开发主线记录过一个根因：车机掉电后，非 fsync 的覆盖写会以「长度正确、内容全 0」的形态回来，旧实现于是静默重新生成密钥、车机重新弹框。**当前源码里没有针对这一类的结构性防护**，只有 `consistent()` 自检 + 删档重建这条后路（§12.2）。

### 8.3 授权持久化：三条必须写清楚的事实

| 事实 | 依据 | 对使用者的含义 |
| --- | --- | --- |
| adbd **每条连接都重读** `/data/misc/adb/adb_keys` | 【实测】开发主线记录：`adbd_auth: loading keys from /data/misc/adb/adb_keys` 紧跟 `adb client authorized`；`isKeyAuthorized()` 在该文件里没有调用点 | 授权**即时生效**，不需要重启 adbd 或车机 |
| 弹框与否**只由 adbd 决定**，`AdbDebuggingManager` 收到 `PK` 是无条件弹框 | 【静态·AOSP】同上记录 | 想「点一次以后不再弹」，用户**必须勾选「一律允许使用这台计算机进行调试」**：只点「允许」不会把公钥写进 `adb_keys` |
| 「一律允许」的授权有 **7 天**静默期（`adb_allowed_connection_time` 代码默认 `604800000 ms`，到点 `filterOutOldKeys()` 把密钥从 `adb_keys` 物理删除） | 【实测·开发主线；本发行版源码里查不到任何补偿代码】 | **7 天内至少成功连接一次，授权才保得住**；长期不动车会重新弹框 |

`GuideActivity` 的引导文案（`:97-149`）就是按这三条写的：一次性授权、必须点「允许」、以后不再需要这一步。与开发主线历史记录的差别要说清楚：历史版本里有 `makeAuthorizationPermanent()` 把 `adb_allowed_connection_time` 写成 `0`（语义是「永远允许既有授权」），但**当前源码树里既没有这个方法、也没有任何 `settings put` 调用** —— `AdbBootstrap` 现在只做「载密钥 → 连接 → 交给 `ShellChannel`」（`AdbBootstrap.java:58-84`）。

### 8.4 两个服务名，不能混用

| 服务 | 用在哪 | 为什么不能换 | 锚点 |
| --- | --- | --- | --- |
| `shell,v2,raw:` | 全部文本命令 | 旧的 `shell:` 让 adbd 给命令挂一个 **pty**，命令于是退化成「某个会话里的作业」，会话一回收就被连带清掉。实测 `app_process … &` 在这种通道下只会在日志里留下一行重定向凭据、进程从不出现；同一条命令走 PC 上的 `adb shell`（即 `shell,v2,raw:`）一次就成了 —— **唯一变量是通道，不是命令**。`raw` 让 adbd 不分配 pty，子进程自成会话 | `AdbClient.java:263-277` |
| `exec:` | 取二进制（抓帧） | 不经过 shell/pty，stdout 二进制透明 —— 这正是 `adb exec-out` 那条路。`shell,v2,raw:` 实测取不回完整 PNG（`screencap -p` 只回来 6 字节，正好是 PNG 魔数 `89 50 4E 47 0D 0A`），base64 绕行同样被截断。代价是**不能带管道或重定向**，只能是一条命令 | `:279-301` |

**v2 正文是分帧的，必须按帧循环解析**（`SHELL_V2_HEADER = 5`，`:257`）：帧格式 `[Id:1][length:4 小端][payload]`，`Id` 里 `1=stdout`、`2=stderr`，其余（退出码、关 stdin、窗口变化）不是正文（`:259-261`）。两个实测坑：**一个 ADB `WRTE` 里可能并排多个帧**（那条 18428 字节的清单输出被 adbd 压成 3 帧塞进同一条 18443 字节的 `WRTE`：`18443 == (5+8192) + (5+8192) + (5+2044)`，所以既不能「一个 WRTE 当一个帧」，也不能「只跳 Id 那 1 字节」）；**一帧会横跨多条 `WRTE`**，残帧必须留给下一包。现在由 `AdbClient.ShellV2Parser` 流式解析（按帧循环、残帧留到下一包、非 stdout/stderr 的帧只跳过）。**这个坑修过一次、在重构里被连带删掉、又原样重现过**【实测】—— `ShellFrames` 这个类名已经不存在。

`openStream` 另外三条必须守住的规矩（`:310-378`）：

| 规矩 | 不守的症状 |
| --- | --- |
| `WRTE` 必须按 **`m.arg1 == localId`** 过滤（`arg0` 是 adbd 的 id、`arg1` 才是我们的） | 同一 socket 上并发多条流时数据互混，实测表现是「PNG 只回来 13 字节 / 偶发 62 字节」 |
| 回 `A_OKAY` 时远程 id 取 **`m.arg0`** | 应答发给错误的 id，流建立不起来 |
| 收尾必须补发 `A_CLSE` | adbd 认为这条流还开着，继续灌残留数据干扰后面的流 |

`close` 只关 socket；连接坏了由 `ShellChannel` 丢弃并靠记住的 `appContext` 重连（`ShellChannel.java:341-347`）。

### 8.5 失败面（`AdbBootstrap.describe`，`:106-121`）

| 状态 | 用户可见文案与下一步 |
| --- | --- |
| `NEED_AUTHORIZATION` | 车机还没授权本应用：请在弹出的「允许 USB 调试吗」对话框上点「允许」 |
| `UNREACHABLE` | 连不上 `127.0.0.1:5555` —— 车机的无线 ADB 没有开启，请在「开发者工具」里打开无线 ADB 开关 |
| 默认（`FAILED`） | ADB 握手失败 |
| 已获得 shell 但特权进程没起来 | 状态行/降级原因里带上具体原因 + 特权进程日志尾部（`tail -n 6`） |

契约是「状态 → 面向用户的中文说明 + 可执行的下一步」。

### 8.6 开机自举

`BootReceiver`（`BootReceiver.java:39-88`）收 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，`goAsync()` + 后台线程 `dashcast-boot` 里跑 `AdbBootstrap.provision(TIMEOUT_BACKGROUND_MS, false)`。**为什么要退避重试而不是开机跑一次就算**：开机时序里 adbd 并不是一开始就监听 TCP 5555 —— 这条链是 `init 读 sys.connect.adb.wiress=1` → `setprop service.adb.tcp.port 5555` → 重启 adbd，而 `sys.connect.adb.wiress` 是 `com.byd.appserver`（持久化系统应用）在自己的进程启动时才写入的；`BOOT_COMPLETED` 到达我们时，AppServer 很可能还没跑完。

| 条件 | 行为 |
| --- | --- |
| 连接就绪 | `attempt` 清零，结束 |
| `NEED_AUTHORIZATION` | **不重试**（再试一百次也没用），交给引导页 |
| 其它失败 | 按 `RETRY_DELAYS_MS = {0, 15s, 30s, 60s, 120s, 120s}` 用 `AlarmManager` 排下一次，总跨度约 5 分钟 |

**但这条链的真机效果尚未验证通过** —— 见 §12.1。

---

## 9. 构建链

无 Gradle，纯命令行七步（`apk/build.ps1`）：

| 步 | 命令 | 行 |
| --- | --- | --- |
| 1 | `aapt2 compile --dir res -o out\res\res.zip` | `:73-76` |
| 2 | `aapt2 link -o out\apk\base.apk -I android.jar --manifest … -R res.zip -A assets --java out\gen --min-sdk-version 26 --target-sdk-version 32 --auto-add-overlay` | `:78-81` |
| 3 | `javac -source 8 -target 8 -encoding UTF-8 -bootclasspath android.jar -d out\classes`（`src/` + 生成的 `gen/`） | `:83-90` |
| 4 | `jar cf out\classes.jar -C out\classes .` + `d8.bat --min-api 26 --lib android.jar --output out\dex` | `:92-97` |
| 5 | **`aapt.exe add <apk> classes.dex`** | `:99-121` |
| 6 | `zipalign.exe -f -p 4` | `:123-126` |
| 7 | `apksigner.bat sign` | `:190-195` |

三个必须知道的约束：

- **第 5 步为什么不能用 zip 重打包**（`:99-102`）：`resources.arsc` 必须保持 `STORED`（method 0）才能在 API 30+ 安装；.NET 的 `CompressionLevel.NoCompression` 仍会输出 deflate（method 8），只有 `aapt add` 会追加新条目并保持既有条目字节不变。脚本 `:113-121` 会**校验**这一点（打印 `resources.arsc stored=… classes.dex present=…`）并在失败时退出。`aapt` 按传入路径命名新条目，所以脚本先 `Push-Location out\dex`。
- **SDK 路径按环境变量解析，不写死**：`build.ps1:18-25` 依次取 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk`；JDK 取 `$env:JAVA_HOME`（未设置时当场报错退出）。脚本开头的 `preflight` 会打印解析出的 SDK 路径与四个工具的存在性，**并在缺依赖时直接失败退出** —— 不这样做的话，它会带着错路径继续跑，最后在某一步抛一个与真实原因无关的异常。
- **不打包任何载荷**（`:40-52`）：删除历史遗留的 `assets/dashcast-agent.jar`，打印 `agent jar: not shipped (privileged code lives in this APK dex)`。可选地由 `-BundledKey <pk8>` 写入 `assets/adb_identity.pk8`；**不传该参数而资产已存在时，脚本会删掉它并打印 WARNING** —— 因为删掉之后 App 会现场生成新密钥、adbd 会拒绝、用户会看到授权对话框，静默地交付一个行为不同的包是必须显式说出来的事。

签名身份（`:128-188`）按优先级取：

| # | 来源 | 说明 |
| --- | --- | --- |
| 1 | 命令行 `-Keystore / -StorePass / -KeyAlias / -KeyPass` | 显式传参优先级最高，口令不落在文件里 |
| 2 | 环境变量 `CE_KS_FILE / CE_KS_PASS / CE_KEY_ALIAS / CE_KEY_PASS` | 先读**进程**环境变量，再回退 User 注册表（实测 `CE_KS_FILE` 在 User 作用域可能读到空值） |
| 3 | `apk/debug.keystore` | 缺失时脚本自己 `keytool -genkeypair`（`CN=dashcast,O=byd,C=CN`，自签名） |

**给车机上已有的安装升级，必须用同一把签名密钥**，否则 `adb install -r` 直接失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装并丢数据。（旧文档说换签名还有第二个后果 ——「代理不会再服务它」—— 那条属于已删除的信任域模型：投屏只依赖 shell 通道，与签名身份无关。）

安装（`:197-211`）：`-NoInstall` 可跳过；`-Serial` 留空时从 `adb devices` 自动取第一个 `device` 状态的目标（**不要写死 IP**：车机是 DHCP，网段换过一次，写死的那份必然过期）；找不到设备就报错退出。产物 `apk/dashcast.apk`。链接顺序上**没有前置步骤**：只有一个 `apk\build.ps1`，不再有 `agent\build.ps1`。

---

## 10. 二次开发扩展点

### 10.1 换「一键」目标或补点落点（不需要碰代码）

改 `apk/res/values/quick_taps.xml` 一处即可（`quick_package` / `quick_activity` / `quick_label` / `quick_taps` 数组项）。三条注意：

- `quick_activity` 要填**真正停在仪表屏上的那个 Activity**，不是 LAUNCHER 入口。实测踩过：原来填的是桌面入口 `.app.LoadingActivity`（一个闪屏，它自己再拉 `CloudMusicRNActivity`），而这一次启动不带 `launchDisplayId`，AMS 按默认屏解析 —— 整条 root task 被从仪表屏挪回 display 0（同一个 `taskId=17`：t+500ms 在目标屏，t+1000ms 已在 display 0）。看门能搬回来，但用户会先看见应用闪到主驾屏上。
- 落点格式 `包名:横坐标:纵坐标`（仪表屏 1920×720 坐标系，与预览区 1:1）。不写这条就只投屏、不点（`tapFor` 返回 null → `tapUntilTargetPage` 直接返回 true，`CastActivity.java:731-734`）。落点值必须**实测**：把应用投到仪表屏、抓 display 2 的截图、量出目标控件中心。
- 换掉应用后 `DashboardEye` 的判页阈值也要重新标定：现在的两维阈值是**为网易云标定的**（§6.7）。换一个应用时 `LYRICS` / `HOME` 的语义不再成立，要么按同样方法重新定标，要么把判页降级为「不判页、只投屏」（去掉 `quick_taps` 条目）。

### 10.2 加一条 Binder 能力（三处同时改，但没有版本漂移）

1. `privileged/PrivilegedProtocol.java`：加 `CODE_*` 常量（从 8 起，1-7 已占用；Binder 自己占用了 0 和一些负数）。
2. `privileged/PrivilegedProcess.java`：在 `Channel.onTransact` 加 `case` + 实现私有方法。**入口没有 uid 裁决**（§4.2）—— 若这条能力比现在更危险，先补裁决再实现。
3. `PrivilegedClient.java`：加公开方法。要回执就自己 `transact(code, data, reply, 0)` + `reply.readInt()`；fire-and-forget 用 `IBinder.FLAG_ONEWAY` 且 `reply` 传 `null`。

改完**编译一次、装一次**即可：两侧在同一个 dex 里，不存在「旧对端 + 新客户端」的错位读（§5.3）。

### 10.3 加一条 shell 能力

改 `ShellChannel`（加原语）+ `InjectClient`（编排 + 状态）。三条纪律：

- **锁的分工不能破**：主连接 `lock`（交互命令 + 判页抓帧）、`previewLocks[0..3]`（预览抓帧），嵌套方向只能是 `previewLock → lock`（重连路径要先备好密钥），**任何已持有 `lock` 的代码都不许再取预览锁**（`ShellChannel.java:95-101`）。理由可以量化：抓一帧要独占 0.3 s 量级，若与触摸共用一条串行化连接，每次触摸平均要多等半帧。
- **二进制一律走 `exec:`**，不要试图在 `shell,v2,raw:` 上取二进制（§8.4）。
- **禁止在主线程做 socket IO**：会抛 `NetworkOnMainThreadException`，而且连接被丢弃后**断了连不回来**（通道记住 `appContext` 就是为了自动重连，`ShellChannel.java:115-119`）。界面侧心跳因此跑在独立的 `HandlerThread` 上（`CastActivity.java:205-212`）。

### 10.4 改界面

| 界面 | 代码 | 布局 |
| --- | --- | --- |
| 主界面（预览 + 触控板 + 操作条） | `CastActivity`：`bindUi:386-400`、`refreshStatus:1415-1443`、`AppAdapter:1464-1502` | `activity_cast.xml` |
| 应用列表面板 | `setPanelOpen:875-880`、`rebuildList:883-909` | `panel_apps.xml` |
| 列表行（星标 + 应用名） | `AppAdapter.getView:1482-1501` | `item_app.xml` |
| ADB 授权引导 | `GuideActivity.buildUi:153-210` | **无布局资源**，代码搭（避免为一个页面引入资源） |

四条硬约束（都实测过）：

- **预览区内部子视图顺序不能动**：`preview`(TextureView) → `previewFallback`(ImageView) → `touchPanel` → `panel_apps`。触控板必须压在预览之上、应用面板之下；早期把触控板写成裸 `View` 时 `onTouch` 完全不触发，改成带背景的 `FrameLayout` 并保持这个顺序后才通（`activity_cast.xml:13-18`）。
- **根布局是竖向 `LinearLayout`**：预览区 `weight=1` 占满操作条以上的空间，操作条是它的兄弟。之前是 `FrameLayout` + 操作条 `layout_gravity="bottom"`，两者互相叠加，预览 `center_vertical` 落在 y=177..897，操作条从 y=894 起把最下 3 行盖住。改成本结构后 720 行全部可见，而且操作条高度将来一变，预览自动跟随（`activity_cast.xml:2-12`）。
- **尺寸单位用 `px`**（车机固定 1920×1080 @240dpi，1dp = 1.5px），预览区是 1:1 的 1920×720，所以触控板的 View 局部坐标就是仪表屏坐标。
- **主题固定深色**，唯一令牌表是 `res/values/styles.xml` 的 `CastTheme`；**没有 `colors.xml`、也没有 `values-night/`**，颜色直接写在布局与 Java 里（`TOUCH_PANEL_IDLE_COLOR = 0x1A2E9BFF` 等，`CastActivity.java:101-104`）。`CastTheme` 那两个属性是有原因的（`styles.xml:2-13`）：「窗口是否半透明」和「窗口背景」在窗口创建时就按 **manifest** 主题定死了，`onCreate` 里的 `setTheme()` 改不动它们 —— 实测首开自动分支运行时改主题，主屏仍然黑屏约 2 秒。**连带后果**：那条分支里 `status` / `preview` / `touchPanel` 全是 `null`，`applyPreviewVisuals`（`:1049-1059`）与 `applyPreviewMode`（`:1069-1073`）必须先判空 —— 不判空就是一次空指针崩溃（实测踩过：进程当场死亡、脚本中断）。

### 10.5 加一个派生包（比如只服务某一个应用的分支）

要求是**每处身份都必须从包名派生**，不要硬编码：

| 项 | 怎么做 | 为什么 |
| --- | --- | --- |
| `NICE_NAME` | 已经是 `APP_PACKAGE + "-priv"`，改包名即可 | 清理用 `pkill -f`（按整条命令行正则匹配），若两个包都叫 `dashcast-priv`，各自的 `start()`/`stop()` 会把对方的进程一起杀掉，表现为「预览随机消失」 |
| `ACTION_READY` 回传 | 已经是 `setPackage(APP_PACKAGE)` | 只有本包收得到句柄 |
| 签名 | 与母工程同一把 | 覆盖安装/升级的前提；与特权通道无关 |
| `keepWatchAfterTarget()` | 子类覆盖（`CastActivity.java:567-569`） | **注意：当前它没有实际效果** —— 见 §12.2 |

### 10.6 不要做的事

- **不要让特权进程建窗口、建 `VirtualDisplay`、碰 provider**：那需要 App 的真实进程环境（§2）。
- **不要把「投屏」实现成「自建一块 VirtualDisplay」**：早期版本自建了一块 `FLAG_PRIVATE` 的「dashcast」虚拟屏投进去，结果只在主屏预览窗口里显示，应用根本没上仪表盘【实测】。
- **不要投 display 2，也不要投 display 4**（§6.3）。
- **不要把抓帧目标指向投屏槽位（3/4）**：恒为全黑；预览与注入必须指向同一块屏（主投影屏 2），否则会出现「预览显示 A 屏、点击落在 B 屏」—— 那比没有预览更糟。
- **不要在 App 进程里枚举已安装应用**（包可见性过滤，§6.9）。
- **不要用 `shell:` 代替 `shell,v2,raw:`，也不要在 v2 通道上用 shell 语法取二进制**（§8.4）。
- **不要在非引导路径传 `requestAuthorization = true`**（§8.1）。
- **不要把「命令有输出」当成成功判据**（§6.6 的 `move-stack`）；「不知道」一律按「未达成」处理。
- **不要在主线程做 socket IO / Binder 往返**（§6.4、§10.3）。

---

## 11. 辅助模块

| 模块 | 定位 | 关键事实 |
| --- | --- | --- |
| `tools/dashcast.ps1` | 纯 adb 命令版驱动，**不安装任何软件**。动作：`list` / `up` / `down` / `touch` / `swipe` / `key` / `text` / `shot` / `restore`，默认 `-Display 2`，抓图落到 `work\dashcast\`（可 `-OutDir` 改） | 只用车上本来就有的命令：`am start --display`、`input -d`、`screencap -d`、`am force-stop`。`up` 不带 activity 时用 `cmd package resolve-activity --brief` 在设备端解析 LAUNCHER 入口。`restore` 会 `am force-stop mark.via` 与 `carrot.icecream`（早期演示用的两个第三方应用），不是通用清理命令 |
| `apk/res/values/quick_taps.xml` | 一键目标三元组 + 补点落点表 | 见 §6.7、§10.1 |

`tools/dashcast.ps1` 顶部注释里的显示拓扑是**早期观测**（写着 display 5 = `remote_dashboard`），与当前实测拓扑（0 / 2 / 3 / 4，§6.3）不一致 —— 代码不依赖那段注释，`list` 动作是直接 `dumpsys display` 现场打印。**以 `dumpsys display` 的输出为准。**

---

## 12. 已知边界与未确认项

### 12.1 未实测（明确没有人量过）

| # | 事项 | 现状 |
| --- | --- | --- |
| 1 | **开机自启（`BootReceiver`）在干净条件下是否必然成功** | **未验证通过**。两次开机实测都没触发：events 缓冲区里本包进程的启动原因是 `pre-top-activity`，**不是 `broadcast`**。最可能是**实测方法本身的错误** —— 验证前跑过 `am force-stop`，会给包置上 stopped 标志，而 Android 不给 stopped 状态的包投递 `BOOT_COMPLETED`。判定方法：确认 `dumpsys package com.byd.dashcast \| grep stopped` 为 `stopped=false` → 重启 → **全程不点任何图标** → 进程启动原因应为 `broadcast`。另一待排除项：BYD ROM 对第三方应用的 `BOOT_COMPLETED` 有限制。**结论：这条特权通道目前不能保证开机后自动存在**，不能让用户以为「装完就永远不用管」 |
| 2 | **端到端触控延迟** | **没有测过**。已实测的是**保真度**（主屏预览区注入 480 px 拖动 → 仪表屏地图跟着平移 480 px，坐标 1:1 无缩放误差）与**注入开销**（拖动期间 52 个注入事件只花特权进程 2 ticks CPU；一次注入 = 一次 Binder oneway）。「手指落下 → 仪表屏画面变化」的端到端时延没有任何数字 |
| 3 | **看门恢复延迟** | **没有测过**。旧代理架构下测到过「人为 `am display move-stack <id> 0` 之后 ~629 ms 回到仪表屏」，那是**旧实现**的数字，不能套到这里。当前实现的巡检节拍是心跳 2000 ms + 一次 `taskDisplay`（设备端 0.03 s）+ `move-stack` + 回查 `taskDisplay`，所以恢复延迟的量级是「一个心跳周期」——**这是推断，不是实测** |
| 4 | 重启车机后**立即冷启动**这一档补点闭环 | 只验到「应用冷启动」（force-stop 后重启）。机制上已覆盖（加载窗口一律判 `OTHER` → 继续等），但**没验就是没验** |
| 5 | 一次 17 s / 2 次点击的慢例 | 第一次点击落到非目标页，闭环正确地没误判、等到回首页才补点成功；**慢的原因未定位** |
| 6 | 特权进程是否 `CapEff=0` | 本进程**没有单独量过**；原版同形态进程实测 `CapEff=0`【推断同为零】 |
| 7 | ADB 会话回收后预览连接组的自愈 | 代码里有重连路径（`reconnectPreviewLocked`），但**没有做过 adbd 重启之类的破坏性验证** |

### 12.2 结构性边界（源码里就能看到的）

| # | 事项 | 事实与影响 |
| --- | --- | --- |
| 1 | **`AdbKeyStore.write` 不是持久化写入** | `:242-257` 直接 `new FileOutputStream(file)` 覆写：先截断再写、**没有 `fsync`、没有回读校验、没有临时文件 + rename**。开发主线记录过这一类的根因（掉电后「长度正确、内容全 0」），当前后路只有 `consistent()` 自检 → `tryLoad` 返回 null → **删档重建**（`:109-116`），用户看到的是「又要授权一次」 |
| 2 | **授权 7 天窗口没有任何补偿** | 当前源码里没有 `makeAuthorizationPermanent`、没有 `adb_allowed_connection_time` 的任何写入（历史版本有）。依赖系统默认 604800000 ms：7 天内至少成功连接一次，否则密钥会被 `filterOutOldKeys()` 删掉、重新弹框 |
| 3 | **特权进程不做调用方 uid 裁决** | `Channel.onTransact` 只有 `enforceInterface`，7 个 case 全无 `getCallingUid()` 检查；约束只来自 `setPackage` 定向广播。**复用到多客户端场景必须先补裁决**（§4.2） |
| 4 | **`keepWatchAfterTarget()` 当前是空操作** | `InjectClient.watch(pkg, display, persistent)` 的 `persistent` 形参**在方法体里没有被使用**（`:211-225`）；看门时长完全由三个 `WATCH_*` 常量决定。所以派生包覆盖它**不会改变行为**；要真的「送到即撤」得改 `watch()` 或调用点 |
| 5 | **`adoptAgentWatch()` 在当前实现下不会生效** | 它依赖 `injector.lastWatch()`（`CastActivity.java:852-862`），而 `InjectClient` 是每次 `prepareSession()` **新建的实例**（`:406`），新实例的 `lastWatch` 为 null；首开自动留下的看门本来也会随 Activity `onDestroy` 的 `stopWatching()` 撤销，没有实际残留状态需要「认领」—— 这段是旧代理架构的遗留逻辑 |
| 6 | **`InjectClient.currentDisplay` 是死字段** | `setDisplay()` 只写不读（`:201-204`），`touch`/`tap`/`captureFrame` 都走 `inputDisplay()`；它的注释仍写着「touch 这条老接口没有 display 参数，所以记住它」—— 那是旧实现的形状 |
| 7 | **PNG 体积口径不一致** | 代码注释写「1920x720 的 PNG 约 765 KB」（`ShellChannel.java:51,69`），而实测的两个值是 **170514 B**（`openStream[exec:]: 远端关闭，已收 170514 字节`）与 **485299 B**（2026-09-20 复核，display 2）。PNG 体积随内容变化，**两个实测值都可用；765 KB 这个数找不到出处**，引用体积请引用实测值 |
| 8 | **`taskDisplay` 解析器是脆的** | 正则 `Task\{[0-9a-f]+ #(\d+) [^}]*A=\d+:(\S+?)[\s}]`（`ShellChannel.java:79-80`）会匹配到 `dumpsys activity activities` 末尾的 `mLastFocusedRootTask=` / `(fullscreen) Task{...}` 摘要行，那些行落在某个 `Display #N` 标题之后会被误归到该 display。现在因为取**第一个**匹配所以不受影响，但解析器本身不健壮（要收紧就只认带缩进的列表行 `  * Task{`） |
| 9 | **`GuideActivity` 里一句错文案** | `:140-141` 的 `setStatus("已授权，但代理未起来", …)` 与 `strings.xml:24-25` 的「ADB 通道」口径不一致（`provision` 成功时通道已交给 `ShellChannel`，不存在「代理」，也几乎不会走到这一分支） |
| 10 | **`tools/dashcast.ps1` 顶部注释的拓扑过期** | 见 §11 |
| 11 | **`panel_apps.xml` 的顺序注释与 `activity_cast.xml` 不一致** | `panel_apps.xml:5-6` 写「预览 → 触控板 → 本面板 → 操作条」，`activity_cast.xml:13-18` 写「预览 → 预览降级 → 触控板 → 应用面板」；**真实的嵌套顺序以 `activity_cast.xml` 为准** |
| 12 | **若干注释与日志仍用「代理」字样** | 例：`AppRepo.java:6`（说清单「经 Binder 下发」，现在是 shell 通道）、`InjectClient.java:415`（日志 `代理下发可投屏应用 …（shell 通道）`）、`CastActivity.java:199,417-419`、字段名 `agentBringUpStarted/agentBringUpRunning`、`strings.xml` 的 `agent_offline/agent_starting`（文案已是「ADB 通道」）。读代码按语义读，不要按名字读 |
| 13 | **看门只在界面可见期间有效** | `onPause` 立即松开，这是设计而不是缺陷（否则用户拿不回控制权）。代价是界面不可见期间不再看门 |
| 14 | **直通画面在内容静止时会「冻」在最后一帧** | 不是故障，是 SurfaceFlinger 不对未变化的图层栈重复合成。所以「画面不动」不足以判断对端死活，得靠 `previewBound` + 心跳探活 |
| 15 | **降级路径抓不到帧时预览区什么都不画** | `ImageView` 没有内容就是全透明：若此时任务还是 `translucent=true`，会透出底层车机画面，看起来像「合成了错误内容」。判定预览是否真的等于仪表屏**必须比像素**，不要用「看起来像」 |
| 16 | **`InjectClient` 每次新建、`PrivilegedClient` 是进程内单例** | 通道状态（Binder 句柄、目标屏、`ioExecutor`）本来就是每进程一份，所以做成单例；而看门状态挂在 `InjectClient` 上、随界面重建归零。往这两处加状态前先确认生命周期 |

### 12.3 未确认 / 待做

- **`com.byd.accountProvider` 不是公开 API**：车机 OTA 改动 authority 或加上权限就会失效；代码里任何读取失败都降级成「识别不到」，不崩（`CarAccount.java:28-32`）。
- **身份键会漂移**：`account.identity()` 依次退到 `userId → photoUrl → nickName`，后两者会随用户改头像/改昵称而变，那时「首开自动」的绑定自然失效，需要用户重新打开一次开关。实测本机从应用 uid 读 `account_big_data` 的 `userId` 是**空串**（shell 能读到 33 字符的值），同一个 Provider 按调用方 uid 做了区别对待（`CarAccount.java:77-97`）。
- **`DashboardSession` 的两个兜底常量（3 与 2）来自本车实测**：换车型/固件要重新确认显示拓扑，不要假定 id 不变。
- **判页阈值余量不宽**：`BAND_TARGET_MAX = 50` 的实测余量是 21（目标页）对 402（车机地图），约 20 倍。若目标页顶部出现亮内容（例如换版本后加了顶栏），会被判成 `OTHER` 而**多等到 20 秒超时**（报失败，不会误报成功）。届时重新标定即可。
- **`bypassHiddenApi()` 的实现已经逐行核读过了**（不再列在「未确认」里）：`VMRuntime.getRuntime()` + `setHiddenApiExemptions(new String[]{"L"})`，即全量豁免（§7.1）。
- **没有做的事**：给触摸流做插值/重采样（拖动帧率的问题已定位到源应用，见下），以及换一个轻量应用投到仪表屏做拖动对照。

### 12.4 性能数据与它的口径

| 指标 | 旧的抓帧路径 | GPU 直通（当前） | 口径 |
| --- | --- | --- | --- |
| App CPU | **29%**（`dumpsys cpuinfo`：18% user + 10% kernel） | **1.5%**（`/proc/<pid>/stat` 计 9 ticks / 6 s，8 核） | 两个都是**单核百分比**，但**不是同一时刻的对照**：29% 取自一个冻结窗口（恰好覆盖旧路径运行的最后一分钟），1.5% 是事后单独量的。方向明确（抓帧换成 GPU 直通后 CPU 大幅下降），**不要当成受控 A/B 的精确倍数** |
| 特权进程 CPU | — | **0 ticks（≈0%）** | 同一次测量 |
| 抓帧线程 / App 线程总数 | 4 条全速 + 每帧 PNG 解码 | 无 / 21 | — |
| 触控每条事件 | `input` 命令 fork 一个 ART：**40~140 ms** | 一次 Binder oneway 调用 | 前者是 `mksh` 内建 `time` 的 5 次样本 |

**帧率的口径**：直通路径**没有人为上限**，画面变化时逐帧到达；内容不变时 SurfaceFlinger 本来就不产生新帧（静态导航地图实测 6 fps 属于正确行为，不是被限速）。用合成输入测帧率会失真 —— `input` 每次调用都要 fork 一个 ART，100 次就能把 CPU 打满，测出来的是工具的瓶颈；**真正的验收只能用真手指**。

**拖动时掉帧是源应用慢，不是预览通路**【实测】：

| 假设 | 怎么测的 | 结果 |
| --- | --- | --- |
| 我们的 UI 线程被触摸处理压住 | 真手指拖动期间读 `dumpsys gfxinfo com.byd.dashcast` | 1752 帧里只有 4 帧 janky、UI 线程慢 3 次、绘制命令慢 0 次 —— 我们的 App 不饱和 |
| 注入路径太重 | 拖动期间读三方 `/proc/<pid>/stat` | 52 个事件只花特权进程 2 ticks；App 11 ticks。注入几乎免费 |
| 新预览 display 抢 GPU | **A/B**：关掉预览后直接注入同样的拖动序列，再开着预览做一遍，各自逐事件抓 display 2 数不同画面 | 关预览 **7** 个不同画面 / 30 次采样，开预览 **10** 个 —— **开着预览没有变慢**（略多来自地图起点不同的噪声）。**复用 layerStack 不额外抢 GPU** |
| 源应用 CPU 忙不过来 | 同上 | 拖动期间导航应用只烧 38 ticks —— 它不忙 |
| 那到底卡在哪 | 拖动期间逐事件抓 display 2 数不同画面，按 0.317 s/次采样校准 | 显示实际只产出 **7.3 fps**；惯性动画窗口能到 21 fps（当时整车 load 已到 34，所以这个数只是**下限**） |

**结论：瓶颈是源应用「被拖动时」的地图更新路径**（约 200 ms 一次更新，且不是 CPU 密集 —— 大概率阻塞在它自己的渲染/瓦片管线里），不是我们的预览通路。两条旁证：仪表盘那块屏**本身**在拖动时同样卡（用户目视确认）；集群屏 `touch NONE`，那个应用从来没有被真手指连续驱动过。
