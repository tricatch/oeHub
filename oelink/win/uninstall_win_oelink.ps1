Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$INSTALL_DIR = "$env:LOCALAPPDATA\oelink"
$REG_ROOT    = "HKCU:\Software\Classes"
$SCHEME      = "oelink"

Write-Host "Removing registry entry..." -ForegroundColor Yellow
if (Test-Path "$REG_ROOT\$SCHEME") {
    Remove-Item "$REG_ROOT\$SCHEME" -Recurse -Force
    Write-Host "  Removed HKCU\Software\Classes\$SCHEME" -ForegroundColor Gray
} else {
    Write-Host "  Registry entry not found, skipping." -ForegroundColor Gray
}

Write-Host "Removing install directory..." -ForegroundColor Yellow
if (Test-Path $INSTALL_DIR) {
    Remove-Item $INSTALL_DIR -Recurse -Force
    Write-Host "  Removed $INSTALL_DIR" -ForegroundColor Gray
} else {
    Write-Host "  Install directory not found, skipping." -ForegroundColor Gray
}

Write-Host ""
Write-Host "Uninstall complete." -ForegroundColor Green
