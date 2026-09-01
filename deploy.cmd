@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-deploy.ps1" %*
exit /b %ERRORLEVEL%
