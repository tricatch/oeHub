@echo off
setlocal

set "OUT_DIR=%~dp0"
set "EXE=%OUT_DIR%oelink.exe"
set "PFX_PATH=%OUT_DIR%oelink_local_cert.pfx"
set "CONFIG=%OUT_DIR%00.code_sign.oelocal"

if not exist "%EXE%" (
    echo oelink.exe not found. Run 01.build_win_oelink.cmd first.
    pause
    exit /b 1
)

if not exist "%PFX_PATH%" (
    echo oelink_local_cert.pfx not found. Run 00.gen_cert.cmd first.
    pause
    exit /b 1
)

if not exist "%CONFIG%" (
    echo %CONFIG% not found. Run 00.gen_cert.cmd first to create and fill it in.
    pause
    exit /b 1
)

set "CERT_PWD="
set "SIGN_TOOL_PATH="
for /f "usebackq tokens=1,* delims=: " %%A in ("%CONFIG%") do (
    if "%%A"=="CERT_PWD" set "CERT_PWD=%%B"
    if "%%A"=="SIGN_TOOL_PATH" set "SIGN_TOOL_PATH=%%B"
)

set "SIGNTOOL=%SIGN_TOOL_PATH%\signtool.exe"

if not exist "%SIGNTOOL%" (
    echo signtool.exe not found at "%SIGNTOOL%". Check SIGN_TOOL_PATH in %CONFIG%.
    pause
    exit /b 1
)

"%SIGNTOOL%" sign /f "%PFX_PATH%" /p "%CERT_PWD%" /fd sha256 /tr "http://timestamp.digicert.com" /td sha256 "%EXE%"

echo.
echo Done. Continue to 03.package_win_oelink.
pause
