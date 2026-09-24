# 更新日志

版本号的**唯一来源**是 `apk/AndroidManifest.xml` 的 `versionName`（人读）与 `versionCode`（单调递增，决定能否覆盖安装）。
本文件、`README.md`、`docs/SOFTWARE_STRUCTURE_ZH.md` 里出现的版本号都必须与它一致；改版本只改 manifest，其它地方只做引用。

> **为什么公开发布序列从 `1.0.0` 直接跳到 `4.1-cast-reliable`？** 不是漏发，是历史上有两条并行的编号线：
>
> | 线 | 编号 | 说明 |
> | --- | --- | --- |
> | 公开发布线 | `1.0.0`（versionCode `100`） | 首个公开版本，代理 jar 架构 |
> | 主开发线 | `1.2-agent-selfheal`（versionCode `3`）→ `3.0-shell-only`（`111`）→ `4.0-passthrough`（`112`）→ `4.1-cast-reliable`（`113`） | 前面若干次迭代只在主开发仓留档，没有对外发过 |
>
> 从 `4.1-cast-reliable` 起**统一到 APK 的 manifest**：`versionCode` 单调递增（决定新包能否覆盖安装到已有版本上），`versionName` 同时用作 git tag。
> 于是 tag 与 manifest 严格一一对应——release 页面写着 `v4.1-cast-reliable`，车上执行 `dumpsys package com.byd.dashcast` 就必须报出 `versionName=4.1-cast-reliable`，不会出现两个说法。

---

## 4.2-cast-hold — versionCode 114（2026-09-24）

### 修复

- **投屏落点必须回查纠正。** 上一版的启动分支把"命令有回话"当成"已经投上去了"，而
  `am start-activity --display N` 并不保证任务真的落在 N 上：实测首开自动那一次任务被建在
  display 0，投屏槽全程为空，仪表屏上什么都没出现。现在启动与搬屏统一走
  `ensureOnDisplay`（轮询屏位 → 不在就 `move-stack` 搬回 → 回查确认），搬不动就如实报未生效；
  首开自动没有界面，失败时补一次 Toast，不再"点了没反应却说已投屏"。
- **判页固定抓主投影屏。** 原来跟着"目标窗口当前挂在哪块屏"走，目标被应用自己拽回主屏后就会去判
  主屏上那个页面，实测因此误报"已到歌词播放页"。判页的语义只有一个：**仪表屏上现在显示什么**。
- **补点每轮先归位再判页。** 目标应用自己的冷启动会把整条 root task 再次拽回主屏，
  不先归位就会点在车机自己的界面上。
- **不再被"闪屏交接"丢投屏。** 启动目标应用时直接启动真正停在副屏上的那个 Activity
  （`res/values/quick_taps.xml` 的 `quick_activity`），绕开闪屏那次"不带 display"的内部启动 ——
  它会让 AMS 把整条 root task 挪回默认屏。

### 新增

- **守位前台服务 `CastGuardService`。** 屏位不变量从界面搬到服务：投屏成立后每 2 秒巡检，
  界面退出（首开自动那条链路跑完即 `finish()`）仍然继续。用户收回的入口是常驻通知上的
  「收回投屏」：把目标搬回主屏后服务停止；目标应用的任务消失时也会自动收工。
- **`AppLog`：关键日志落盘**到 `/sdcard/Android/data/<包名>/files/dashcast.log`（上限 256 KB）。
  车机日志策略会丢弃第三方应用的 `Log.*`，不落盘就没有现场 —— 这次排障正是被这一点卡住的。

### 实测（同一台车，2026-09-24）

- 冷启动链路：`当前屏位=-1` → 直接启动 → 判页 `OTHER`（冷启动）→ `HOME` → 补点一次 →
  `LYRICS` → 完成，约 11 秒；60 秒后任务仍在 display 3、`screencap -d 2` 仍是歌词页；
  媒体会话位置连续，补点**没有重新起播**。
- 守位：强制 `am display move-stack <id> 0` 后，下一次巡检（≤2 秒）把它拉回 display 3。

### 已知

- 点**被投屏应用自己的**桌面图标时，它仍会经闪屏跳到主屏，随后被守位拉回（表现为闪一下）。
  要做到"用户点图标即收回并结束投屏"，需要一个能区分"用户点的"与"应用自己跳的"的信号，
  尚未实现。

## 4.1-cast-reliable — versionCode 113（2026-09-20）

### 修复

- **ADB shell v2 分帧解析**。`shell,v2,raw:` 的每一帧是 `[id:1][length:4 小端][payload]`，帧头 5 字节，
  而且**一帧会横跨多条 `WRTE`**。旧实现按"每条报文各自对齐"解析，于是从第二包起把正文当帧头读，从此跑飞——
  表现是**大输出静默截断**（应用清单从 70 条掉到 22 条）。现在改为流式解析器，残帧留到下一包，
  非 stdout/stderr 帧只跳过；实测 18428 字节输出与 PC 侧真值逐字节一致。
  这是一次**回归**：同一个根因在更早的版本修过，那份实现在后来的重构里被连带删除，于是原样重现。
