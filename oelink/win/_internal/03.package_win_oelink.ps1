Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR = $PSScriptRoot
$OUT_DIR    = "$SCRIPT_DIR\.."
$OUTPUT_EXE = "$OUT_DIR\oelink.exe"
$CA_CER     = "$OUT_DIR\oelink_local_cert.cer"

if (-not (Test-Path $OUTPUT_EXE)) {
    Write-Error "oelink.exe not found. Run build_win_oelink.ps1 first."
    exit 1
}

# Package distribution zip. Does not rebuild oelink.exe, so a manually/externally
# signed exe is packaged as-is. install/uninstall are plain .cmd (no PowerShell
# dependency for end users). ca.cer (public cert only, never the .pvk private
# key) is bundled if present so install.cmd can register it as a trusted
# publisher on the target machine.
$STATIC_DIR = "$OUT_DIR\..\..\src\main\resources\static\oelink"
$ZIP_PATH   = "$STATIC_DIR\install_windows_oelink_scheme.zip"

$files = @(
    $OUTPUT_EXE,
    "$OUT_DIR\install_win_oelink.cmd",
    "$OUT_DIR\uninstall_win_oelink.cmd"
)
if (Test-Path $CA_CER) {
    $files += $CA_CER
} else {
    Write-Host "Note: oelink_local_cert.cer not found next to oelink.exe — packaging without it." -ForegroundColor Yellow
}

Write-Host "Packaging $ZIP_PATH ..." -ForegroundColor Yellow
if (Test-Path $ZIP_PATH) { Remove-Item $ZIP_PATH -Force }
Compress-Archive -Path $files -DestinationPath $ZIP_PATH
Write-Host "Packaged: $ZIP_PATH" -ForegroundColor Green
