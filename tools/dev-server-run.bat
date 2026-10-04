@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" -version >nul 2>&1
if errorlevel 1 (
    echo Java 21 not found. Install Java 21 and add it to PATH, or set JAVA_HOME.
    pause
    goto end
)

:run_server
echo [run] Checking server startup settings...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\server-startup-hooks.ps1" -ServerRoot "%~dp0."
if errorlevel 1 (
    echo Server startup hooks failed. The server was not started.
    pause
    goto end
)
echo [run] Starting server with "%JAVA_CMD%"...
"%JAVA_CMD%" -Xms2G -Xmx4G -jar fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar --port 25566 nogui
set "server_exit_code=%errorlevel%"

echo.
if not "%server_exit_code%"=="0" echo 서버가 오류 코드 %server_exit_code%^(으^)로 종료되었습니다.
choice /C YN /N /M "서버를 다시 키겠습니까? Y/N: "
if errorlevel 2 goto end
goto run_server

:end
endlocal
