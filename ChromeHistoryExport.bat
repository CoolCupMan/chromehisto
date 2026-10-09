@echo off
rem Double-click to export all local Chrome/Edge/Brave history to reports\
cd /d "%~dp0"
where py >nul 2>nul && (py -3 -m chromehisto %*) || (python -m chromehisto %*)
pause
