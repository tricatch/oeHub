param([string]$RawUrl = "")

$CHROME_PATHS = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)

$LOG = "$env:TEMP\oelink_debug.log"

$UI_LANG = (Get-UICulture).TwoLetterISOLanguageName
if ($UI_LANG -ne 'ko') { $UI_LANG = 'en' }

$MSG = @{
    ko = @{
        ChromeRunning = "동일한 프로필로 실행 중인 Chrome이 있습니다.`n종료하고 다시 실행하시겠습니까?"
    }
    en = @{
        ChromeRunning = "Chrome is already running with the same profile.`nClose it and relaunch?"
    }
}

function Find-Exe($Paths) {
    foreach ($p in $Paths) { if (Test-Path $p) { return $p } }
    return $null
}

# --oelink=<id> value extraction (value-less --oelink flag is ignored)
function Get-OelinkId([string]$CmdLine) {
    if ([string]::IsNullOrEmpty($CmdLine)) { return $null }
    if ($CmdLine -match '--oelink=([^\s"]+)') {
        return $Matches[1]
    }
    return $null
}

function Get-ConflictingChrome($TargetId) {
    # --oelink=0        -> look for Chrome with no --user-data-dir (default profile)
    # --oelink=<crc32>  -> look for Chrome with a matching --oelink value
    # Chrome running without --oelink at all is out of scope for detection, unless TargetId is 0.
    $allChrome = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" -ErrorAction SilentlyContinue
    return $allChrome | Where-Object {
        if ($_.CommandLine -like '*--type=*') { return $false }
        if ($TargetId -eq '0') {
            return $_.CommandLine -notlike '*--user-data-dir*'
        }
        return (Get-OelinkId $_.CommandLine) -eq $TargetId
    }
}

function Stop-OelinkChrome($TargetId) {
    $mainProcs = Get-ConflictingChrome $TargetId
    if (-not $mainProcs) { return }

    $pids = @($mainProcs | ForEach-Object { $_.ProcessId })
    Add-Content $LOG "[oelink] Closing Chrome (oelink='$TargetId', PIDs: $($pids -join ', '))"

    foreach ($procId in $pids) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc) { $proc.CloseMainWindow() | Out-Null }
    }
    Start-Sleep -Seconds 3

    foreach ($procId in $pids) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc -and -not $proc.HasExited) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Add-Content $LOG "[oelink] Force-killed Chrome PID: $procId"
        }
    }
}

function Check-OelinkChrome($TargetId) {
    if (-not (Get-Process -Name 'chrome' -ErrorAction SilentlyContinue)) { return $false }

    $mainProcs = Get-ConflictingChrome $TargetId
    if (-not $mainProcs) { return $false }

    $mainPids = @($mainProcs | ForEach-Object { $_.ProcessId })
    Add-Content $LOG "[oelink] Found running Chrome (oelink='$TargetId', PIDs: $($mainPids -join ', '))"

    $wsh = New-Object -ComObject WScript.Shell
    $result = $wsh.Popup($MSG[$UI_LANG].ChromeRunning, 0, "oelink", 4)
    if ($result -eq 6) {
        Stop-OelinkChrome $TargetId
        return $false
    }
    return $true
}

