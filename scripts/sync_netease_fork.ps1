#requires -Version 5.1
# Sync the netease fork (netease/apk) from the main project (apk), so the fork carries
# only its documented divergences instead of drifting a whole file at a time.
#
# Layout it produces (see netease/apk/fork/README.md):
#   main source --(package prefix)--> netease/apk/src/com/byd/dashcast/netease/...
#   then the fork's own fragments from netease/apk/fork/ are spliced in:
#     CastActivity.doc.txt        replaces the branch CastActivity class javadoc
#     CastActivity.onCreate.txt   replaces the branch CastActivity onCreate
#     CastActivity.overrides.txt  inserted blocks: constants, fields, the auto-mode
#                                 dispatch in the receiver, three null guards and the
#                                 restored auto-cast methods
#     InjectClient.tap.txt        restores InjectClient.tap(...), which the branch
#                                 CastActivity still calls but v1.0.0 of the main
#                                 project dropped
#     strings.branch.xml          branch-owned wording for the string resources the
#                                 main project dropped; overrides or appends by name
#     only/                       branch-only files the main project no longer has
#                                 (AutoCast.java, CarAccount.java, DashboardEye.java,
#                                 quick_taps.xml) -- copied verbatim, package renamed
#
# ASCII only: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so a non-ASCII
# literal here would be mangled into mojibake before it reached any output file (and
# would swallow the neighbouring ASCII). Every Chinese string lives in the fragment
# files under netease/apk/fork/ and is read as UTF-8.
param(
    # Defaults are derived from this script's own location, never hard-coded:
    #   -Main   <repo>\apk
    #   -Fork   <repo>\netease\apk
    [string]$Main = '',
    [string]$Fork = ''
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false

function Read-Text([string]$p) {
    return [System.IO.File]::ReadAllText($p, $utf8)
}
function Write-Text([string]$p, [string]$t) {
    $dir = Split-Path -Parent $p
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($p, $t, $utf8)
}

$repo = Split-Path -Parent $PSScriptRoot
if ($Main -eq '') { $Main = Join-Path $repo 'apk' }
if ($Fork -eq '') { $Fork = Join-Path $repo 'netease\apk' }

if (-not (Test-Path (Join-Path $Main 'AndroidManifest.xml'))) {
    throw "not a dashcast apk tree: $Main"
}
$forkRoot = Join-Path $Fork 'fork'
if (-not (Test-Path $forkRoot)) {
    throw "fork fragments missing: $forkRoot"
}
$onlyDir = Join-Path $forkRoot 'only'

# The agent is a separate uid-2000 process with its own contract. These names are spoken
# across that boundary and MUST survive the rename untouched:
#   com.byd.dashcast.agent.Agent              class inside dashcast-agent.jar
#   com.byd.dashcast.agent.AgentBinder        Binder interface of that jar
#   com.byd.dashcast.action.AGENT_READY       broadcast the agent sends
#   com.byd.dashcast.extra.AGENT_BINDER       extra key on that broadcast
# Everything else in the fork's own namespace gets the fork prefix. The last two rules are
# fork-private plumbing: renaming them keeps the two packages' internal intents distinct.
function Convert-Package([string]$t) {
    $t = $t -replace '(?m)^package com\.byd\.dashcast\.adb;', 'package com.byd.dashcast.netease.adb;'
    $t = $t -replace '(?m)^package com\.byd\.dashcast;', 'package com.byd.dashcast.netease;'
    $t = $t -replace '(?m)^import com\.byd\.dashcast\.adb\.', 'import com.byd.dashcast.netease.adb.'
    $t = $t -replace 'com\.byd\.dashcast\.action\.RETRY_AGENT', 'com.byd.dashcast.netease.action.RETRY_AGENT'
    $t = $t -replace 'com\.byd\.dashcast\.extra\.NEXT_ACTIVITY', 'com.byd.dashcast.netease.extra.NEXT_ACTIVITY'
    return $t
}

# Insert an import in alphabetical position unless the file already has it. The branch's
# restored code needs classes (Bitmap, SystemClock, MotionEvent) the v1.0.0 main project
# no longer imports, and hard-coding an insertion point would break on the next reorder.
function Add-Import([string]$text, [string]$imp) {
    $line = 'import ' + $imp + ';'
    if ($text.Contains("`n$line`n")) { return $text }
    $lines = New-Object System.Collections.ArrayList
    foreach ($l in ($text -split "`n")) { $null = $lines.Add($l) }
    $first = -1; $last = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith('import ')) { if ($first -lt 0) { $first = $i }; $last = $i }
    }
    if ($first -lt 0) { throw "no import block while adding: $imp" }
    $pos = $last + 1
    for ($i = $first; $i -le $last; $i++) {
        if ([string]::CompareOrdinal($lines[$i], $line) -gt 0) { $pos = $i; break }
    }
    $null = $lines.Insert($pos, $line)
    return ($lines -join "`n")
}

