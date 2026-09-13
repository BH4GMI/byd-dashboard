# fork/ — 网易云分支的差异片段

本目录的东西**不是最终代码**，是 `netease/apk`（网易云共存版）相对母工程 `main`
分支的可审阅差异。仓库根的 `scripts/sync_netease_fork.ps1` 会读它们，把分支重新生成出来。

## 为什么这样组织

分支原先靠手工拷贝文件维护，它的类注释声称「差异点收敛在 onCreate 一处，其余类与母
工程逐字相同」。这句注释曾一度不成立——母工程的 `CastActivity` 长出了 222 行界面接线，
分支没跟上——于是每次同步都得重新分析全量差异，很容易漏。

把差异从代码里抽出来放进本目录后：

- 同步变成一条命令，母工程的修复自动流到分支
- 分支到底改了什么，看这几个文件就够，不用做 diff
- 同步脚本能对"不该改的东西"做断言（见下）

## 文件

| 文件 | 作用 |
| --- | --- |
| `CastActivity.doc.txt` | 替换分支 `CastActivity` 的类注释 |
| `CastActivity.onCreate.txt` | 替换分支 `CastActivity` 的 `onCreate`：打开即投屏、不建界面 |
| `CastActivity.onPause.txt` | 替换分支 `CastActivity` 的 `onPause`：本分支没有界面，它自己的 pause 恰恰是「目标被启动」的信号，所以不能在那里撤看门、也不能停心跳 |
| `CastActivity.overrides.txt` | 分支对母工程 `CastActivity` 的其余改动，按 `//@@ AFTER/BEFORE <锚点>` 分块插入 |
| `InjectClient.tap.txt` | 给分支的 `InjectClient` 补回 `tap(...)`（见下"为什么要补 tap"） |
| `strings.branch.xml` | 分支自己的文案（母工程删掉了那几条 string），按 `name` 覆盖或追加进 `strings.xml` |
| `app_name.txt` | 应用显示名（`网易云投屏`） |
| `only/` | 母工程已经没有的分支专有文件，逐字拷贝：`AutoCast.java`、`CarAccount.java`、`DashboardEye.java`、`quick_taps.xml` |

片段与母工程源码的对应关系：前三个是**整方法/整注释替换**（`doc`、`onCreate`、`onPause`，
按 `@Override` + 花括号配对定位），`overrides.txt` 与 `InjectClient.tap.txt` 是**按锚点插入**。

`overrides.txt` 里每个锚点都是母工程源码里的**正则**（例如
`^    private void refreshStatus\(\) \{$`）。母工程一旦把锚点挪了位置，同步脚本会当场
报错退出，而不是悄悄少打一个补丁——这是刻意的：**分支的失效必须是响的**。

中文一律放这里、不放 `.ps1`：Windows PowerShell 5.1 把无 BOM 的 `.ps1` 按 ANSI 解码，
写在脚本里的中文字面量会先变成乱码再写进产出文件（多字节序列还会吞掉紧跟的 ASCII 字符）。
本目录的文件按 UTF-8 读取，没有这个问题。所以 `sync_netease_fork.ps1` 必须保持
ASCII-only、且不能有 BOM。

## 同步

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  <仓库>\scripts\sync_netease_fork.ps1
```

脚本自己从所在位置推出 `-Main=<repo>\apk`、`-Fork=<repo>\netease\apk`，不需要任何别的参数。

母工程 v1.0.0 删掉了自动投屏编排，那几条文案随之删除，而分支的脚本还要用，
所以那些 `<string>` 由 `strings.branch.xml` 提供：脚本按 `name` 覆盖或追加进分支的
`strings.xml`，并逐条断言都在。**删代码来绕过缺资源是错的**，这是刻意保留的路。

文案由分支自己拥有，不从旧开发目录读：旧目录是陈旧的，而且它的措辞属于母工程的
「首开自动」开关，与分支的语义（打开即投屏）不符。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  <repo>\netease\apk\build.ps1 `
  -Keystore C:\Users\REDMI\Documents\AndroidKey.keystore -StorePass 114514 -KeyAlias bh4gmi
```

`-KeyPass` 留空会自动取 `-StorePass`。`build.ps1` 由同步脚本从母工程的
`apk/build.ps1` 生成，只改两处：产物名 `dashcast-netease.apk`，以及 agent jar 的
路径（分支没有自己的 `agent/`，用的就是母工程 `agent/out/dashcast-agent.jar`，
同一个 uid-2000 进程服务两个包）。

## 不能碰的 4 个名字

它们是跨进程契约，改名后 uid-2000 代理就认不出调用方：

- `com.byd.dashcast.agent.Agent` — 代理 jar 里的入口类
- `com.byd.dashcast.agent.AgentBinder` — 该 jar 的 Binder 接口
- `com.byd.dashcast.action.AGENT_READY` — 代理发的广播
- `com.byd.dashcast.extra.AGENT_BINDER` — 该广播的 extra key

同步脚本每次跑完都会逐个断言它们还在，缺一个就 `exit 1`。代理的信任模型是
「与锚点包 `com.byd.dashcast` 同签名的应用都服务」，所以共存版本装上就能用，
不需要改代理——前提是两者用同一把钥匙签名。

## 为什么要补 tap

`CastActivity.tapUntilTargetPage` / `settleThenTap` 是实测闭环，方法体逐字保留
（任何"优化""简化""重构"都是错的）。`tapUntilTargetPage` 内部调用
`injector.tap(display, x, y)`，而母工程 v1.0.0 的 `InjectClient` 已经删掉了这个
客户端助手（新骨架不补点）。删掉调用点等于改行为，所以分支把 `tap` 补回来：
它只用现成的 `touch()` 发 DOWN/UP 一对事件，不涉及任何代理侧协议。

## 已知限制

授权丢失时，母工程的 `ensureAgent()` 会去打开引导页（`GuideActivity`）；分支继承
同一行为（v1.0.0 的 `ensureAgent` 里已经没有 `autoMode` 判断）。需要手工授权时也可以
显式打开引导页：

```powershell
adb shell am start -n com.byd.dashcast.netease/.GuideActivity
```
