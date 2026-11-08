@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install_cmake_3.25.0.ps1"
if %errorlevel% neq 0 (
    echo.
    echo Installation failed. Press any key to exit.
    pause >nul
)
endlocal
