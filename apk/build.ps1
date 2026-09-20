#requires -Version 5.1
# Build the APK: aapt2 -> javac -> d8 -> repack -> zipalign -> apksigner -> adb install.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM.
param(
    [switch]$NoInstall,
    # 留空 = 自动取当前已连接的设备。不要写死 IP：车机是 DHCP，网段换过一次
    # （10.144.81.x -> 10.174.199.x），写死的那份必然过期。
    [string]$Serial = '',
    [string]$BundledKey = '',
    # 签名覆盖项。默认从环境变量 CE_KS_* 取；显式传参时优先级最高。
    # 口令只走参数/环境变量，不落在本文件里。
    [string]$Keystore = '',
    [string]$StorePass = '',
    [string]$KeyAlias = '',
    [string]$KeyPass = ''
)

# SDK 位置：先环境变量，再退回本机默认安装路径。
# 不写死绝对路径 —— 那是某一台开发机专属的，换台机器就直接构建不了。
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Join-Path $sdk 'build-tools\35.0.0'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$androidJar = Join-Path $sdk 'platforms\android-32\android.jar'
if (-not $env:JAVA_HOME) {
    Write-Output 'JAVA_HOME 未设置，无法定位 javac / jar / keytool。'
    exit 1
}
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
foreach ($d in @('classes', 'dex', 'gen', 'res', 'apk')) {
    New-Item -ItemType Directory -Path (Join-Path $out $d) -Force | Out-Null
}
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'

Write-Output '--- preflight ---'
Write-Output ("  SDK   : {0}" -f $sdk)
# 缺东西就当场停。继续跑下去只会在几步之后抛一个与真实原因无关的错，
# 让人以为是脚本逻辑坏了，而不是"这台机器没装齐"。
$missing = @()
foreach ($tool in @('aapt2.exe', 'zipalign.exe', 'apksigner.bat', 'd8.bat')) {
    $p = Join-Path $bt $tool
    if (-not (Test-Path $p)) { $missing += $p }
    Write-Output ("  {0} : {1}" -f $tool, (Test-Path $p))
}
if (-not (Test-Path $androidJar)) { $missing += $androidJar }
if ($missing.Count -gt 0) {
    Write-Output '缺少构建依赖，已停止：'
    foreach ($m in $missing) { Write-Output ("  - {0}" -f $m) }
    Write-Output '提示：把 ANDROID_HOME 指向 Android SDK 根目录，并确认已装'
    Write-Output '      build-tools;35.0.0 与 platforms;android-32。'
    exit 1
}

Write-Output '--- assets ---'
# 不再打包独立的代理 jar：uid-2000 特权进程用 `CLASSPATH=<本 APK>` 拉起，
# 它的代码就在本包 dex 里的 com.byd.dashcast.privileged.*，与原版 Just Dashboard
# 的做法一致。好处是没有第二份产物，也就没有版本漂移。
$assets = Join-Path $root 'assets'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
$staleJar = Join-Path $assets 'dashcast-agent.jar'
if (Test-Path $staleJar) {
    # 留着它会让"这个包到底还带不带代理"变成一件要靠翻源码才知道的事。
    Write-Output '  removing stale dashcast-agent.jar (no longer shipped)'
    Remove-Item $staleJar -Force
}
Write-Output '  agent jar: not shipped (privileged code lives in this APK dex)'

# Optional fixed ADB identity. With no bundled key the app generates its own
# keypair on first run and keeps it in private storage.
$idAsset = Join-Path $assets 'adb_identity.pk8'
if ($BundledKey -ne '' -and (Test-Path $BundledKey)) {
    Copy-Item $BundledKey $idAsset -Force
    Write-Output ("  bundled identity = " + (Get-Item $idAsset).Length + " bytes from " + $BundledKey)
} else {
    if (Test-Path $idAsset) {
        # Deleting a bundled identity silently changes what ships: without the asset the
        # app generates a fresh key, adbd rejects it and the user gets an authorization
        # dialog. Say so loudly instead of shipping a different app than the tree implies.
        $warn = '  WARNING: removing ' + $idAsset + ' -- this build will ask the car for ADB authorization on first run.'
        Write-Output $warn
        Write-Output '  Pass -BundledKey <pk8> to keep the trusted identity.'
        Remove-Item $idAsset -Force
    }
    Write-Output '  bundled identity: none (app generates its own)'
}

Write-Output '--- aapt2 compile ---'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o (Join-Path $out 'res\res.zip') 2>&1 | Out-String | Write-Output
Write-Output "aapt2 compile exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Output '--- aapt2 link ---'
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $out 'apk\base.apk') -I $androidJar --manifest (Join-Path $root 'AndroidManifest.xml') -R (Join-Path $out 'res\res.zip') -A (Join-Path $root 'assets') --java (Join-Path $out 'gen') --min-sdk-version 26 --target-sdk-version 32 --auto-add-overlay 2>&1 | Out-String | Write-Output
Write-Output "aapt2 link exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Output '--- javac ---'
$sources = @()
$sources += (Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src') | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem -Recurse -Filter *.java (Join-Path $out 'gen') | ForEach-Object { $_.FullName })
Write-Output ("sources: " + $sources.Count)
& $javac -source 8 -target 8 -encoding UTF-8 -bootclasspath $androidJar -nowarn -d (Join-Path $out 'classes') $sources
Write-Output "javac exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

& $jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'classes') . 2>&1 | Out-Null

Write-Output '--- d8 ---'
& (Join-Path $bt 'd8.bat') --min-api 26 --lib $androidJar --output (Join-Path $out 'dex') (Join-Path $out 'classes.jar') 2>&1 | Out-String | Write-Output
Write-Output "d8 exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

