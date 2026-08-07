#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MODE="${1:-all}"
EXPECTED_RELEASE_CERT_SHA256="b122576dcaa5b1ceebd977545314bd515432f9e3fa50733fa8153a1e48a6c19e"

case "$MODE" in
  debug|release|all) ;;
  *) echo "Usage: $0 [debug|release|all]" >&2; exit 2 ;;
esac

release_signing_ready() {
  [[ -n "${NEKOFLASH_KEYSTORE_PATH:-}" && -f "${NEKOFLASH_KEYSTORE_PATH}" && \
     -n "${NEKOFLASH_STORE_PASSWORD:-}" && -n "${NEKOFLASH_KEY_ALIAS:-}" && \
     -n "${NEKOFLASH_KEY_PASSWORD:-}" ]]
}

normalize_sha256() {
  tr -d ':' | tr '[:upper:]' '[:lower:]'
}

verify_release_keystore_certificate() {
  command -v keytool >/dev/null 2>&1 || {
    echo "ERROR: keytool is required to verify the release certificate." >&2
    exit 1
  }

  local actual
  actual="$(
    keytool -list -v \
      -keystore "$NEKOFLASH_KEYSTORE_PATH" \
      -storepass "$NEKOFLASH_STORE_PASSWORD" \
      -alias "$NEKOFLASH_KEY_ALIAS" 2>/dev/null | \
    sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' | \
    head -n 1 | normalize_sha256
  )"

  [[ -n "$actual" ]] || {
    echo "ERROR: could not read SHA-256 certificate fingerprint from the release keystore." >&2
    exit 1
  }

  echo "Expected release certificate: $EXPECTED_RELEASE_CERT_SHA256"
  echo "Actual release certificate:   $actual"

  [[ "$actual" == "$EXPECTED_RELEASE_CERT_SHA256" ]] || {
    echo "ERROR: release keystore certificate does not match the permanent NekoFlash release key." >&2
    exit 1
  }
}

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  local sdk_root candidate
  for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
    [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
    candidate="$(find "$sdk_root/build-tools" -type f -name apksigner -print | sort -V | tail -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

verify_release_apk_certificate() {
  local apk="$1"
  local apksigner signature_file actual

  apksigner="$(find_apksigner)" || {
    echo "ERROR: apksigner not found; signed release verification is mandatory." >&2
    exit 1
  }

  signature_file="${TMPDIR:-/tmp}/nekoflash-apk-signature-$$.txt"
  trap 'rm -f "${signature_file:-}"' RETURN

  "$apksigner" verify --verbose --print-certs "$apk" | tee "$signature_file"

  actual="$(
    sed -nE \
      's/^.*certificate SHA-256 digest:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' \
      "$signature_file" | \
    head -n 1 | normalize_sha256
  )"

  [[ -n "$actual" ]] || {
    echo "ERROR: could not read signer certificate SHA-256 from APK." >&2
    exit 1
  }

  echo "Expected APK certificate: $EXPECTED_RELEASE_CERT_SHA256"
  echo "Actual APK certificate:   $actual"

  [[ "$actual" == "$EXPECTED_RELEASE_CERT_SHA256" ]] || {
    echo "ERROR: APK is not signed with the permanent NekoFlash release key." >&2
    exit 1
  }
}

if [[ "$MODE" == "release" || "$MODE" == "all" ]]; then
  if ! release_signing_ready; then
    echo "ERROR: release/all build requires the permanent NekoFlash signing key." >&2
    echo "Set NEKOFLASH_KEYSTORE_PATH, NEKOFLASH_STORE_PASSWORD, NEKOFLASH_KEY_ALIAS and NEKOFLASH_KEY_PASSWORD, or run: $0 debug" >&2
    exit 1
  fi
  verify_release_keystore_certificate
fi

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
  verify_release_apk_certificate "$RELEASE_APK"
  cp "$RELEASE_APK" "forum-build/NekoFlash-${VERSION_NAME}-release-signed.apk"
fi

(
  cd forum-build
  sha256sum *.apk > checksums-sha256.txt
  ls -lh *.apk checksums-sha256.txt
  cat checksums-sha256.txt
)

printf '\nBuild artifacts: %s/forum-build\n' "$ROOT_DIR"
