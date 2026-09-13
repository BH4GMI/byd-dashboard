#requires -Version 5.1
<#
  dashcast.ps1 - drive the BYD DiLink5 dashboard (instrument cluster) display over plain ADB.

  No software is installed on the head unit. Everything uses only shell commands that already
  exist there:

    * am start --display <ID>        put any app on the cluster display
    * input -d <ID> tap/swipe/...    inject touches into it
    * screencap -d <ID>              observe it
    * am force-stop                  clean up

  Verified on DiLink5.0_For_BYD_AUTO / Android 12 (SDK 32):
    display 0  1920x1080 @240  INTERNAL      the main screen
    display 2  1920x720  @320  VIRTUAL        fission_bg_XDJAScreenProjection  <- the cluster
    display 5  1920x720  @320  VIRTUAL        remote_dashboard (only while the original app runs)

  Display 2 is owned by com.byd.containerservice and is NOT FLAG_PRIVATE, so uid 2000 (shell)
  may launch onto it. It has touch NONE, so all input must be injected with `input -d 2`.

  Usage:
    .\dashcast.ps1 list
    .\dashcast.ps1 up   <package> [activity]
    .\dashcast.ps1 touch <x> <y>
    .\dashcast.ps1 swipe <x1> <y1> <x2> <y2> [ms]
    .\dashcast.ps1 key  <keycode>
    .\dashcast.ps1 text <string>
    .\dashcast.ps1 shot <name>
    .\dashcast.ps1 down <package>
    .\dashcast.ps1 restore
#>
param(
  [Parameter(Mandatory = $true, Position = 0)][string]$Action,
  [Parameter(Position = 1)][string]$A1,
  [Parameter(Position = 2)][string]$A2,
  [Parameter(Position = 3)][string]$A3,
  [Parameter(Position = 4)][string]$A4,
  [Parameter(Position = 5)][string]$A5,
  [int]$Display = 2,
  [string]$Adb = 'adb',
  [string]$OutDir = (Join-Path (Split-Path $PSScriptRoot -Parent) 'work\dashcast')
)

if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

function Dev([string[]]$argv) { & $Adb shell @argv 2>&1 }

function Get-Components {
  # package -> launcher component, resolved on the device, so the caller may pass just a package
  param([string]$Package)
  $r = (Dev @('cmd', 'package', 'resolve-activity', '--brief',
               '-a', 'android.intent.action.MAIN',
               '-c', 'android.intent.category.LAUNCHER', $Package)) -join ' '
  $m = [regex]::Match($r, '([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s*$')
  if (-not $m.Success) { throw "no launcher activity for $Package" }
  return $m.Groups[1].Value
}

switch ($Action.ToLower()) {

  'list' {
    Write-Output '--- displays ---'
    & $Adb shell dumpsys display 2>$null |
      Select-String -Pattern 'DisplayDeviceInfo\{' |
      ForEach-Object { '  ' + $_.Line.Trim() }
  }

  'up' {
    if (-not $A1) { throw 'usage: up <package> [activity]' }
    $cmp = if ($A2) { "$A1/$A2" } else { Get-Components $A1 }
    Write-Output "launching $cmp on display $Display"
    Dev @('am', 'start', '--display', "$Display", '-n', $cmp) | ForEach-Object { "  $_" }
  }

  'down' {
    if (-not $A1) { throw 'usage: down <package>' }
    Dev @('am', 'force-stop', $A1) | ForEach-Object { "  $_" }
  }

  'touch' {
    if (-not $A1 -or -not $A2) { throw 'usage: touch <x> <y>' }
    Dev @('input', '-d', "$Display", 'tap', $A1, $A2) | ForEach-Object { "  $_" }
  }

  'swipe' {
    $ms = if ($A5) { $A5 } else { '400' }
    Dev @('input', '-d', "$Display", 'swipe', $A1, $A2, $A3, $A4, $ms) | ForEach-Object { "  $_" }
  }

  'key' {
    Dev @('input', '-d', "$Display", 'keyevent', $A1) | ForEach-Object { "  $_" }
  }

  'text' {
    Dev @('input', '-d', "$Display", 'text', $A1) | ForEach-Object { "  $_" }
  }

  'shot' {
    $name = if ($A1) { $A1 } else { 'shot-' + (Get-Date -Format 'HHmmss') }
    $dev = "/sdcard/_dc_$name.png"
    Dev @('screencap', '-d', "$Display", '-p', $dev) | Out-Null
    $local = Join-Path $OutDir "$name.png"
    & $Adb pull $dev $local 2>&1 | Out-Null
    Dev @('rm', '-f', $dev) | Out-Null
    if (Test-Path $local) {
      $h = (Get-FileHash $local -Algorithm SHA256).Hash.Substring(0, 12)
      Write-Output ("{0}  {1} bytes  sha={2}" -f $local, (Get-Item $local).Length, $h)
    } else {
      Write-Output 'capture failed'
    }
  }

  'restore' {
    Write-Output 'stopping demo apps and handing the cluster back to BYD'
    Dev @('am', 'force-stop', 'mark.via') | Out-Null
    Dev @('am', 'force-stop', 'com.carrot.icecream') | Out-Null
    Write-Output 'done. BYD own nav meter task redraws display 2 by itself.'
  }

  default {
    Write-Output 'unknown action. see the comment block at the top of this file.'
    exit 1
  }
}
