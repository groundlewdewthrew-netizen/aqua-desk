@echo off
title AquaDesk PC Agent
cd /d "%~dp0"
echo Installing AquaDesk helper libraries (first run only)...
python -m pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo Install failed. Copy the red text above and send it to Claude.
  pause
  exit /b 1
)
echo.
python aquadesk_agent.py
echo.
echo AquaDesk agent stopped.
pause
