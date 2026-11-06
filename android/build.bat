@echo off
cd /d "%~dp0"
echo Building APK...
py -3 build.py %*
if errorlevel 1 (
    echo retry with python...
    python build.py %*
)
echo.
pause