# Index of the '}' that closes the block opened at $openBrace, skipping braces that live in
# string / char literals or comments. A plain regex cannot do this and the fork's onCreate
# fragment must land on exact boundaries, so match properly.
function Get-BodyEnd([string]$text, [int]$openBrace) {
    $depth = 0; $i = $openBrace; $n = $text.Length
    $inStr = $false; $inChr = $false; $inLine = $false; $inBlk = $false
    $BS = [char]92; $DQ = [char]34; $SQ = [char]39; $SL = [char]47; $ST = [char]42
    $LB = [char]123; $RB = [char]125
    while ($i -lt $n) {
        $c = $text[$i]
        $d = if ($i + 1 -lt $n) { $text[$i + 1] } else { [char]0 }
        if ($inLine) { if ($c -eq "`n") { $inLine = $false } }
        elseif ($inBlk) { if ($c -eq $ST -and $d -eq $SL) { $inBlk = $false; $i++ } }
        elseif ($inStr) { if ($c -eq $BS) { $i++ } elseif ($c -eq $DQ) { $inStr = $false } }
        elseif ($inChr) { if ($c -eq $BS) { $i++ } elseif ($c -eq $SQ) { $inChr = $false } }
        else {
            if ($c -eq $SL -and $d -eq $SL) { $inLine = $true; $i++ }
            elseif ($c -eq $SL -and $d -eq $ST) { $inBlk = $true; $i++ }
            elseif ($c -eq $DQ) { $inStr = $true }
            elseif ($c -eq $SQ) { $inChr = $true }
            elseif ($c -eq $LB) { $depth++ }
            elseif ($c -eq $RB) { $depth--; if ($depth -eq 0) { return $i } }
        }
        $i++
    }
    return -1
}

# A fragment file is a sequence of blocks, each introduced by an ASCII marker line:
#     //@@ <AFTER|BEFORE> <anchorName>
# and running up to the next marker. AFTER inserts the block right after the matched
# anchor (the anchor's own line ending stays where it was, so a leading blank line in the
# fragment produces a blank line before the block); BEFORE inserts the block plus one blank
# line in front of the anchor. Every anchor is a regex over the main project's own source:
# if the main project moves it, the run fails loudly instead of silently dropping a patch.
function Get-Blocks([string]$text) {
    $blocks = New-Object System.Collections.ArrayList
    $cur = $null
    $sb = New-Object System.Text.StringBuilder
    foreach ($raw in ($text -split "`n")) {
        $line = $raw.TrimEnd("`r")
        $m = [regex]::Match($line, '^//@@\s+(AFTER|BEFORE)\s+([A-Za-z0-9]+)\s*$')
        if ($m.Success) {
            if ($null -ne $cur) {
                $null = $blocks.Add(@{ Mode = $cur.Mode; Name = $cur.Name
                                       Body = $sb.ToString().TrimEnd("`r", "`n") })
            }
            $cur = @{ Mode = $m.Groups[1].Value; Name = $m.Groups[2].Value }
            $null = $sb.Clear()
            continue
        }
        if ($null -eq $cur) { continue }
        $null = $sb.Append($line).Append("`n")
    }
    if ($null -ne $cur) {
        $null = $blocks.Add(@{ Mode = $cur.Mode; Name = $cur.Name
                               Body = $sb.ToString().TrimEnd("`r", "`n") })
    }
    return $blocks
}

