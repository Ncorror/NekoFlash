@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0\.."

set "MODE=%~1"
if "%MODE%"=="" set "MODE=all"
if /I not "%MODE%"=="debug" if /I not "%MODE%"=="release" if /I not "%MODE%"=="all" (
  echo Usage: scripts\build-apk.bat [debug^|release^|all]
  exit /b 2
)

if not "%NEKOFLASH_RELEASE_STORE_FILE%"=="" (
  set "NEKOFLASH_SIGNING_STORE_FILE=%NEKOFLASH_RELEASE_STORE_FILE%"
) else (
  set "NEKOFLASH_SIGNING_STORE_FILE=%NEKOFLASH_KEYSTORE_PATH%"
)
if not "%NEKOFLASH_RELEASE_STORE_PASSWORD%"=="" (
  set "NEKOFLASH_SIGNING_STORE_PASSWORD=%NEKOFLASH_RELEASE_STORE_PASSWORD%"
) else (
  set "NEKOFLASH_SIGNING_STORE_PASSWORD=%NEKOFLASH_STORE_PASSWORD%"
)
if not "%NEKOFLASH_RELEASE_KEY_ALIAS%"=="" (
  set "NEKOFLASH_SIGNING_KEY_ALIAS=%NEKOFLASH_RELEASE_KEY_ALIAS%"
) else (
  set "NEKOFLASH_SIGNING_KEY_ALIAS=%NEKOFLASH_KEY_ALIAS%"
)
if not "%NEKOFLASH_RELEASE_KEY_PASSWORD%"=="" (
  set "NEKOFLASH_SIGNING_KEY_PASSWORD=%NEKOFLASH_RELEASE_KEY_PASSWORD%"
) else (
  set "NEKOFLASH_SIGNING_KEY_PASSWORD=%NEKOFLASH_KEY_PASSWORD%"
)
set "NEKOFLASH_RELEASE_STORE_FILE=%NEKOFLASH_SIGNING_STORE_FILE%"
set "NEKOFLASH_RELEASE_STORE_PASSWORD=%NEKOFLASH_SIGNING_STORE_PASSWORD%"
set "NEKOFLASH_RELEASE_KEY_ALIAS=%NEKOFLASH_SIGNING_KEY_ALIAS%"
set "NEKOFLASH_RELEASE_KEY_PASSWORD=%NEKOFLASH_SIGNING_KEY_PASSWORD%"

if /I "%MODE%"=="release" goto CHECK_RELEASE_SIGNING
if /I "%MODE%"=="all" goto CHECK_RELEASE_SIGNING
goto AFTER_RELEASE_SIGNING_CHECK

:CHECK_RELEASE_SIGNING
if "%NEKOFLASH_SIGNING_STORE_FILE%"=="" (
  echo ERROR: release/all build requires NEKOFLASH_RELEASE_STORE_FILE.
  echo Run scripts\build-apk.bat debug for a debug-only build.
  exit /b 1
)
if not exist "%NEKOFLASH_SIGNING_STORE_FILE%" (
  echo ERROR: release keystore not found: %NEKOFLASH_SIGNING_STORE_FILE%
  exit /b 1
)
if "%NEKOFLASH_SIGNING_STORE_PASSWORD%"=="" (
  echo ERROR: release/all build requires NEKOFLASH_RELEASE_STORE_PASSWORD.
  exit /b 1
)
if "%NEKOFLASH_SIGNING_KEY_ALIAS%"=="" (
  echo ERROR: release/all build requires NEKOFLASH_RELEASE_KEY_ALIAS.
  exit /b 1
)
if "%NEKOFLASH_SIGNING_KEY_PASSWORD%"=="" (
  echo ERROR: release/all build requires NEKOFLASH_RELEASE_KEY_PASSWORD.
  exit /b 1
)
:AFTER_RELEASE_SIGNING_CHECK

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