# aapt2 link cannot take a dex, and it must not be re-zipped either: resources.arsc
# has to stay STORED (method 0) for API 30+ installs. .NET's CompressionLevel.
# NoCompression still emits deflate (method 8), so use `aapt add`, which appends the
# new entry and leaves every existing entry byte-for-byte alone.
Write-Output '--- add classes.dex (aapt add appends; existing entries untouched) ---'
$unsigned = Join-Path $out 'apk\unsigned.apk'
Copy-Item (Join-Path $out 'apk\base.apk') $unsigned -Force
# aapt names the new entry after the path it is given, so run it from the dex dir.
Push-Location (Join-Path $out 'dex')
& (Join-Path $bt 'aapt.exe') add $unsigned 'classes.dex' 2>&1 | Out-String | Write-Output
Pop-Location
Write-Output "aapt add exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($unsigned)
$arsc = $zip.Entries | Where-Object { $_.FullName -eq 'resources.arsc' }
$dex = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
Write-Output ("  resources.arsc stored={0}  classes.dex present={1}" -f `
    ($arsc.CompressedLength -eq $arsc.Length), ($null -ne $dex))
$zip.Dispose()
if ($null -eq $dex) { Write-Output 'classes.dex missing from apk'; exit 1 }
if ($arsc.CompressedLength -ne $arsc.Length) { Write-Output 'resources.arsc got recompressed'; exit 1 }

Write-Output '--- zipalign ---'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $unsigned (Join-Path $out 'apk\aligned.apk') 2>&1 | Out-String | Write-Output
Write-Output "zipalign exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Output '--- signing identity ---'
# 签名库与口令一律从环境变量取，**绝不写进本文件**（写了就会进版本库）。
# 车机上已安装的 com.byd.dashcast 是用 CN=BH4GMI 那把签的；换密钥会导致
# INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能卸载重装并丢数据。
#
#   CE_KS_FILE / CE_KS_PASS / CE_KEY_ALIAS / CE_KEY_PASS
#
# 读不到就回落到本目录的 debug.keystore（自签名）——那样只能装到没有旧版本的设备上。
# 优先读**进程环境变量**（子进程必然继承，实测最可靠），再回退到 User 注册表。
# 实测 CE_KS_FILE 在 User 作用域下可能读到空值，而进程环境里是好的。
function Get-Setting([string]$name) {
    $v = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ($null -eq $v -or $v.Length -eq 0) {
        $v = [Environment]::GetEnvironmentVariable($name, 'User')
    }
    if ($null -eq $v) { return '' }
    return $v
}
$envKs     = Get-Setting 'CE_KS_FILE'
$envKsPass = Get-Setting 'CE_KS_PASS'
$envAlias  = Get-Setting 'CE_KEY_ALIAS'
$envKeyPass= Get-Setting 'CE_KEY_PASS'

# 注意两条 PowerShell 5.1 的坑（脚本用 powershell -File 跑，是 5.1 不是 7）：
#   1. $null -ne '' 为 True —— 判空一律用 .Length，别用 -ne ''
#   2. 不支持 $x = if (...) {...} else {...} 这种赋值表达式，必须写成分支赋值
$useCmd = $false
if ($null -ne $Keystore -and $Keystore.Length -gt 0) { $useCmd = $true }
$useEnv = $false
if ($null -ne $envKs -and $envKs.Length -gt 0) {
    if (Test-Path $envKs) { $useEnv = $true }
}

if ($useCmd) {
    $ks = $Keystore
    $ksPass = $StorePass
    $alias = $KeyAlias
    $keyPass = $KeyPass
    if ($keyPass.Length -eq 0) { $keyPass = $StorePass }
    $ksSource = 'command line'
} elseif ($useEnv) {
    $ks = $envKs
    $ksPass = $envKsPass
    $alias = $envAlias
    $keyPass = $envKeyPass
    if ($keyPass.Length -eq 0) { $keyPass = $envKsPass }
    $ksSource = 'environment (CE_KS_*)'
} else {
    $ks = Join-Path $root 'debug.keystore'
    if (-not (Test-Path $ks)) {
        & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias dashcast -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=dashcast,O=byd,C=CN" 2>&1 | Out-String | Write-Output
    }
    $ksPass = 'android'
    $keyPass = 'android'
    $alias = 'dashcast'
    $ksSource = 'local debug.keystore (self-signed)'
}
if ($null -eq $alias -or $alias.Length -eq 0) { $alias = 'dashcast' }
Write-Output ("  source : " + $ksSource)
Write-Output ("  keystore: " + $ks)
Write-Output ("  alias  : " + $alias)

Write-Output '--- apksigner ---'
$apkOut = Join-Path $root 'dashcast.apk'
if (Test-Path $apkOut) { Remove-Item -Force $apkOut }
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass "pass:$ksPass" --ks-key-alias $alias --key-pass "pass:$keyPass" --out $apkOut (Join-Path $out 'apk\aligned.apk') 2>&1 | Out-String | Write-Output
Write-Output "apksigner exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

if ($NoInstall) { Write-Output 'DONE (no install)'; exit 0 }

Write-Output '--- install ---'
if (-not $Serial) {
    $Serial = (& $adb devices 2>&1 | Select-String -Pattern '^\S+\s+device$' |
               Select-Object -First 1) -replace '\s+device.*$', ''
    $Serial = "$Serial".Trim()
}
if (-not $Serial) {
    Write-Output 'no connected device found; run "adb connect <car-ip>:5555" first'
    exit 1
}
Write-Output ("  target: " + $Serial)
& $adb -s $Serial install -r $apkOut 2>&1 | Out-String | Write-Output
Write-Output 'DONE'
