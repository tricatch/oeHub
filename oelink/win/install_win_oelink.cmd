@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "SOURCE_EXE=%SCRIPT_DIR%oelink.exe"
set "INSTALL_DIR=%LOCALAPPDATA%\oelink"
set "INSTALL_EXE=%INSTALL_DIR%\oelink.exe"
set "CA_CER=%SCRIPT_DIR%oelink_local_cert.cer"

if not exist "%SOURCE_EXE%" (
    echo oelink.exe not found. Run build_win_oelink.cmd first.
    pause
    exit /b 1
)

echo Removing existing installation...
if exist "%INSTALL_DIR%" rmdir /s /q "%INSTALL_DIR%"
reg delete "HKCU\Software\Classes\oelink" /f >nul 2>&1

mkdir "%INSTALL_DIR%"

echo Installing oelink.exe...
copy /y "%SOURCE_EXE%" "%INSTALL_EXE%" >nul

rem Files downloaded via a browser carry the Mark-of-the-Web (Zone.Identifier
rem alternate data stream), which triggers the SmartScreen "publisher could
rem not be verified" prompt. Clearing the stream has the same effect as
rem PowerShell's Unblock-File.
echo. > "%SOURCE_EXE%:Zone.Identifier" 2>nul
echo. > "%INSTALL_EXE%:Zone.Identifier" 2>nul

if exist "%CA_CER%" (
    echo Registering trusted certificate...
    certutil -user -addstore Root "%CA_CER%" >nul
)

echo Registering oelink:// URL scheme...
reg add "HKCU\Software\Classes\oelink" /ve /d "URL:oelink Protocol" /f >nul
reg add "HKCU\Software\Classes\oelink" /v "URL Protocol" /d "" /f >nul
reg add "HKCU\Software\Classes\oelink" /v "FriendlyTypeName" /d "oelink" /f >nul
reg add "HKCU\Software\Classes\oelink\Application" /v "ApplicationName" /d "oelink" /f >nul
reg add "HKCU\Software\Classes\oelink\Application" /v "ApplicationDescription" /d "oelink" /f >nul
reg add "HKCU\Software\Classes\oelink\Application" /v "ApplicationIcon" /d "\"%INSTALL_EXE%\",0" /f >nul
reg add "HKCU\Software\Classes\oelink\shell\open\command" /ve /d "\"%INSTALL_EXE%\" \"%%1\"" /f >nul

echo.
echo Installation complete!  Launcher: %INSTALL_EXE%
echo.
echo ------------------------------------------------------------
echo  Test URLs (paste into Run dialog or browser address bar)
echo ------------------------------------------------------------
echo.
echo  [Chrome - Normal]
echo    oelink://chrome?LS1ob3N0LXJlc29sdmVyLXJ1bGVzPSJNQVAgZXhhbXBsZS5jb20gMTI3LjAuMC4xIiBodHRwczovL2V4YW1wbGUuY29t
echo.
echo  [Chrome - Incognito]
echo    oelink://chrome?LS1ob3N0LXJlc29sdmVyLXJ1bGVzPSJNQVAgZXhhbXBsZS5jb20gMTI3LjAuMC4xIiAtLWluY29nbml0byBodHRwczovL2V4YW1wbGUuY29t
echo ------------------------------------------------------------
pause
