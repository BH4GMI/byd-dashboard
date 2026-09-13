# BYD-dashboard · 把任意 App 投到比亚迪 DiLink 5 的仪表盘

在**不 root、不刷机、不需要电脑，只要ADB权限**的前提下，让车机上的普通应用自己拿到 `uid 2000 (shell)`，
再用这份权限把任意应用投到仪表盘副屏、注入触控，并把它钉在副屏上。

> 面向一台你自己拥有、且已开启无线 ADB 的 DiLink 5.0 车机。
> 与比亚迪汽车工业有限公司无关联，仅供互操作研究。
> 许可证全文见 [`LICENSE`](LICENSE)。
> 当前版本 **v1.0.0**（首个公开版本）。

---

## 它解决什么问题

DiLink 5 的仪表盘是**车机自带的投影屏**，不需要也不应该自建：

| displayId | 名字 | 尺寸 | flags |
| --- | --- | --- | --- |
| 0 | 主屏 | 1920×1080 | `FLAG_SECURE` |
| 2 | `fission_bg_XDJAScreenProjection` | 1920×720 @320 | `FLAG_PRESENTATION`，**不带** `FLAG_OWN_CONTENT_ONLY` |
| 3 / 4 | `shared_fission_bg_XDJAScreenProjection_0/1` | — | 共享变体 |

普通应用想把别的应用（或自己的界面）送到副屏，会被 AMS 直接拒掉：

```
SecurityException: Permission Denial: ... with launchDisplayId=2
```

**但 `uid 2000` 可以。** 于是整件事只剩一个真问题要解决：**让应用自己拿到 uid 2000。**

## 核心机制

1. **本地 ADB 自举** — 应用用内嵌（或首次运行自生成）的密钥连车机本地回环 `127.0.0.1:5555`，
   走标准 ADB 线协议完成握手，拿到一条 `shell,v2,raw:` 通道。
2. **拉起代理** — 把 APK 内置的 `dashcast-agent.jar` 写到 `/data/local/tmp/`，
   再用 `setsid app_process --nice-name=dashcast-agent` 启动。
   必须 `setsid`：adbd 在 shell 会话结束时回收的是**整个会话**，不脱离就一定会被连带杀掉。
   也必须用 `shell,v2,raw:`：旧的 `shell:` 会分配 pty，同样会导致会话回收时杀掉后台进程。
3. **投屏** — 代理通过 ATMS 把目标应用投到仪表盘的 displayId。
4. **触控注入** — 代理用 `InputManager.injectInputEvent` 把点击送进副屏。
   注意 `TOUCH` 事务本身不带 display，代理必须先 `setDisplay()`。
5. **看门** — 目标应用被拽回主屏时把它搬回去。
   应用自己发起的 Activity 启动不带 display，AMS 会把整条 root task 挪回默认屏。

软件结构见 [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md)。

## 目录

```
apk/        车机端应用：src/ res/ AndroidManifest.xml build.ps1
  assets/   构建时填入代理 jar 与可选 ADB 私钥（内容不入库）
agent/      uid-2000 代理：src/ build.ps1 run.ps1
tools/      dashcast.ps1 —— 纯 adb 命令版的投屏/操控驱动，不需要装应用
docs/       软件结构技术文档（面向二次开发）
LICENSE     GNU GPL v3.0 全文
```

## 构建

前置：JDK（`JAVA_HOME`）、Android SDK 含 `build-tools;35.0.0` 与 `platforms;android-32`。
SDK 位置先读 `ANDROID_HOME`，没有则退回 `%LOCALAPPDATA%\Android\Sdk`。

```powershell
# 1) 先出代理 jar
.\agent\build.ps1 -NoPush

# 2) 再出 APK
.\apk\build.ps1 -NoInstall
```

产物为 `apk\dashcast.apk`。

- 去掉 `-NoInstall` 并加 `-Serial <ip:port>` 可直接装到车机。
- `apk\build.ps1 -BundledKey <某个.pk8>` 可把一把固定的 ADB 私钥打进 APK。
  **不打也可以**：应用首次运行会自己生成密钥对存在私有目录里，
  代价是车机会弹一次 ADB 授权框，点允许即可。
- `apk\debug.keystore` 缺失时构建脚本会自己 `keytool -genkeypair`，无需自备。

**给车机上已有的安装升级，必须用同一把签名密钥**：

```powershell
.\apk\build.ps1 -Keystore <keystore 路径> -StorePass <库口令> -KeyAlias <别名>
```

换签名的后果是两条，不止一条：

1. `adb install -r` 直接失败（签名不一致）；
2. **即使装上，代理也不会再服务它** —— 代理的信任模型是**签名相等**
   （`Agent.java:84-94`），换签名等于换信任域，已经在跑的那个代理会拒绝新应用。

不传 `-Keystore` 时脚本仍退回 `apk\debug.keystore`；`debug.keystore` 只适合全新安装，
永远升级不了用别的密钥签过的车机版本。传了 `-Keystore` 而文件不存在时脚本会**直接失败**，
不会退回临时密钥 —— 那会产出一个装不上去的 APK。

## 运行前提

- 车机已开启**无线 ADB**。DiLink 5 上这条链是
  `sys.connect.adb.wiress=1` → init `setprop service.adb.tcp.port 5555` → 重启 adbd。
- 应用连的是**车机本地回环** `127.0.0.1:5555`，全程不需要 PC。

## 首次运行

桌面入口是**免责声明页**（`DisclaimerActivity`），所以第一次打开的顺序是：

```
桌面图标 → 使用前须知 →（同意）→ 主界面 →（尚未授权时）授权引导 → 车机弹「允许 USB 调试吗」
```

- 免责声明在**任何调试通道动作之前**出现：它是 manifest 里的 LAUNCHER 入口，
  所以"先看须知"是一条结构性约束，不是主界面里的一次判断。
- 「同意」记在本机 SharedPreferences（键 `disclaimer_accepted_v1`），此后不再显示；
  「不同意」直接退出应用，**不提供降级运行**。声明内容有实质改动时会升版本号重新征求同意。
- 车机弹窗里**必须勾选「一律允许使用这台计算机进行调试」再点「允许」**：
  只点「允许」不会把公钥写进 `adb_keys`，下次打开还会再弹。
- 授权完成后每次开机自动连接，不再需要任何操作。

## 界面

- **固定深色**，不跟随车机日夜主题：只有 `apk/res/values/colors.xml` 一套令牌，
  没有 `values-night/`。白底在这块屏（驾驶员右手边）夜间会刺眼。
- 车机是 1920×1080 @240dpi（1dp = 1.5px），布局一律用 `px` 排版：
  顶栏 88 / 镜像区 720 / 底栏 96，合计 904 ≤ 907。
- 前端规格是一份独立的 HTML 预览稿（按 1:1 渲染 1920×1080），**不在本仓库内**。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) | 软件结构技术文档：模块划分、进程与 IPC 契约、启动流程、构建链、扩展点 |
| [`LICENSE`](LICENSE) | GNU GPL v3.0 |

## 已知边界

- **开机自启（`BootReceiver`）尚未在干净条件下验证通过** ——
  不要在部署时假定「装完就不用管」。
- 其余边界见 [`docs/SOFTWARE_STRUCTURE_ZH.md`](docs/SOFTWARE_STRUCTURE_ZH.md) 第 12 节。

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
