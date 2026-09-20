# apk/

车机端应用。构建入口 `build.ps1`，链路是
`preflight → assets → aapt2 compile → aapt2 link → javac → d8 → aapt add → zipalign → apksigner`。

没有 Gradle：整个构建就是这一串命令行工具，`build.ps1` 负责把它们接起来。

## 源码

`src/com/byd/dashcast/` 下同时住着两个进程的代码 —— 它们**在同一个 APK 里**：

| 包 | 跑在哪 | 做什么 |
| --- | --- | --- |
| `com.byd.dashcast` | 普通应用进程 | 界面、ADB 自举、应用列表、看门、预览与触控的转发 |
| `com.byd.dashcast.privileged` | `app_process` 起的 `uid 2000` 进程 | 建屏接屏（`SurfaceControl`）、输入注入（`InputManager`） |

特权进程由 App 用 `CLASSPATH=<本 APK 路径>` 拉起，因此**不需要单独的 jar，也不需要往设备推文件**。
两侧的 IPC 契约是 `privileged/PrivilegedProtocol.java` 里的一组事务号，改协议要同时改两边。

## assets/

`aapt2 link -A assets` 会把**这个目录下的所有文件**打进 APK，所以不要往这里放说明文档。

目录本身必须存在，构建脚本会自行创建。正常情况下它是**空的**：

- 可选地，`build.ps1 -BundledKey <pk8>` 会放进一把固定的 ADB 私钥（`adb_identity.pk8`），
  让应用开箱即用、不必在车机上点授权框。**不带这把密钥也能用**：应用首次运行会自己生成
  RSA-2048 密钥对存在私有目录里，代价是车机会弹一次「允许 USB 调试吗」，
  勾选「一律允许」后公钥写入 `/data/misc/adb/adb_keys`，此后长期有效。

放入的文件不入库。

## 打包时的两个硬约束

- `resources.arsc` 在 APK 里必须是 **STORED（method 0）**，否则 API 30+ 装不上。
  .NET 的 `CompressionLevel.NoCompression` 仍会产出 deflate，所以这一步用 `aapt add` 而不是重新压 zip。
  构建脚本会在收尾时校验，被重新压缩就直接失败退出。
- `classes.dex` 由 `aapt add` **追加**进已经 `zipalign` 过的包，不能重新打包。
