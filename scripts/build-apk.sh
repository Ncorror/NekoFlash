#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MODE="${1:-all}"
case "$MODE" in
  debug|release|all) ;;
  *) echo "Usage: $0 [debug|release|all]" >&2; exit 2 ;;
esac

if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing." >&2
  exit 1
fi

chmod +x gradlew

run_gradle_task() {
  ./gradlew "$1" --no-daemon --stacktrace --warning-mode all --console=plain
}

case "$MODE" in
  debug)
    run_gradle_task :app:lintDebug
    run_gradle_task :app:assembleDebug
    ;;
  release)
    run_gradle_task :app:lintRelease
    run_gradle_task :app:assembleRelease
    ;;
  all)
    run_gradle_task :app:lintDebug
    run_gradle_task :app:assembleDebug
    run_gradle_task :app:assembleRelease
    ;;
esac

VERSION_NAME="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle | head -1)"
mkdir -p forum-build
rm -f forum-build/*.apk forum-build/checksums-sha256.txt

if [[ "$MODE" == "debug" || "$MODE" == "all" ]]; then
  DEBUG_APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1 || true)"
  [[ -n "$DEBUG_APK" && -f "$DEBUG_APK" ]] || { echo "ERROR: Debug APK not found." >&2; exit 1; }
  cp "$DEBUG_APK" "forum-build/NekoFlash-${VERSION_NAME}-debug.apk"
fi

if [[ "$MODE" == "release" || "$MODE" == "all" ]]; then
  RELEASE_APK="$(find app/build/outputs/apk/release -name '*.apk' | head -1 || true)"
  [[ -n "$RELEASE_APK" && -f "$RELEASE_APK" ]] || { echo "ERROR: Release APK not found." >&2; exit 1; }

  SIGNED=false
  if [[ -n "${NEKOFLASH_KEYSTORE_PATH:-}" && -f "${NEKOFLASH_KEYSTORE_PATH}" && \
        -n "${NEKOFLASH_STORE_PASSWORD:-}" && -n "${NEKOFLASH_KEY_ALIAS:-}" && \
        -n "${NEKOFLASH_KEY_PASSWORD:-}" ]]; then
    SIGNED=true
  fi

  if [[ "$SIGNED" == true ]]; then
    cp "$RELEASE_APK" "forum-build/NekoFlash-${VERSION_NAME}-release-signed.apk"
  else
    cp "$RELEASE_APK" "forum-build/NekoFlash-${VERSION_NAME}-release-unsigned.apk"
  fi
fi

(
  cd forum-build
  sha256sum *.apk > checksums-sha256.txt
  ls -lh *.apk checksums-sha256.txt
  cat checksums-sha256.txt
)

printf '\nBuild artifacts: %s/forum-build\n' "$ROOT_DIR"
