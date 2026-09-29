@echo off
chcp 65001 >nul
rem Чтобы включить пароль: python "%~dp0server.py" --port 5000 --password МОЙ_ПАРОЛЬ
python "%~dp0server.py" --port 5000
pause
