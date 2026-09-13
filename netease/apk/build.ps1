#requires -Version 5.1
# Build the APK: aapt2 -> javac -> d8 -> repack -> zipalign -> apksigner -> adb install.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM.
param(
    [switch]$NoInstall,
    # Empty = no -s, let adb pick the only attached device. This file is ASCII-only
    # on purpose (see line 3): non-ASCII comments here get ANSI-decoded and can eat
    # the very next declaration.
    [string]$Serial = '',
    [string]$BundledKey = '',
    # Release signing. The built-in debug.keystore can only ever produce a fresh
    # install. Upgrading an install that was signed with another key fails outright
    # (`adb install -r`), and the agent refuses the app as well: its trust model is
    # signature equality against the anchor package, so a new signer is a new trust
    # domain and an agent already running under the old signature will not serve it.
    # Pass the same key that signed the install already on the car.
    [string]$Keystore = '',
    [string]$StorePass = 'android',
    [string]$KeyPass = '',
    [string]$KeyAlias = 'dashcast'
)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Join-Path $sdk 'build-tools\35.0.0'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$androidJar = Join-Path $sdk 'platforms\android-32\android.jar'
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
foreach ($tool in @('aapt2.exe', 'zipalign.exe', 'apksigner.bat', 'd8.bat')) {
    $p = Join-Path $bt $tool
    Write-Output ("  {0} : {1}" -f $tool, (Test-Path $p))
}

Write-Output '--- assets: bundle the uid-2000 agent jar ---'
# The agent now ships inside the APK so the device never needs a PC again:
# AgentLauncher uploads it to /data/local/tmp over the app's own adb shell channel.
$agentJar = Join-Path (Join-Path $root '..\..') 'agent\out\dashcast-agent.jar'
if (-not (Test-Path $agentJar)) {
    Write-Output "agent jar missing: $agentJar (run agent\build.ps1 first)"
    exit 1
}
$assets = Join-Path $root 'assets'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
Copy-Item $agentJar (Join-Path $assets 'dashcast-agent.jar') -Force
Write-Output ("  dashcast-agent.jar = " + (Get-Item (Join-Path $assets 'dashcast-agent.jar')).Length + " bytes")

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
# has to stay STORED (method 0) for API 30+ installs, and .NET's CompressionLevel.
# NoCompression still emits deflate (method 8). `aapt add` appends the new entry and
# leaves every existing entry byte-for-byte alone, so use that instead.
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

Write-Output '--- keystore ---'
$ks = if ($Keystore -ne '') { $Keystore } else { Join-Path $root 'debug.keystore' }
if (-not (Test-Path $ks)) {
    # Only auto-generate the throwaway debug key. A missing release key is a hard stop:
    # silently falling back to a fresh key would produce an uninstallable APK.
    if ($Keystore -ne '') { Write-Output "keystore not found: $ks"; exit 1 }
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias dashcast -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=dashcast,O=byd,C=CN" 2>&1 | Out-String | Write-Output
}
if ($KeyPass -eq '') { $KeyPass = $StorePass }
Write-Output ("keystore: " + $ks + "  alias: " + $KeyAlias)

Write-Output '--- apksigner ---'
$apkOut = Join-Path $root 'dashcast-netease.apk'
if (Test-Path $apkOut) { Remove-Item -Force $apkOut }
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass "pass:$StorePass" --ks-key-alias $KeyAlias --key-pass "pass:$KeyPass" --out $apkOut (Join-Path $out 'apk\aligned.apk') 2>&1 | Out-String | Write-Output
Write-Output "apksigner exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

if ($NoInstall) { Write-Output 'DONE (no install)'; exit 0 }

Write-Output '--- install ---'
# Arguments go through an array, never string concatenation: a path or passphrase
# containing a space would be re-split by the command line parser.
$adbArgs = @()
if ($Serial -ne '') { $adbArgs += @('-s', $Serial) }
$adbArgs += @('install', '-r', $apkOut)
& $adb @adbArgs 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "adb install exit=$LASTEXITCODE"; exit 1 }
Write-Output 'DONE'
# Explicit exit. Every JVM tool above prints "Picked up JAVA_TOOL_OPTIONS" to stderr;
# a native command with redirected stderr leaves $? false, and PowerShell would then
# hand the caller exit code 1 for a build that fully succeeded. Say it outright.
exit 0
