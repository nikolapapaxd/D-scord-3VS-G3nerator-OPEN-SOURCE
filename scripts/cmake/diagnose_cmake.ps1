# General Android build diagnostic for DiscordGen.
# (CMake/Ninja/NDK are no longer required — the native code was replaced with Kotlin.)

param(
    [string]$SdkDir = "C:\Android"
)

$ErrorActionPreference = "Continue"

function Write-Header($text) {
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host $text -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
}

function Write-FileContent($path) {
    if (Test-Path $path) {
        Write-Host "`n--- $path ---" -ForegroundColor Green
        Get-Content $path | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "`n--- $path --- NOT FOUND" -ForegroundColor Red
    }
}

Write-Header "1. LOCAL.PROPERTIES"
Write-FileContent "local.properties"

Write-Header "2. JAVA / JDK"
if ($env:JAVA_HOME) {
    Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $javaExe) {
        & $javaExe -version 2>&1 | Write-Host
    }
} else {
    Write-Host "JAVA_HOME is not set" -ForegroundColor Red
}
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) {
    Write-Host "java.exe in PATH: $($pathJava.Source)" -ForegroundColor Green
}

Write-Header "3. ANDROID SDK"
if (Test-Path $SdkDir) {
    Write-Host "SDK root: $SdkDir" -ForegroundColor Green
    $platforms = Join-Path $SdkDir "platforms"
    if (Test-Path $platforms) {
        Write-Host "Installed platforms:" -ForegroundColor Green
        Get-ChildItem -Path $platforms -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
    }
    $buildTools = Join-Path $SdkDir "build-tools"
    if (Test-Path $buildTools) {
        Write-Host "Installed build-tools:" -ForegroundColor Green
        Get-ChildItem -Path $buildTools -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
    }
} else {
    Write-Host "SDK root NOT FOUND: $SdkDir" -ForegroundColor Red
}

Write-Header "DIAGNOSTIC COMPLETE"
Write-Host "This project no longer uses CMake, Ninja, or the NDK." -ForegroundColor Green
