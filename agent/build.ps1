#requires -Version 5.1
# Build agent/src -> dex -> dashcast-agent.jar and push it to the car.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM.
param([switch]$NoPush)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Join-Path $sdk 'build-tools\35.0.0'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$androidJar = Join-Path $sdk 'platforms\android-32\android.jar'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path (Join-Path $out 'classes') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $out 'dex') -Force | Out-Null

$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src') | ForEach-Object { $_.FullName }

Write-Output '--- javac ---'
& $javac -source 8 -target 8 -encoding UTF-8 -bootclasspath $androidJar -nowarn -d (Join-Path $out 'classes') $sources
Write-Output "javac exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

& $jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'classes') . 2>&1 | Out-Null

Write-Output '--- d8 ---'
& (Join-Path $bt 'd8.bat') --min-api 26 --output (Join-Path $out 'dex') (Join-Path $out 'classes.jar') 2>&1 | Out-String | Write-Output
Write-Output "d8 exit=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

& $jar cf (Join-Path $out 'dashcast-agent.jar') -C (Join-Path $out 'dex') classes.dex 2>&1 | Out-Null
Get-ChildItem $out -Recurse -File | Select-Object Length, FullName | Format-Table -AutoSize | Out-String -Width 140 | Write-Output

if ($NoPush) { Write-Output 'DONE (no push)'; exit 0 }

& $adb push (Join-Path $out 'dashcast-agent.jar') /data/local/tmp/dashcast-agent.jar 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "adb push exit=$LASTEXITCODE"; exit 1 }
Write-Output 'DONE'
# Explicit exit: javac/d8 print "Picked up JAVA_TOOL_OPTIONS" to stderr, and a native
# command with redirected stderr leaves $? false -- otherwise a good build exits 1.
exit 0
