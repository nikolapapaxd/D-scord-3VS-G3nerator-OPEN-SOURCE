@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0diagnose_cmake.ps1"

echo.
echo Press any key to exit.
pause >nul
endlocal
