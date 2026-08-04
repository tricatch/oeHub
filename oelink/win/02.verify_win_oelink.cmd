@echo off
setlocal

set "OUT_DIR=%~dp0"
set "EXE=%OUT_DIR%oelink.exe"
set "CONFIG=%OUT_DIR%00.code_sign.oelocal"

if not exist "%EXE%" (
    echo oelink.exe not found. Run 01.build_win_oelink.cmd first.
    pause
    exit /b 1
)

if not exist "%CONFIG%" (
    echo %CONFIG% not found. Run 00.gen_cert.cmd first to create and fill it in.
    pause
    exit /b 1
)

set "SIGN_TOOL_PATH="
for /f "usebackq tokens=1,* delims=: " %%A in ("%CONFIG%") do (
    if "%%A"=="SIGN_TOOL_PATH" set "SIGN_TOOL_PATH=%%B"
)

set "SIGNTOOL=%SIGN_TOOL_PATH%\signtool.exe"

if not exist "%SIGNTOOL%" (
    echo signtool.exe not found at "%SIGNTOOL%". Check SIGN_TOOL_PATH in %CONFIG%.
    pause
    exit /b 1
)

"%SIGNTOOL%" verify /pa "%EXE%"

echo.
echo If verified, continue to 03.package_win_oelink.
pause
