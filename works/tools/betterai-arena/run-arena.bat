@echo off
rem Better AI self-play: run-arena.bat BATTLES [--threads N] [--seed S] [--jar PATH] [--commit SHA] [--note TEXT]
rem Plays the battles, keeps the record files under the runs folder of arena.properties and rebuilds the viewer.
setlocal
chcp 65001 >nul
set PYTHONUTF8=1
if "%~1"=="" (
  echo usage: run-arena.bat BATTLES [--threads N] [--seed S] [--jar PATH] [--commit SHA] [--note TEXT]
  exit /b 2
)
python "%~dp0arena.py" run %*
