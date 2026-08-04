@echo off
setlocal

set "CER_PATH=%~dp0oelink_local_cert.cer"

if not exist "%CER_PATH%" (
    echo oelink_local_cert.cer not found. Run 00.gen_cert.cmd first.
    pause
    exit /b 1
)

certutil -user -addstore -f "Root" "%CER_PATH%"

echo.
echo Done. oelink_local_cert is now trusted as a Root CA on this machine.
pause
