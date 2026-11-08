@echo off
REM Publish signed release APK to yourname/discord-evs-generator-releases
REM Usage: publish.bat 5.0.0
REM Optional: publish.bat 5.0.0 path\to\app-release.apk

set VERSION=%~1
if "%VERSION%"=="" (
  echo Usage: publish.bat 5.0.0 [path\to\apk]
  exit /b 1
)
set APK=%~2
if "%APK%"=="" set APK=..\..\app\build\outputs\apk\release\app-release.apk

python "%~dp0publish_release.py" --apk "%APK%" --version "%VERSION%"
