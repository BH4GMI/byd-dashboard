# BYD-dashboard · 把任意 App 投到比亚迪 DiLink 5 的仪表盘

> ## ⚠️ Caution · 未经真机验收
>
> **这是一份尚未完成真机端到端验收的发行版，上车前请自行评估风险。**
>
> - 打包这次发行版时，**测试车机不在网络上**，所以本次的源码同步、死代码清理与构建脚本改动
>   **没有做任何真机回归**。
> - APK 里的代码本身在**一台** DiLink 5.0 车机上验证过（直通预览、触控注入、投屏、应用列表），
>   并且本仓库构建出的 `apk/dashcast.apk` 与车上验证过的那份**逐字节一致**（SHA-256 相同）。
>   但"能构建出同一个文件"**不等于**"这条发行路径被验证过"。
> - 明确**未验证**的：全新安装（非覆盖安装）、从 `1.0.0` 升级、开机自启、
>   以及**除这一台车之外的任何车型与固件**。
> - 请**不要在行车过程中**首次试用；涉及仪表盘显示的功能请先确认不影响原车信息的读取。
> - 本项目按「现状」提供，不附带任何担保，详见文末[免责](#免责)。
>
> 如果你愿意反馈真机结果（成功或失败），对本项目都有价值。

在**不 root、不刷机、不需要电脑，只要 ADB 权限**的前提下，让车机上的普通应用自己拿到 `uid 2000 (shell)`，
再用这份权限把任意应用投到仪表盘副屏、注入触控，并把它钉在副屏上。

> 面向一台你自己拥有、且已开启无线 ADB 的 DiLink 5.0 车机。
> 与比亚迪汽车工业有限公司无关联，仅供互操作研究。
> 许可证全文见 [`LICENSE`](LICENSE)。
> 当前版本 **4.2-cast-hold**（`versionCode 114`）。版本历史见 [`CHANGELOG.md`](CHANGELOG.md)。

---

## 它解决什么问题

DiLink 5 的仪表盘是**车机自带的投影屏**，不需要也不应该自建：

| displayId | 名字 | 尺寸 | 关键属性 |
| --- | --- | --- | --- |
| 0 | 内置屏幕 | 1920×1080 | layerStack 0，唯一的 HWC 屏 |
| 2 | `fission_bg_XDJAScreenProjection` | 1920×720 @320 | layerStack 2，`touch NONE`，owner `com.byd.containerservice`(uid 1000) —— **这就是仪表盘** |
| 3 / 4 | `shared_fission_bg_XDJAScreenProjection_0/1` | 1920×720 | layerStack 3/4 —— **投屏槽位**，`screencap` 出来全黑 |

普通应用想把别的应用送到副屏，会被 AMS 直接拒掉：

```
SecurityException: Permission Denial: ... with launchDisplayId=2
```

**但 `uid 2000` 可以。** 于是整件事只剩一个真问题要解决：**让应用自己拿到 uid 2000。**

## 核心机制

1. **本地 ADB 自举** — 应用用首次运行自生成（或构建时内嵌）的密钥连车机本地回环 `127.0.0.1:5555`，
   走标准 ADB 线协议完成握手，拿到一条 `shell,v2,raw:` 通道。全程不需要电脑。
2. **拉起 uid 2000 特权进程** — 特权代码**就在同一个 APK 的 dex 里**，用
   `CLASSPATH=<已安装 APK>` 由 `app_process` 以 `--nice-name=com.byd.dashcast-priv` 拉起。
   不需要单独的代理 jar，也不需要往设备写任何文件。
3. **投屏** — 把目标应用送到 **display 3（投屏槽位）**：没有任务时
   `am start-activity --display 3`，已有任务时 `am display move-stack <taskId> 3`。
   槽位到仪表盘的映射由车机自己的 `com.byd.containerservice` 完成，本工程不插手。
4. **预览** — 主界面上有一块与仪表屏 1:1 的 `TextureView`。特权进程用 `SurfaceControl`
   复用仪表屏的 layerStack，把它的合成结果**直接接到这块 Surface 上**：
   零抓帧、零编解码、零 CPU 回读。
5. **触控注入** — 预览上覆盖一块同坐标系的透明层，手指事件由特权进程用
   `InputManager.injectInputEvent` 注入到仪表屏。**仪表屏 `touch NONE`，所有输入都必须注入。**
6. **守位** — 应用自己发起的 Activity 启动不带 display，AMS 会把整条 root task 挪回默认屏，
   这是应用侧行为、无法预防，只能事后搬回。搬回由前台服务 `CastGuardService` 负责：
   投屏成立后每 2 秒巡检一次，**界面退出也继续**；用户要收回，点常驻通知上的「收回投屏」，
   目标被搬回主屏后服务停止。既不会自己过期，也不会把人钉在副屏上。

软件结构见 [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md)。

## 目录

```
apk/              车机端应用：src/ res/ AndroidManifest.xml build.ps1
  assets/         构建时可选填入一把固定的 ADB 私钥（内容不入库）
tools/            dashcast.ps1 —— 纯 adb 命令版的投屏/操控驱动，不需要装应用
docs/             软件结构技术文档（面向二次开发）
CHANGELOG.md      版本历史
LICENSE           GNU GPL v3.0 全文
```

## 构建

前置：JDK（`JAVA_HOME`）、Android SDK 含 `build-tools;35.0.0` 与 `platforms;android-32`。
SDK 位置依次读 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk`；
`preflight` 会打印解析结果，缺依赖时直接失败退出。

```powershell
.\apk\build.ps1 -NoInstall
```

产物为 `apk\dashcast.apk`。链路是
`preflight → assets → aapt2 compile → aapt2 link → javac → d8 → aapt add → zipalign → apksigner`。

- 去掉 `-NoInstall` 并加 `-Serial <ip:port>` 可直接装到车机。
- `apk\build.ps1 -BundledKey <某个.pk8>` 可把一把固定的 ADB 私钥打进 APK。
  **不打也可以**：应用首次运行会自己生成密钥对存在私有目录里，
  代价是车机会弹一次 ADB 授权框，点允许即可。
- `apk\debug.keystore` 缺失时构建脚本会自己 `keytool -genkeypair`，无需自备。

**给车机上已有的安装升级，必须用同一把签名密钥**：

```powershell
.\apk\build.ps1 -Keystore <keystore 路径> -StorePass <库口令> -KeyAlias <别名>
```

不传 `-Keystore` 时脚本仍退回 `apk\debug.keystore`；`debug.keystore` 只适合全新安装，
永远升级不了用别的密钥签过的车机版本。传了 `-Keystore` 而文件不存在时脚本会**直接失败**，
不会退回临时密钥 —— 那会产出一个装不上去的 APK。

## 运行前提

- 车机已开启**无线 ADB**。DiLink 5 上这条链是
  `sys.connect.adb.wiress=1` → init `setprop service.adb.tcp.port 5555` → 重启 adbd。
- 应用连的是**车机本地回环** `127.0.0.1:5555`，全程不需要 PC。

## 首次运行

桌面入口就是**主界面**（`CastActivity`）。若尚未授权，它会转到授权引导页，
车机弹出「允许 USB 调试吗」。这里有一个必须做对的动作：

- 弹窗里**必须勾选「一律允许使用这台计算机进行调试」再点「允许」**：
  只点「允许」不会把公钥写进 `adb_keys`，下次打开还会再弹。
- 「一律允许」的授权有**静默期**（框架默认 604800000 ms = 7 天）：超过时限没有成功连接过一次，
  框架会把密钥从 `adb_keys` 里删掉并重新弹框。**当前源码对这一点没有任何补偿**
  （既不写 `adb_allowed_connection_time`，也没有别的续期动作），所以长期不动车就有可能要重新授权一次。
  这是已知边界，见 [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) 第 12.2 节。
- 授权完成后每次开机自动连接，不再需要任何操作 —— **前提是「开机自启」那条链真的走通了，
  而它目前尚未在干净条件下验证通过**（同见 12.1 节）。

## 界面

- 布局一律用 `px` 排版（车机 1920×1080 @240dpi，1dp = 1.5px），因为这块屏的物理尺寸是固定的。
- 主题是 `Theme.DeviceDefault.NoActionBar` 加半透明窗口 —— 半透明是**结构性必需**：
  「窗口是否半透明」在窗口创建时就按 manifest 主题定死，`onCreate` 里的 `setTheme()` 改不动它。
- 界面自身的根布局是不透明深色底；只有开机自启那条不 `setContentView` 的路径会整块窗口全透明，用户看不见。
- 前端规格是一份独立的 HTML 预览稿（按 1:1 渲染 1920×1080），**不在本仓库内**。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) | 软件结构技术文档：模块划分、进程与 IPC 契约、启动流程、构建链、扩展点 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本历史与升级注意 |
| [`LICENSE`](LICENSE) | GNU GPL v3.0 |

## 已知边界

- **ADB 身份文件的写入不是持久化的**。`AdbKeyStore.write()` 是直接覆写：没有 `fsync`、
  没有临时文件 + 原子替换、没有回读校验。车机掉电后它可能以"长度正确、内容全 0"的样子回来，
  于是被判为损坏、删档重建，用户会再看到一次授权框。这是已知根因，尚未修。
- **预览的帧率上限由被投屏的应用自己决定**。实测在导航地图上拖动时，那块屏本身就只有约 7.3 fps，
  预览是忠实镜像，通路本身不设上限。关闭预览做同样的拖动对照，画面数反而略少，
  可以排除"新 display 抢 GPU"。
- **直通画面在内容静止时会"冻"在最后一帧**，这不是故障：SurfaceFlinger 不会对未变化的图层栈重复合成。
  所以"画面不动"不能用来判断对端死活。
- **端到端触控延迟尚未实测**。能确定的是每条事件已从"fork 一个进程（单次 40~140 ms）"
  变成"一次 Binder oneway 调用"。
- **守位的恢复延迟已实测**：把目标任务强制搬回主屏（`am display move-stack <id> 0`）后，
  下一次巡检（≤2 s）就把它拉回投屏槽，`dashcast.log` 留痕。巡检间隔 2 s，所以最坏延迟就是 2 s。
- **开机自启（`BootReceiver`）在本车上不会执行**：车机改过的广播队列会跳过第三方应用的开机广播
  （`BroadcastQueue: skip reciever for uid … BOOT_COMPLETED … ignored !!!`，实测原文）。
  所以 ADB 通道要等应用被拉起后现开，"装完不用管"只对**车机自己会自启的应用**成立 —— 见
  [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) 的「已知边界与未确认项」一节。
- 其余边界见 [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) 的「已知边界与未确认项」一节。

## 开源与免费

本项目**开源且免费**，以 **GNU 通用公共许可证第 3 版（GPL-3.0）** 发布，全文见 [`LICENSE`](LICENSE)。

- 再分发或发布修改版时必须满足 GPL-3.0 的条件：保留版权与许可证声明、
  **提供完整的对应源码**、并以同一许可证授权（copyleft）。

## 免责

- **本软件按「现状」提供**，不附带任何明示或暗示的担保，
  包括但不限于适销性、特定用途适用性与不侵权。
- **因安装或使用本软件产生的任何直接、间接、附带、特殊或后果性损失，作者概不负责**，
  包括但不限于车辆损坏、车机功能异常或失去原厂保修、数据丢失、第三方索赔、罚款或扣分。
- 是否使用、以及如何使用，由使用者自行判断并承担全部风险；
  请自行确认你的行为符合当地法律与车机厂商的服务条款。
- 与比亚迪汽车工业有限公司无关联。
- 代码中出现的 `com.byd.*` 是车机平台自身的包名，仅用于互操作。
- 请只在你自己拥有或已获授权的车辆上使用。
