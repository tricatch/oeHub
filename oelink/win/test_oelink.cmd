@echo off
echo.
echo ============================================================
echo  oelink:// scheme test
echo ============================================================
echo.
echo  [1] Chrome - Normal
echo  [2] Chrome - Incognito
echo.
set /p CHOICE="Select (1-2): "

if "%CHOICE%"=="1" powershell -NoProfile -Command "$q=[char]34; $u='%USERPROFILE%\oe-chrome'; $a='--oelink --host-resolver-rules='+$q+'MAP example.com 127.0.0.1'+$q+' --user-data-dir='+$q+$u+$q+' https://example.com'; $b=([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($a))-replace'\+','-'-replace'/','_'-replace'=+$',''); Start-Process ('oelink://chrome?'+$b)"
if "%CHOICE%"=="2" powershell -NoProfile -Command "$q=[char]34; $u='%USERPROFILE%\oe-chrome'; $a='--oelink --host-resolver-rules='+$q+'MAP example.com 127.0.0.1'+$q+' --incognito --user-data-dir='+$q+$u+$q+' https://example.com'; $b=([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($a))-replace'\+','-'-replace'/','_'-replace'=+$',''); Start-Process ('oelink://chrome?'+$b)"
