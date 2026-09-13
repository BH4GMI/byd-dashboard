# apk/

车机端应用。构建入口 `build.ps1`，链路是
`aapt2 compile → aapt2 link → javac → d8 → aapt add → zipalign → apksigner`。

## assets/

`aapt2 link -A assets` 会把**这个目录下的所有文件**打进 APK，所以不要往这里放说明文档。

目录本身必须存在，构建脚本会自行创建。它只在构建时被填入一个文件：

| 文件 | 来源 | 作用 |
| --- | --- | --- |
| `dashcast-agent.jar` | `agent/build.ps1` 的产物 | 代理 jar。运行时被写到车机 `/data/local/tmp/`，由 `app_process` 拉起 |

可选地，`build.ps1 -BundledKey <pk8>` 还会放进一把固定的 ADB 私钥（`adb_identity.pk8`）。
**不带这把密钥也能用**：应用首次运行会自己生成 RSA-2048 密钥对存在私有目录里，
代价是车机会弹一次「允许 USB 调试吗」，点允许后公钥写入 `/data/misc/adb/adb_keys`，此后长期有效。

两个文件都不入库。
