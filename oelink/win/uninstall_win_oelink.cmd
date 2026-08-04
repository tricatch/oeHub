@echo off
setlocal

set "INSTALL_DIR=%LOCALAPPDATA%\oelink"

echo Removing registry entry...
reg query "HKCU\Software\Classes\oelink" >nul 2>&1
if %errorlevel%==0 (
    reg delete "HKCU\Software\Classes\oelink" /f >nul
    echo   Removed HKCU\Software\Classes\oelink
) else (
    echo   Registry entry not found, skipping.
)

echo Removing install directory...
if exist "%INSTALL_DIR%" (
    rmdir /s /q "%INSTALL_DIR%"
    echo   Removed %INSTALL_DIR%
) else (
    echo   Install directory not found, skipping.
)

echo.
echo Uninstall complete.
pause