function Apply-Blocks([string]$text, [hashtable]$anchors, [string]$fragmentPath) {
    $blocks = Get-Blocks (Read-Text $fragmentPath)
    if ($blocks.Count -eq 0) { throw "no marker blocks in $fragmentPath" }
    foreach ($b in $blocks) {
        if (-not $anchors.ContainsKey($b.Name)) {
            throw "unknown anchor '$($b.Name)' in $(Split-Path -Leaf $fragmentPath)"
        }
        $pattern = $anchors[$b.Name]
        if ($b.Body.Length -eq 0) { throw "empty block for anchor '$($b.Name)'" }
        if ($pattern -eq 'LAST_BRACE') {
            $idx = $text.LastIndexOf('}')
            if ($idx -lt 0) { throw "no closing brace found for anchor '$($b.Name)'" }
            $text = $text.Substring(0, $idx) + $b.Body + "`n" + $text.Substring($idx)
            continue
        }
        $m = [regex]::Match($text, $pattern)
        if (-not $m.Success) { throw "anchor not found in the main project: $($b.Name)" }
        if ($b.Mode -eq 'AFTER') {
            $end = $m.Index + $m.Length
            $text = $text.Substring(0, $end) + "`n" + $b.Body + $text.Substring($end)
        } else {
            $text = $text.Substring(0, $m.Index) + $b.Body + "`n`n" + $text.Substring($m.Index)
        }
    }
    return $text
}

$castAnchors = @{}
$castAnchors['heartbeatConstant'] =
    '(?m)^    private static final long HEARTBEAT_INTERVAL_MS = 2000;$'
$castAnchors['sessionField'] =
    '(?m)^    private DashboardSession session;$'
$castAnchors['receiverAttachCheck'] =
    '(?m)^            if \(!injector\.isAttached\(\)\) \{\n                refreshStatus\(\);\n                return;\n            \}$'
$castAnchors['refreshStatusSignature'] =
    '(?m)^    private void refreshStatus\(\) \{$'
$castAnchors['mirroringAssignment'] =
    '(?m)^        mirroring = active;$'
$castAnchors['setStatusTextHead'] =
    '(?m)^    private void setStatus\(CharSequence text\) \{\n        Log\.i\(TAG, [^\n]*\);$'
$castAnchors['classEnd'] = 'LAST_BRACE'

$injectAnchors = @{}
$injectAnchors['transactConstants'] =
    '(?m)^    private static final int TRANSACT_MOVE_TO_DISPLAY = IBinder\.FIRST_CALL_TRANSACTION \+ 11;$'
$injectAnchors['classEnd'] = 'LAST_BRACE'

# --- clean the generated subtrees (never fork/, never assets/) --------------------------
Write-Output '--- clean generated subtrees ---'
foreach ($p in @('src', 'res')) {
    $d = Join-Path $Fork $p
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
}
foreach ($f in @('AndroidManifest.xml', 'build.ps1')) {
    $p = Join-Path $Fork $f
    if (Test-Path $p) { Remove-Item -Force $p }
}

