@echo off
chcp 65001 >nul
python "%~dp0server.py" --port 5000
pause
