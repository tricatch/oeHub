Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR = $PSScriptRoot
$OUT_DIR    = "$SCRIPT_DIR\.."
$SOURCE_PS1 = "$SCRIPT_DIR\_oelink_exe.ps1"
$OUTPUT_EXE = "$OUT_DIR\oelink.exe"

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

Write-Host ""
Write-Host "Note: oelink.exe is unsigned. Sign it now (02.sign_win_oelink.cmd), then run 03.package_win_oelink.cmd (it will NOT rebuild the exe)." -ForegroundColor Yellow
