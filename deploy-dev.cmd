@echo off
setlocal
chcp 65001 >nul

where pwsh.exe >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\dev-deploy.ps1" %*
) else (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\dev-deploy.ps1" %*
)

set "DEPLOY_EXIT=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %DEPLOY_EXIT%
