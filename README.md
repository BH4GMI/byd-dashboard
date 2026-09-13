# 网易云投屏 · 点一下图标，网易云就上仪表盘

本分支（`netease`）在母工程之上多出一个**共存安装**的应用：`com.byd.dashcast.netease`，
显示名 **网易云投屏**。它就是一个**快捷方式**：点一下图标，网易云音乐被投到仪表屏并展开到
歌词播放页，全程没有界面。

> 面向一台你自己拥有、且已开启无线 ADB 的 DiLink 5.0 车机。
> 与比亚迪汽车工业有限公司无关联，仅供互操作研究。
> 许可证全文见 [`LICENSE`](LICENSE)。
> 当前版本 **v1.0.0-netease**。主应用「仪表盘投屏」的 v1.0.0 在 `main` 分支。

## 下载

[**`dashcast-netease.apk`**](https://github.com/BH4GMI/byd-dashboard/releases/download/v1.0.0-netease/dashcast-netease.apk)
· 105,643 字节 · SHA-256 `6ad9f2a8ac7fb1798527e9eb6cdc36ed0fe97b7fe4a6c3663b8b8a66dd9769da`

发行页：<https://github.com/BH4GMI/byd-dashboard/releases/tag/v1.0.0-netease>（标注为 pre-release，
因为 `Latest` 保持给主应用）。

**必须与主应用 `com.byd.dashcast` 用同一把签名密钥**：代理的信任模型是「与 `com.byd.dashcast`
同签名的应用都服务」（`agent/src/com/byd/dashcast/agent/Agent.java` 的 `ANCHOR_PACKAGE`）。
换签名等于换信任域，`adb install -r` 会失败，即使装上代理也不会服务它。

## 用法

1. **首次打开**：桌面图标 → **使用前须知**（同意一次）→ 尚未授权时是**授权引导** →
   车机弹「允许 USB 调试吗」→ 勾选「一律允许使用这台计算机进行调试」再点「允许」。
2. **之后**：点一下图标就走，不需要任何交互。链路是
   找到网易云 → 送到仪表屏 `display 2` → 抓帧判页 → 必要时补点 → 停在歌词播放页 →
   撤看门 → 自己结束。

投屏完成后网易云**留在仪表屏**。要在主屏用它，**点它的图标**就能拉回主屏——那时看门已经撤了，
没有东西会把它拽回仪表屏。

**装完共存包必须重启一次代理**：信任集合是**启动时解析一次**的（`Agent.java:171`
`resolveTrustedPackages`），共存包若在代理启动**之后**才装上，它永远不在名单里 → 收不到
Binder 广播 → 打开就一直等到超时。重启车机（或杀掉 `dashcast-agent` 让应用自愈重拉）即可。

## 已知限制

- **链路还没跑完时别去点网易云图标**：那段时间看门在，点它会被 1 秒内搬回仪表屏。
  链路跑完（几秒）看门自动撤，之后点图标就能拉回主屏。
- **授权丢失时会重新走一次授权引导页**，不是静默失败。也可以直接打开：
  `adb shell am start -n com.byd.dashcast.netease/.GuideActivity`。
- **补点落点是实测数据**：`netease/apk/fork/only/quick_taps.xml` 里是
  `com.netease.cloudmusic.iot:318:309`，来自这台车这块屏。网易云改版后需要重新实测。
- **只在 2025 款比亚迪汉智驾版（DiLink 5.0）上验证过**，其他车型或版本未验证。

## 这棵树上还有什么

母工程仍然完整地在这棵树上（`apk/`、`agent/`、`docs/`），**主应用「仪表盘投屏」的说明在
`main` 分支**：<https://github.com/BH4GMI/byd-dashboard>。两者共用同一个 uid-2000 代理，
同签名，可共存安装，互不影响。

- 网易云共存版的技术说明（与主应用的关系、构建、目录）：[`netease/README.md`](netease/README.md)
- 分支相对母工程的可审阅差异（片段）：[`netease/apk/fork/README.md`](netease/apk/fork/README.md)

## 免责

- 本软件按「现状」提供，不附带任何担保；因安装或使用产生的任何损失，作者概不负责，
  包括但不限于车辆损坏、车机功能异常或失去原厂保修、数据丢失、第三方索赔、罚款或扣分。
- 是否使用、如何使用，由使用者自行判断并承担全部风险；请自行确认你的行为符合当地法律与
  车机厂商的服务条款。
- 代码中出现的 `com.byd.*` 是车机平台自身的包名，仅用于互操作。
- 请只在你自己拥有或已获授权的车辆上使用。

## 许可

GPL-3.0，与母工程相同，全文见 [`LICENSE`](LICENSE)。