# --- java: verbatim copy + package prefix ---------------------------------------------
Write-Output '--- java: main sources, package renamed ---'
$mainSrc = Join-Path $Main 'src'
$prefix = 'com\byd\dashcast\'
$copied = 0
foreach ($f in (Get-ChildItem $mainSrc -Recurse -Filter *.java)) {
    $rel = $f.FullName.Substring($mainSrc.Length + 1)
    if (-not $rel.StartsWith($prefix)) { throw "unexpected source path: $rel" }
    $relFork = 'com\byd\dashcast\netease\' + $rel.Substring($prefix.Length)
    $dst = Join-Path (Join-Path $Fork 'src') $relFork
    Write-Text $dst (Convert-Package (Read-Text $f.FullName))
    $copied++
}
Write-Output ("  {0} file(s) from {1}" -f $copied, $mainSrc)

Write-Output '--- CastActivity: main copy + the fork''s fragments ---'
$text = Convert-Package (Read-Text (Join-Path $Main 'src\com\byd\dashcast\CastActivity.java'))
foreach ($imp in @('android.graphics.Bitmap', 'android.os.SystemClock')) {
    $text = Add-Import $text $imp
}

$cls = 'public final class CastActivity extends Activity'
$ci = $text.IndexOf($cls)
if ($ci -lt 0) { throw 'anchor not found: CastActivity class declaration' }
$docStart = $text.LastIndexOf('/**', $ci)
if ($docStart -lt 0) { throw 'anchor not found: CastActivity class javadoc' }
$doc = (Read-Text (Join-Path $forkRoot 'CastActivity.doc.txt')).TrimEnd("`r", "`n")
$text = $text.Substring(0, $docStart) + $doc + "`n" + $text.Substring($ci)

$sig = 'protected void onCreate(Bundle savedInstanceState)'
$si = $text.IndexOf($sig)
if ($si -lt 0) { throw 'anchor not found: onCreate signature' }
$brace = $text.IndexOf('{', $si)
if ($brace -lt 0) { throw 'anchor not found: onCreate opening brace' }
$end = Get-BodyEnd $text $brace
if ($end -lt 0) { throw 'brace matching failed for onCreate' }
$ov = $text.LastIndexOf('@Override', $si)
if ($ov -lt 0) { throw 'anchor not found: @Override above onCreate' }
$start = $ov
while ($start -gt 0 -and ($text[$start - 1] -eq ' ' -or $text[$start - 1] -eq "`t")) { $start-- }
$onCreate = (Read-Text (Join-Path $forkRoot 'CastActivity.onCreate.txt')).TrimEnd("`r", "`n")
$text = $text.Substring(0, $start) + $onCreate + $text.Substring($end + 1)

$text = Apply-Blocks $text $castAnchors (Join-Path $forkRoot 'CastActivity.overrides.txt')
$castDst = Join-Path $Fork 'src\com\byd\dashcast\netease\CastActivity.java'
Write-Text $castDst $text
Write-Output '  CastActivity.java'

Write-Output '--- InjectClient: main copy + the restored tap() ---'
$ic = Convert-Package (Read-Text (Join-Path $Main 'src\com\byd\dashcast\InjectClient.java'))
foreach ($imp in @('android.os.SystemClock', 'android.view.MotionEvent')) {
    $ic = Add-Import $ic $imp
}
$ic = Apply-Blocks $ic $injectAnchors (Join-Path $forkRoot 'InjectClient.tap.txt')
$icDst = Join-Path $Fork 'src\com\byd\dashcast\netease\InjectClient.java'
Write-Text $icDst $ic
Write-Output '  InjectClient.java'

# --- branch-only files the main project no longer has ---------------------------------
Write-Output '--- only/: branch-only sources ---'
foreach ($f in @('AutoCast.java', 'CarAccount.java', 'DashboardEye.java')) {
    $src = Join-Path $onlyDir $f
    if (-not (Test-Path $src)) { throw "branch-only source missing: $src" }
    $dst = Join-Path $Fork "src\com\byd\dashcast\netease\$f"
    Write-Text $dst (Convert-Package (Read-Text $src))
    Write-Output "  $f"
}

