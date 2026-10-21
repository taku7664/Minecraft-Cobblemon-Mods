@echo off
rem Serves "play against the boss" for a phone: the play server on this PC plus a cloudflared tunnel. Leave it open.
setlocal
chcp 65001 >nul
set PYTHONUTF8=1
python "%~dp0arena.py" play %*
