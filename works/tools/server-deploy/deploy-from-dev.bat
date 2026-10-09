@echo off
setlocal
if not exist "%~dp0tools\deploy-server.ps1" (
  echo Deployment program is missing: tools\deploy-server.ps1
  pause
  exit /b 1
)
if not exist "%~dp0tools\server-deployment.psm1" (
  echo Deployment module is missing: tools\server-deployment.psm1
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0tools\deploy-server.ps1" -SourceRoot "%~dp0..\Cobblemon-Mods\develop-product\server" -TargetRoot "%~dp0."