# --- res ------------------------------------------------------------------------------
Write-Output '--- res: main resources + the fork''s tap table ---'
Copy-Item (Join-Path $Main 'res') (Join-Path $Fork 'res') -Recurse -Force
$tapSrc = Join-Path $onlyDir 'quick_taps.xml'
if (-not (Test-Path $tapSrc)) { throw "branch-only resource missing: $tapSrc" }
Copy-Item $tapSrc (Join-Path $Fork 'res\values\quick_taps.xml') -Force
Write-Output '  res\values\quick_taps.xml'

$stPath = Join-Path $Fork 'res\values\strings.xml'
$st = Read-Text $stPath
$appName = (Read-Text (Join-Path $forkRoot 'app_name.txt')).Trim()
if ($appName.Length -eq 0) { throw 'fork\app_name.txt is empty' }
$m = [regex]::Match($st, '<string name="app_name">[^<]*</string>')
if (-not $m.Success) { throw 'anchor not found: app_name string' }
$want = '<string name="app_name">' + $appName + '</string>'
$st = $st.Substring(0, $m.Index) + $want + $st.Substring($m.Index + $m.Length)

# String resources the branch's code needs but v1.0.0 of the main project dropped when it
# deleted the auto-cast orchestration. Deleting the call sites instead would be changing
# behaviour, so the branch carries the resources. The wording is owned by the branch and
# lives in fork\strings.branch.xml -- read as UTF-8, never typed into this script (which
# has to stay ASCII), and never inherited from the old development tree: that wording was
# about the main project's "first-launch auto" switch, which is not what this branch is.
$branchStringsPath = Join-Path $forkRoot 'strings.branch.xml'
if (-not (Test-Path $branchStringsPath)) { throw "branch strings fragment missing: $branchStringsPath" }
$entries = [regex]::Matches((Read-Text $branchStringsPath), '<string name="([^"]+)">.*?</string>')
if ($entries.Count -eq 0) { throw "no <string> entries in $branchStringsPath" }
$overridden = 0
$appended = New-Object System.Collections.ArrayList
foreach ($e in $entries) {
    $pat = '<string name="' + [regex]::Escape($e.Groups[1].Value) + '">.*?</string>'
    $cur = [regex]::Match($st, $pat)
    if ($cur.Success) {
        if ($cur.Value -ne $e.Value) {
            $st = $st.Substring(0, $cur.Index) + $e.Value + $st.Substring($cur.Index + $cur.Length)
            $overridden++
        }
    } else {
        $null = $appended.Add('    ' + $e.Value)
    }
}
if ($appended.Count -gt 0) {
    $block = @()
    $block += ''
    $block += '    <!-- netease fork only: the main project dropped the auto-cast orchestration'
    $block += '         and its strings; the branch still runs that script. -->'
    $block += $appended
    $close = $st.LastIndexOf('</resources>')
    if ($close -lt 0) { throw 'anchor not found: </resources> in res\values\strings.xml' }
    $st = $st.Substring(0, $close) + (($block -join "`n") + "`n") + $st.Substring($close)
}
# Assert every branch-owned name really landed. A silent miss would ship a resource the
# branch's code still references, which fails at inflate time on the car -- far more
# expensive to find than a sync failure.
foreach ($e in $entries) {
    $name = $e.Groups[1].Value
    if (-not [regex]::IsMatch($st, '<string name="' + [regex]::Escape($name) + '">')) {
        throw "branch string '$name' missing from the generated res\values\strings.xml"
    }
}
Write-Text $stPath $st
Write-Output ("  res\values\strings.xml (branch strings: {0} overridden, {1} appended)" -f $overridden, $appended.Count)

