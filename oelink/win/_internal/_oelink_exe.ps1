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

function Launch-WithArgs($ExePath, $ArgsStr) {
    $shell = New-Object -ComObject WScript.Shell
    $null = $shell.Run("`"$ExePath`" $ArgsStr", 1, $false)
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

switch ($browser) {
    'chrome' {
        $exe = Find-Exe $CHROME_PATHS
        if (-not $exe) { Add-Content $LOG '[oelink] Chrome not found'; exit 1 }

        if (Check-OelinkChrome $targetId) { exit 0 }

        if ([string]::IsNullOrEmpty($argsStr)) {
            Add-Content $LOG '[oelink] Launching Chrome with no args'
            Start-Process $exe
        } else {
            Add-Content $LOG "[oelink] Launching Chrome: $argsStr"
            Launch-WithArgs $exe $argsStr
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
