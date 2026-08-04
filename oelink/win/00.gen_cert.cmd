@echo off
setlocal

set "OUT_DIR=%~dp0"
set "CONFIG=%OUT_DIR%00.code_sign.oelocal"

if not exist "%CONFIG%" (
    echo CERT_PWD: > "%CONFIG%"
    echo SIGN_TOOL_PATH: >> "%CONFIG%"
    echo Created %CONFIG%
    echo Fill in CERT_PWD and SIGN_TOOL_PATH ^(the folder containing signtool.exe^) in that file, then run this again.
    echo This file is local-only and is never committed to git ^(see .gitignore: *.oelocal^).
    pause
    exit /b 1
)

set "CERT_PWD="
for /f "usebackq tokens=1,* delims=: " %%A in ("%CONFIG%") do (
    if "%%A"=="CERT_PWD" set "CERT_PWD=%%B"
)

if "%CERT_PWD%"=="" (
    echo CERT_PWD is empty in %CONFIG%. Fill it in and run this again.
    pause
    exit /b 1
)

powershell -ExecutionPolicy Bypass -File "%OUT_DIR%_internal\00.gen_cert.ps1" -CertPassword "%CERT_PWD%"

echo.
echo Done. Use oelink_local_cert.pfx with 02.sign_win_oelink.cmd to sign oelink.exe.
pause
