Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR  = $PSScriptRoot
$SOURCE_EXE  = "$SCRIPT_DIR\oelink.exe"
$INSTALL_DIR = "$env:LOCALAPPDATA\oelink"
$INSTALL_EXE = "$INSTALL_DIR\oelink.exe"
$SCHEME      = "oelink"
$REG_ROOT    = "HKCU:\Software\Classes"

if (-not (Test-Path $SOURCE_EXE)) {
    Write-Error "oelink.exe not found. Run build_oelink.ps1 first."
    exit 1
}

Write-Host "Removing existing installation..." -ForegroundColor Yellow
if (Test-Path $INSTALL_DIR) { Remove-Item $INSTALL_DIR -Recurse -Force }
if (Test-Path "$REG_ROOT\$SCHEME") { Remove-Item "$REG_ROOT\$SCHEME" -Recurse -Force }

New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null

Write-Host "Installing oelink.exe..." -ForegroundColor Yellow
Copy-Item $SOURCE_EXE $INSTALL_EXE -Force

Write-Host "Registering oelink:// URL scheme..." -ForegroundColor Yellow
$regBase = "$REG_ROOT\$SCHEME"
New-Item -Path $regBase -Force | Out-Null
Set-ItemProperty -Path $regBase -Name '(Default)'        -Value 'URL:oelink Protocol'
New-ItemProperty  -Path $regBase -Name 'URL Protocol'     -Value '' -PropertyType String -Force | Out-Null
New-ItemProperty  -Path $regBase -Name 'FriendlyTypeName' -Value 'oelink' -PropertyType String -Force | Out-Null

$regApp = "$regBase\Application"
New-Item -Path $regApp -Force | Out-Null
New-ItemProperty -Path $regApp -Name 'ApplicationName'        -Value 'oelink' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $regApp -Name 'ApplicationDescription' -Value 'oelink' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $regApp -Name 'ApplicationIcon'        -Value "`"$INSTALL_EXE`",0" -PropertyType String -Force | Out-Null

$regOpen = "$regBase\shell\open\command"
New-Item -Path $regOpen -Force | Out-Null
Set-ItemProperty -Path $regOpen -Name '(Default)' -Value "`"$INSTALL_EXE`" `"%1`""

function Make-Url($Browser, $BrowserArgs) {
    $bytes   = [System.Text.Encoding]::UTF8.GetBytes($BrowserArgs)
    $b64     = [Convert]::ToBase64String($bytes)
    $urlSafe = $b64 -replace '\+', '-' -replace '/', '_' -replace '=', ''
    return "oelink://${Browser}?${urlSafe}"
}

$ARGS_NORMAL    = '--host-resolver-rules="MAP example.com 127.0.0.1" https://example.com'
$ARGS_INCOGNITO = '--host-resolver-rules="MAP example.com 127.0.0.1" --incognito https://example.com'

Write-Host ""
Write-Host "Installation complete!  Launcher: $INSTALL_EXE" -ForegroundColor Green
Write-Host ""
Write-Host "------------------------------------------------------------"
Write-Host " Test URLs (paste into Run dialog or browser address bar)"
Write-Host "------------------------------------------------------------"
Write-Host ""
Write-Host " [Chrome - Normal]"
Write-Host "   $(Make-Url 'chrome' $ARGS_NORMAL)"
Write-Host ""
Write-Host " [Chrome - Incognito]"
Write-Host "   $(Make-Url 'chrome' $ARGS_INCOGNITO)"
Write-Host ""
Write-Host "------------------------------------------------------------"
Write-Host " * SmartScreen blocked: click 'More info' -> 'Run anyway'"
Write-Host " * ExecutionPolicy error: run with"
Write-Host "   powershell -ExecutionPolicy Bypass -File install_win_oelink.ps1"
Write-Host "------------------------------------------------------------"
