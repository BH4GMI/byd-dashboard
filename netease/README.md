# netease/ — 网易云投屏（共存版）

本分支（`netease`）在母工程之上多出一个**共存安装**的应用：`com.byd.dashcast.netease`，
显示名 **网易云投屏**。

它就是一个**快捷方式**：**点一下图标，网易云就被投到仪表屏并展开到歌词播放页**，全程没有界面。
母工程本身仍在这棵树上（`apk/`、`agent/`），根 `README.md` 讲的还是它。

## 用法

1. **首次打开**：桌面图标 → **使用前须知**（同意一次）→ 尚未授权时是**授权引导** →
   车机弹「允许 USB 调试吗」→ 勾选「一律允许使用这台计算机进行调试」再点「允许」。
2. **之后**：点一下图标就走，不需要任何交互。链路是
   找到网易云 → 送到仪表屏 `display 2` → 抓帧判页 → 必要时补点 → 停在歌词播放页 →
   撤看门 → 自己结束。

投屏完成后网易云**留在仪表屏**。要在主屏用它，**点它的图标**就能拉回主屏——那时看门已经撤了，
没有东西会把它拽回仪表屏。

## 与主应用的关系

| | 仪表盘投屏 `com.byd.dashcast` | 网易云投屏 `com.byd.dashcast.netease` |
| --- | --- | --- |
| 界面 | 完整管理界面 | **没有界面**（透明窗口，跑完即退） |
| 投什么 | 列表里选，或「一键启动」 | 固定网易云，并展开到歌词播放页 |
| 看门 | 界面在前台时留着，`onPause` 撤 | 留到链路结束，`onDestroy` 才撤 |
| 授权引导 | `GuideActivity` | 同一个（分支自己的包名） |
| 代理 | 与主应用**共用**同一个 uid-2000 代理（同一个 jar） | 同左 |

共存的前提是**同签名**：代理的信任模型是「与 `com.byd.dashcast` 同签名的应用都服务」
（`agent/src/com/byd/dashcast/agent/Agent.java` 的 `ANCHOR_PACKAGE`）。换签名 = 换信任域。

## 装完共存包必须重启一次代理

代理的信任集合是**启动时解析一次**的（`Agent.java:171` `resolveTrustedPackages`），
而它用 `intent.setPackage(client)` **逐个点名**广播 Binder。所以共存包如果是在代理启动
**之后**才装上的，它永远不在名单里 → 收不到 Binder 广播 → 打开就一直等到超时。
**重启车机**（或杀掉 `dashcast-agent` 让应用自愈重拉）即可。

## 构建

```powershell
# 1) 先从母工程生成（读 apk/fork/ 的片段重建 netease/apk）
.\scripts\sync_netease_fork.ps1

# 2) 再构建并安装到车机
.\netease\apk\build.ps1 -Keystore <keystore 路径> -StorePass <库口令> -KeyAlias <别名>
```

产物 `netease/apk/dashcast-netease.apk`；包名 `com.byd.dashcast.netease`；
版本 `1.0.0-netease`（`versionCode 100`）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `apk/` | **生成物**：一个能直接构建的完整应用。不要手改，改片段后重新生成 |
| `apk/fork/` | 分支相对母工程的可审阅差异（片段），见 [`apk/fork/README.md`](apk/fork/README.md) |
| `../scripts/sync_netease_fork.ps1` | 生成器：读 `apk/fork/` 重建 `apk/` |

## 已知限制

- **链路还没跑完时别去点网易云图标**：那段时间看门在，点它会被 1 秒内搬回仪表屏。
  链路跑完（几秒）看门自动撤，之后点图标就能拉回主屏。
- **授权丢失时会重新走一次授权引导页**：`ensureAgent` 在未授权时会把 `GuideActivity`
  拉到前台，不是静默失败。需要手工授权时也可以直接打开：
  `adb shell am start -n com.byd.dashcast.netease/.GuideActivity`。
- **补点落点是实测数据**：`apk/fork/only/quick_taps.xml` 里是
  `com.netease.cloudmusic.iot:318:309`，来自这台车这块屏。网易云改版后需要重新实测。
- **只在 2025 款比亚迪汉智驾版（DiLink 5.0）上验证过**，其他车型或版本未验证。

## 许可

GPL-3.0，与母工程相同，见根 [`LICENSE`](../LICENSE)。