# --- manifest -------------------------------------------------------------------------
Write-Output '--- AndroidManifest.xml ---'
$mf = Read-Text (Join-Path $Main 'AndroidManifest.xml')
$mf = $mf -replace 'package="com\.byd\.dashcast"', 'package="com.byd.dashcast.netease"'
$mf = $mf -replace 'com\.byd\.dashcast\.adb\.BootReceiver', 'com.byd.dashcast.netease.adb.BootReceiver'
$mf = $mf -replace 'android:versionName="[^"]*"', 'android:versionName="1.0.0-netease"'
if ($mf -notmatch 'package="com\.byd\.dashcast\.netease"') { throw 'manifest package rename failed' }
if ($mf -notmatch 'com\.byd\.dashcast\.netease\.adb\.BootReceiver') { throw 'manifest receiver rename failed' }
if ($mf -notmatch '\.DisclaimerActivity') { throw 'manifest lost the DisclaimerActivity launcher entry' }
Write-Text (Join-Path $Fork 'AndroidManifest.xml') $mf
Write-Output '  AndroidManifest.xml'

# --- build.ps1 ------------------------------------------------------------------------
Write-Output '--- build.ps1 ---'
$bp = Read-Text (Join-Path $Main 'build.ps1')
$before = $bp
$bp = $bp -replace "'dashcast\.apk'", "'dashcast-netease.apk'"
# The fork has no agent/ of its own: the uid-2000 agent is built in the main project and is
# shared byte-for-byte (one process serves both packages). Point the fork's build at the
# main project's jar instead of a sibling directory that does not exist here. Relative
# path only -- the fork sits two levels below the repository root.
$bp = $bp -replace [regex]::Escape('(Split-Path -Parent $root)'), "(Join-Path `$root '..\..')"
if ($bp -eq $before) { throw 'build.ps1: no replacement matched (main build.ps1 changed?)' }
foreach ($must in @("'dashcast-netease.apk'", "'..\..'", '[string]$Keystore', '[string]$KeyPass',
                    '[string]$KeyAlias', 'exit 0')) {
    if ($bp -notmatch [regex]::Escape($must)) { throw "build.ps1: missing required token $must" }
}
Write-Text (Join-Path $Fork 'build.ps1') $bp
Write-Output '  build.ps1'

# --- verify ---------------------------------------------------------------------------
Write-Output '--- verify: encoding ---'
# U+FFFD anywhere means some source string was decoded with the wrong encoding. That is
# exactly how a Chinese literal decoded through the wrong code page turns into mojibake,
# and it is invisible in a directory listing -- so assert on it.
$corrupt = 0
foreach ($f in (Get-ChildItem (Join-Path $Fork 'src') -Recurse -Filter *.java)) {
    if ((Read-Text $f.FullName).Contains([char]0xFFFD)) {
        Write-Output "  CORRUPT $($f.Name) contains U+FFFD"
        $corrupt++
    }
}
foreach ($f in (Get-ChildItem (Join-Path $Fork 'res') -Recurse -Filter *.xml)) {
    if ((Read-Text $f.FullName).Contains([char]0xFFFD)) {
        Write-Output "  CORRUPT $($f.Name) contains U+FFFD"
        $corrupt++
    }
}
if (-not (Read-Text $stPath).Contains($want)) {
    Write-Output '  CORRUPT res\values\strings.xml: app_name did not round-trip'
    $corrupt++
}
foreach ($name in $needed) {
    if (-not (Read-Text $stPath).Contains('name="' + $name + '"')) {
        Write-Output "  CORRUPT res\values\strings.xml: $name missing"
        $corrupt++
    }
}
if ($corrupt -gt 0) { Write-Output "FAILED: $corrupt corrupted file(s)"; exit 1 }
Write-Output '  OK   no encoding corruption'

