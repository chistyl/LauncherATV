@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo Dangbei Launcher V5.4.0 - APK Build
echo ==========================================

where gradle >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Gradle was not found in PATH.
  echo Install Gradle 8.9 and configure ANDROID_HOME / Android SDK first.
  echo Alternatively, upload this project to GitHub and run the Actions workflow.
  pause
  exit /b 1
)

if "%ANDROID_HOME%"=="" if "%ANDROID_SDK_ROOT%"=="" (
  echo [ERROR] ANDROID_HOME or ANDROID_SDK_ROOT is not configured.
  pause
  exit /b 1
)

call gradle --no-daemon assembleDebug
if errorlevel 1 (
  echo [ERROR] APK build failed. Review the Gradle error above.
  pause
  exit /b 1
)

if not exist "output" mkdir "output"
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "output\DangbeiLauncher-5.4.0-debug.apk" >nul
if errorlevel 1 (
  echo [ERROR] APK output file was not found.
  pause
  exit /b 1
)

echo.
echo [SUCCESS] APK created:
echo %~dp0output\DangbeiLauncher-5.4.0-debug.apk
pause
