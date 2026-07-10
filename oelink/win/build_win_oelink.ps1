Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR = $PSScriptRoot
$SOURCE_PS1 = "$SCRIPT_DIR\_oelink_exe.ps1"
$OUTPUT_EXE = "$SCRIPT_DIR\oelink.exe"

if (-not (Test-Path $SOURCE_PS1)) {
    Write-Error "Source not found: $SOURCE_PS1"
    exit 1
}

$ps2exeMod = Get-Module -ListAvailable -Name ps2exe | Sort-Object Version -Descending | Select-Object -First 1
if (-not $ps2exeMod) {
    Write-Host "ps2exe not found. Installing from PSGallery..." -ForegroundColor Yellow
    Install-Module -Name ps2exe -Scope CurrentUser -Force -AllowClobber
    $ps2exeMod = Get-Module -ListAvailable -Name ps2exe | Sort-Object Version -Descending | Select-Object -First 1
}

Write-Host "Compiling $SOURCE_PS1 -> $OUTPUT_EXE ..." -ForegroundColor Yellow

# ps2exe must run in Windows PowerShell (5.x / Desktop edition).
# When called from pwsh (Core), Invoke-ps2exe re-spawns powershell.exe without -ExecutionPolicy Bypass,
# which fails on systems with a restrictive execution policy. Call powershell.exe directly with Bypass instead.
powershell.exe -ExecutionPolicy Bypass -NonInteractive -Command `
    "Import-Module '$($ps2exeMod.Path)' -Force; Invoke-ps2exe -InputFile '$SOURCE_PS1' -OutputFile '$OUTPUT_EXE' -NoConsole -x64 -title 'oelink' -description 'oelink' -product 'oelink' -company '' -version '1.0.0.0'"

if (Test-Path $OUTPUT_EXE) {
    Write-Host "Build succeeded: $OUTPUT_EXE" -ForegroundColor Green
} else {
    Write-Error "Build failed: $OUTPUT_EXE was not created."
    exit 1
}

# Package distribution zip
$STATIC_DIR = "$SCRIPT_DIR\..\..\src\main\resources\static\oelink"
$ZIP_PATH   = "$STATIC_DIR\install_windows_oelink_scheme.zip"

Write-Host "Packaging $ZIP_PATH ..." -ForegroundColor Yellow
if (Test-Path $ZIP_PATH) { Remove-Item $ZIP_PATH -Force }
Compress-Archive -Path @(
    $OUTPUT_EXE,
    "$SCRIPT_DIR\install_win_oelink.cmd",
    "$SCRIPT_DIR\install_win_oelink.ps1",
    "$SCRIPT_DIR\uninstall_win_oelink.cmd",
    "$SCRIPT_DIR\uninstall_win_oelink.ps1"
) -DestinationPath $ZIP_PATH
Write-Host "Packaged: $ZIP_PATH" -ForegroundColor Green