Write-Output '--- verify: the agent contract must be untouched ---'
$contract = @(
    'com.byd.dashcast.agent.Agent',
    'com.byd.dashcast.agent.AgentBinder',
    'com.byd.dashcast.action.AGENT_READY',
    'com.byd.dashcast.extra.AGENT_BINDER'
)
$bad = 0
$javaFiles = Get-ChildItem (Join-Path $Fork 'src') -Recurse -Filter *.java
foreach ($token in $contract) {
    # -SimpleMatch: these are literal dotted names, not patterns.
    $hit = $javaFiles | Select-String -SimpleMatch $token -List
    if ($hit) { Write-Output "  OK   $token" } else { Write-Output "  MISS $token"; $bad++ }
}
$forkPrivate = @(
    'com.byd.dashcast.netease.action.RETRY_AGENT',
    'com.byd.dashcast.netease.extra.NEXT_ACTIVITY'
)
foreach ($token in $forkPrivate) {
    $hit = $javaFiles | Select-String -SimpleMatch $token -List
    if ($hit) { Write-Output "  OK   $token (fork-private)" } else { Write-Output "  MISS $token"; $bad++ }
}
if ($bad -gt 0) { Write-Output "FAILED: $bad contract token(s) wrong"; exit 1 }

Write-Output '--- verify: the renamed tree is complete ---'
$bad = 0
foreach ($token in @('package com.byd.dashcast.netease;', 'package com.byd.dashcast.netease.adb;')) {
    $hit = $javaFiles | Select-String -SimpleMatch $token -List
    if ($hit) { Write-Output "  OK   $token" } else { Write-Output "  MISS $token"; $bad++ }
}
$stale = $javaFiles | Select-String -Pattern '^package com\.byd\.dashcast(\.adb)?;' -List
if ($stale) {
    Write-Output "  MISS unrenamed package remains in: $($stale.Path -join ', ')"
    $bad++
} else {
    Write-Output '  OK   no main-package declaration left in the fork'
}

Write-Output '--- verify: the restored auto-cast code is in place ---'
$castOut = Read-Text $castDst
$present = @(
    'private static final long AUTO_BIND_TIMEOUT_MS',
    'private static final long TASK_SETTLE_TIMEOUT_MS',
    'private static final long SETTLE_STEADY_MS',
    'private static final long TAP_SETTLE_MS',
    'private static final long SETTLE_AFTER_MOVE_MS',
    'private static final int CLUSTER_WIDTH = 1920',
    'private static final int CLUSTER_HEIGHT = 720',
    'private AutoCast autoCast;',
    'private boolean autoMode;',
    'private boolean autoStarted;',
    'private boolean quickCastOk;',
    'private void startAutoCast()',
    'private void runAutoCast()',
    'private void runQuickCast(final AutoCast.Target target, final boolean background,',
    'private boolean tapUntilTargetPage(AutoCast.Target target, int display)',
    'private void settleThenTap(final AutoCast.Target target, final int display,',
    'private static void sleepQuietly(long ms)',
    'injector.tap(display, point[0], point[1]);',
    'DashboardEye.grab(injector, CLUSTER_WIDTH, CLUSTER_HEIGHT)',
    'DashboardEye.classify(frame)',
    'injector.watch(target.packageName, display);'
)
foreach ($t in $present) {
    if ($castOut.Contains($t)) { Write-Output "  OK   $t" } else { Write-Output "  MISS $t"; $bad++ }
}
# Declaration-shaped, not bare names: the fragments explain *why* those two concepts are
# gone, so a bare-name search would trip over the explanation itself.
$absent = @('boolean keepWatchAfterTarget', 'boolean persistentWatch')
foreach ($t in $absent) {
    if ($castOut.Contains($t)) { Write-Output "  MISS $t should not exist any more"; $bad++ }
    else { Write-Output "  OK   gone: $t" }
}
if (-not (Read-Text $icDst).Contains('public void tap(int displayId, float x, float y)')) {
    Write-Output '  MISS InjectClient.tap'; $bad++
} else {
    Write-Output '  OK   InjectClient.tap'
}
if ($bad -gt 0) { Write-Output "FAILED: $bad problem(s) in the generated fork"; exit 1 }

Write-Output 'DONE'