- **应用列表里显示包名而不是应用名**。`AndroidManifest.xml` 缺少 `<queries>`，而 targetSdk 32 在 Android 11+
  默认做包可见性过滤，`getApplicationInfo()` 对未声明的第三方包直接抛异常，取不到 label 就退回包名。
  更隐蔽的是**可见性还是动态的**：只有本进程启动过某个应用它才临时可见，进程一重启这份授权就没了，
  所以症状是"昨天还好好的、今天全变成包名"。已按官方方式声明 `MAIN`/`LAUNCHER` 意图查询
  （不需要受限的 `QUERY_ALL_PACKAGES` 权限）。
- **投屏可能"点了没反应、状态栏却说已投屏"**。搬屏原语 `am display move-stack` **失败时也会正常回话**
  （甚至只回一句 `Nothing to do`），而旧实现拿"命令有没有输出"当成功判据，上层又无条件当成投屏成功，
  于是既不报错也不回退。现在搬完**回查屏位**才算成功，失败时明确写状态行并弹提示。

### 变更

- **特权进程与 App 同生共死**。`am force-stop` 与低内存回收都**不会走 `onPause`**，旧的
  `CODE_SHUTDOWN` 永远发不出去，会留下一个 uid 2000 的孤儿进程和它创建的 display。
  现在 App 把 Binder token 交给对端 `linkToDeath`，App 一死对端自己清理退出。
- **特权进程名按包名派生**（`com.byd.dashcast-priv`）。原来是所有包共用的常量 `dashcast-priv`，
  而清理用 `pkill -f` 匹配整条命令行——同一台车机上若装了本工程的另一个包（如网易云共存分支），
  两边会把对方的进程杀掉。
- **首次运行流程简化**：不再有独立的「使用前须知」同意页，桌面入口直接是主界面。

### 清理

- **构建脚本不再写死 SDK 绝对路径**。`build.ps1` 原先直接把
  `C:\Users\<某台开发机>\AppData\Local\Android\Sdk` 写在文件里，换一台机器就构建不了；
  现在依次读 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk`。
  `preflight` 同时改为**缺依赖即失败退出**，不再打印一行 `False` 之后带着错路径继续跑。
  这两处只改构建，产物逐字节不变。
- 删除 `AdbClient` 里已无调用方的协议代码：写远端文件（`writeRemoteFile` / `awaitRemoteSize`）、
  双工流（`openDuplex` / `Duplex`）及相关辅助，共约 284 行。它们原本只用于把代理 jar 推到设备，
  改用 `CLASSPATH=<已安装 APK>` 后整条链路不再需要。协议层结论（`CLSE` 的双向语义、
  v2 下 stdin 灌法尚未解决的问题）已转入开发仓的 `docs/ADB_SELF_PROVISION_ZH.md`。
- 删除零调用的 `ShellChannel.swipe()`、`InjectClient.isWatching()`，以及整条无人调用的
  特权进程状态查询事务（`CODE_STATUS`）。
- 发布仓移除 `agent/` 目录（被取代的 H.264 代理方案，已不可运行）。

### 升级注意

- **从 4.0 升到 4.1 时，如果车上还跑着旧的特权进程，它不会被自动清理**：旧进程名是 `dashcast-priv`，
  新版的清理按新名字 `com.byd.dashcast-priv` 匹配，两者不匹配。重启车机即可，或手工执行一次
  `adb shell pkill -f dashcast-priv`。

---

## 4.0-passthrough — versionCode 112（2026-09-20）

预览从"抓帧"改成"GPU 直通"，这是本项目性能上的一次质变。

- **预览改为 SurfaceFlinger 图层栈直通**。uid 2000 的特权进程用 `SurfaceControl`
  创建设备显示并复用仪表屏的 layerStack，通过 `setDisplaySurface` 把仪表屏的合成结果直接接到
  App 的 `TextureView` 上——**零抓帧、零编解码、零 CPU 回读**。App CPU 从抓帧路径实测的 29%
  降到约 1.5%，特权进程约 0。
- **触控改走 Binder 注入**。由特权进程内 `InputManager.injectInputEvent` 完成，每条事件一次
  oneway 调用；旧的 shell `input` 命令每次都要 fork 一个 ART 进程，单次实测 40~140 ms。
- **特权代码内嵌进 APK 的 dex**，由 `CLASSPATH=<已安装 APK>` 拉起 `app_process`。
  不再需要单独的代理 jar，也不再需要往设备写任何文件。
- **保留 screencap 抓帧作为降级路径**，并在状态行写明降级原因——降级是显式的，不静默。

## 3.0-shell-only — versionCode 111（2026-09-20）

- 去掉代理 jar，改为只经 ADB shell 通道执行 `am` / `dumpsys` / `cmd`。
- 投屏原语：`am start-activity --display <槽位>`，任务已存在时 `am display move-stack <taskId> <槽位>`。
- 预览走 `screencap` 抓帧，受限于抓帧本身的开销。

## 1.0.0 — versionCode 100（2026-09-14）

首个公开版本。

- **本地 ADB 自举**：应用用内嵌（或首次运行自生成）的密钥连车机本地回环 `127.0.0.1:5555`，
  走标准 ADB 线协议握手，拿到一条 `shell,v2,raw:` 通道，全程不需要电脑。
- **拉起 uid 2000 代理**：把 `dashcast-agent.jar` 写到 `/data/local/tmp/`，再用
  `setsid app_process --nice-name=dashcast-agent` 启动。
- **投屏 + 触控注入 + 看门**三件事齐备：把任意应用送到仪表盘、把触控注进去、
  并在目标被系统挪回主屏时搬回去。
- 首次运行有「使用前须知」同意页，且它被放在任何调试通道动作之前。
