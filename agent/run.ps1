#requires -Version 5.1
# Start / stop / inspect the uid-2000 injection agent on the car.
# ASCII only: Windows PowerShell 5.1 reads .ps1 as ANSI unless it has a BOM.
param(
    [ValidateSet('start', 'stop', 'status', 'log')]
    [string]$Action = 'status'
)

$adb = 'adb'
$jarPath = '/data/local/tmp/dashcast-agent.jar'
$logPath = '/data/local/tmp/dashcast-agent.log'
$mainClass = 'com.byd.dashcast.agent.Agent'
# app_process --nice-name=dashcast-agent calls setArgV0(), so the process comm is
# exactly this name and `pidof` finds it. Never use `pgrep/pkill -f`: the wrapping
# `sh -c '<this whole line>'` carries the same text in its own cmdline, so -f matches
# (and kills) the shell that is about to start the process.
# `pgrep -x` / `pkill -x` are NOT usable here either - toybox on this car returns
# nothing for them, which silently left stale agents running and double-broadcasting.
$processName = 'dashcast-agent'
$killAll = 'for p in $(pidof ' + $processName + '); do kill $p; done'

function Invoke-Adb {
    param([string]$Command)
    & $adb shell $Command 2>&1 | Out-String | Write-Output
}

switch ($Action) {
    'start' {
        $cmd = $killAll + ' >/dev/null 2>&1; sleep 0.5; ' +
               ': > ' + $logPath + '; ' +
               'CLASSPATH=' + $jarPath + ' nohup app_process /system/bin --nice-name=' + $processName + ' ' +
               $mainClass + ' >' + $logPath + ' 2>&1 & echo launched'
        Invoke-Adb $cmd
        Start-Sleep -Seconds 5
        Invoke-Adb ('pidof ' + $processName)
        Invoke-Adb ('cat ' + $logPath)
    }
    'stop' {
        Invoke-Adb ($killAll + '; sleep 0.5; pidof ' + $processName)
        Write-Output 'stopped (no pid above means gone)'
    }
    'status' {
        Invoke-Adb ('pidof ' + $processName)
    }
    'log' {
        Invoke-Adb ('cat ' + $logPath)
    }
}
