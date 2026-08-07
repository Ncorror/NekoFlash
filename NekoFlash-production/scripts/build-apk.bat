@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0\.."

set "MODE=%~1"
if "%MODE%"=="" set "MODE=all"
if /I not "%MODE%"=="debug" if /I not "%MODE%"=="release" if /I not "%MODE%"=="all" (
  echo Usage: scripts\build-apk.bat [debug^|release^|all]
  exit /b 2
)

REM Permanent semantic guards only (version-stamped check-v5*.py tripwires removed in cleanup).

if not exist gradle\wrapper\gradle-wrapper.jar (
  echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing.
  exit /b 1
)

if /I "%MODE%"=="debug" (
  call gradlew.bat :app:lintDebug --no-daemon --stacktrace --console=plain || exit /b 1
  call gradlew.bat :app:assembleDebug --no-daemon --stacktrace --console=plain || exit /b 1
) else if /I "%MODE%"=="release" (
  call gradlew.bat :app:lintRelease --no-daemon --stacktrace --console=plain || exit /b 1
  call gradlew.bat :app:assembleRelease --no-daemon --stacktrace --console=plain || exit /b 1
) else (
  call gradlew.bat :app:lintDebug --no-daemon --stacktrace --console=plain || exit /b 1
  call gradlew.bat :app:assembleDebug --no-daemon --stacktrace --console=plain || exit /b 1
  call gradlew.bat :app:assembleRelease --no-daemon --stacktrace --console=plain || exit /b 1
)

echo Build completed. APK files are under app\build\outputs\apk\
exit /b 0
