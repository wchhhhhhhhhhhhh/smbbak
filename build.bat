@echo off
REM ============================================================
REM 快捷构建脚本（Windows）
REM 依赖：JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）
REM 用法：build.bat [debug|release|all]
REM 输出：app\build\outputs\apk\
REM ============================================================
setlocal

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.11"
set "GRADLE_USER_HOME=%CD%\.gradle-home"
set "ANDROID_USER_HOME=%CD%\.android"
set "ANDROID_HOME=G:\dxhc\android-sdk"
set "TEMP=%CD%\tmp"
set "TMP=%CD%\tmp"

set "GRADLE=gradle"
where gradle >nul 2>nul || set "GRADLE=G:\dxhc\tools\gradle-8.9\bin\gradle.bat"

if "%TARGET%"=="debug" (
    call "%GRADLE%" --no-daemon --console=plain -p "%~dp0" assembleDebug
) else if "%TARGET%"=="release" (
    call "%GRADLE%" --no-daemon --console=plain -p "%~dp0" assembleRelease
) else (
    call "%GRADLE%" --no-daemon --console=plain -p "%~dp0" assembleDebug assembleRelease
)

echo.
echo 输出目录：%~dp0app\build\outputs\apk\
endlocal