function Decode-Base64Url($Encoded) {
    $b64 = $Encoded -replace '-', '+' -replace '_', '/'
    $rem = $b64.Length % 4
    if ($rem -eq 2) { $b64 += '==' }
    elseif ($rem -eq 3) { $b64 += '=' }
    $bytes = [Convert]::FromBase64String($b64)
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

# The argument string comes from a link anyone can craft (a shared profile, a pasted oelink:// URL),
# so it is split into words by hand (double quotes group, nothing is expanded) and every word is
# checked before Chrome is started. One rejected word cancels the whole launch.

# Flag names (without the leading --) that run programs, weaken the sandbox, open a debugging
# channel or redirect traffic. A trailing * matches any suffix.
$DENIED_FLAGS = @(
    'renderer-cmd-prefix', 'gpu-launcher', 'utility-cmd-prefix', 'zygote-cmd-prefix',
    'plugin-launcher', 'ppapi-plugin-launcher', 'nacl-gdb', 'nacl-gdb-script', 'browser-subprocess-path',
    'load-extension', 'disable-extensions-except', 'load-component-extension',
    'remote-debugging-*', 'remote-allow-origins', 'enable-automation',
    'no-sandbox', 'disable-gpu-sandbox', 'disable-setuid-sandbox', 'disable-web-security',
    'disable-site-isolation-trials', 'allow-file-access-from-files', 'allow-running-insecure-content',
    'ignore-certificate-errors*', 'proxy-pac-url', 'proxy-auto-detect', 'js-flags',
    'utility-and-browser-sandbox-cmd-prefix', 'ppapi-flash-path', 'enable-logging', 'log-file'
)

# Returns the words, or $null when a quote is left open.
function Split-OelinkArgs([string]$Line) {
    $words = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $inQuote = $false
    $have = $false
    foreach ($ch in $Line.ToCharArray()) {
        if ($ch -eq [char]'"') {
            $inQuote = -not $inQuote
            $have = $true
        } elseif (-not $inQuote -and [char]::IsWhiteSpace($ch)) {
            if ($have) { $words.Add($sb.ToString()); [void]$sb.Clear(); $have = $false }
        } else {
            [void]$sb.Append($ch)
            $have = $true
        }
    }
    if ($inQuote) { return $null }
    if ($have) { $words.Add($sb.ToString()) }
    return ,$words.ToArray()
}

# $true when the word is a permitted flag or a permitted start URL.
function Test-OelinkArg([string]$Word) {
    if ($Word -match '[\x00-\x1f]') { return $false }
    if ($Word -match '^--([A-Za-z0-9][A-Za-z0-9-]*)(=|$)') {
        $name = $Matches[1].ToLower()
        foreach ($pat in $DENIED_FLAGS) {
            if ($name -like $pat) { return $false }
        }
        return $true
    }
    if ($Word -match '^(https?://|about:|chrome://)') { return $true }
    # The delayed-redirect page hosts.pebble builds (wrapDelayedUrl in util.js).
    if ($Word.StartsWith("data:text/html,<script>setTimeout(()=>location.replace('") -and $Word.EndsWith("'),1000)</script>")) { return $true }
    return $false
}

# One word as a command-line argument: quoted whole, with the backslashes in front of the closing
# quote doubled so they cannot escape it.
function Quote-OelinkArg([string]$Word) {
    $trailing = [regex]::Match($Word, '\\*$').Value
    return '"' + $Word + $trailing + '"'
}

function Launch-WithArgs($ExePath, [string[]]$Words) {
    $shell = New-Object -ComObject WScript.Shell
    $line = ($Words | ForEach-Object { Quote-OelinkArg $_ }) -join ' '
    $null = $shell.Run("`"$ExePath`" $line", 1, $false)
}

function Reject-Launch() {
    $text = if ($UI_LANG -eq 'ko') { '허용되지 않는 실행 옵션이 포함되어 있어 실행하지 않았습니다.' } else { 'The link contains a launch option that is not allowed, so nothing was started.' }
    $wsh = New-Object -ComObject WScript.Shell
    $null = $wsh.Popup($text, 0, 'oelink', 16)
}

Add-Content $LOG "[oelink] Received: $RawUrl"

$body = $RawUrl -replace '^oelink://', ''
$browser = ''
$encoded = ''
if ($body -match '^([^?]+)\?(.*)$') {
    $browser = $Matches[1].ToLower().TrimEnd('/')
    $encoded = $Matches[2]
} else {
    $browser = $body.ToLower().TrimEnd('/')
}

$argsStr = $null
if (-not [string]::IsNullOrEmpty($encoded)) {
    try {
        $argsStr = Decode-Base64Url $encoded
    } catch {
        Add-Content $LOG '[oelink] Base64 decode failed — launching with defaults'
        $argsStr = $null
    }
}
if (-not [string]::IsNullOrEmpty($argsStr)) {
    # Expand %USERPROFILE% etc. so Chrome is actually launched with the real path.
    $argsStr = [Environment]::ExpandEnvironmentVariables($argsStr)
}
$targetId = Get-OelinkId $argsStr

# Vet every word before anything runs.
$argWords = @()
if (-not [string]::IsNullOrEmpty($argsStr)) {
    $argWords = Split-OelinkArgs $argsStr
    $bad = $null
    if ($null -eq $argWords) {
        $bad = 'unbalanced quotes'
    } else {
        foreach ($w in $argWords) {
            if (-not (Test-OelinkArg $w)) { $bad = $w; break }
        }
    }
    if ($null -ne $bad) {
        Add-Content $LOG "[oelink] Refused: argument not allowed: $bad"
        Reject-Launch
        exit 1
    }
}

switch ($browser) {
    'chrome' {
        $exe = Find-Exe $CHROME_PATHS
        if (-not $exe) { Add-Content $LOG '[oelink] Chrome not found'; exit 1 }

        if (Check-OelinkChrome $targetId) { exit 0 }

        if ($argWords.Count -eq 0) {
            Add-Content $LOG '[oelink] Launching Chrome with no args'
            Start-Process $exe
        } else {
            Add-Content $LOG "[oelink] Launching Chrome: $argsStr"
            Launch-WithArgs $exe $argWords
        }
    }
    default {
        Add-Content $LOG "[oelink] Unknown browser: '$browser' — falling back to Chrome"
        $exe = Find-Exe $CHROME_PATHS
        if ($exe) {
            if (-not (Check-OelinkChrome $targetId)) { Start-Process $exe }
        }
    }
}
