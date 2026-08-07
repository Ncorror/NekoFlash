#!/data/data/com.termux/files/usr/bin/bash
# Export two complementary artifacts without Git metadata, local SDK paths,
# signing material, APKs or generated build outputs:
#   1. recovery ZIP: source + chat continuity + restore instructions;
#   2. reviewed-source ZIP: bootstrap-compatible input for the publisher that
#      already exists on protected main before this update is merged.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="${1:-$HOME/storage/downloads}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
BUNDLE_NAME="NekoFlash-recovery-$STAMP"
SOURCE_NAME="NekoFlash-reviewed-source-$STAMP"
OUTPUT_ZIP="$OUTPUT_DIR/$BUNDLE_NAME.zip"
OUTPUT_SHA="$OUTPUT_ZIP.sha256"
OUTPUT_SOURCE_ZIP="$OUTPUT_DIR/$SOURCE_NAME.zip"
OUTPUT_SOURCE_SHA="$OUTPUT_SOURCE_ZIP.sha256"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for cmd in git rsync zip sha256sum mktemp date find; do
  command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
done

[ -f "$ROOT/PROJECT_MASTER_TRACKER.md" ] || fail "not a NekoFlash source tree: $ROOT"
[ -s "$ROOT/gradle/wrapper/gradle-wrapper.jar" ] || fail "Gradle Wrapper JAR is missing"
mkdir -p "$OUTPUT_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
BUNDLE_ROOT="$TMP_DIR/$BUNDLE_NAME"
RECOVERY_SOURCE_ROOT="$BUNDLE_ROOT/SOURCE"
PUBLISH_SOURCE_ROOT="$TMP_DIR/$SOURCE_NAME"
mkdir -p "$RECOVERY_SOURCE_ROOT" "$PUBLISH_SOURCE_ROOT" "$BUNDLE_ROOT/CHAT_CONTEXT"

rsync -a \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='.idea/' \
  --exclude='.cxx/' \
  --exclude='build/' \
  --exclude='*/build/' \
  --exclude='local.properties' \
  --exclude='keystore.properties' \
  --exclude='*.jks' \
  --exclude='*.keystore' \
  --exclude='*.apk' \
  --exclude='*.aab' \
  --exclude='*.so' \
  --exclude='*.o' \
  --exclude='*.class' \
  --exclude='*.jar.tmp.*' \
  "$ROOT/" "$RECOVERY_SOURCE_ROOT/"

# The companion source ZIP intentionally has one wrapper directory and the
# project root directly below it. This is accepted by both the old protected-
# main publisher and the updated nested-bundle-aware publisher.
rsync -a "$RECOVERY_SOURCE_ROOT/" "$PUBLISH_SOURCE_ROOT/"

bash "$ROOT/scripts/export-chat-context.sh" \
  "$BUNDLE_ROOT/CHAT_CONTEXT/NekoFlash-chat-context.txt"

{
  printf 'NEKOFLASH RECOVERY BUNDLE\n'
  printf 'Generated: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
  printf 'Repository: Ncorror/NekoFlash\n'
  if [ -d "$ROOT/.git" ]; then
    printf 'Local branch: %s\n' "$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
    printf 'Local commit: %s\n' "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
    printf 'Working tree:\n'
    git -C "$ROOT" status --short || true
  else
    printf 'Git metadata: absent\n'
  fi
  printf '\nSOURCE contains the complete reviewed project tree.\n'
  printf 'CHAT_CONTEXT contains the compact new-chat handoff.\n'
  printf 'Use the separately generated NekoFlash-reviewed-source ZIP for the first protected-main publication.\n'
  printf 'Each outer ZIP has a sibling .sha256 file with a relative filename.\n'
} > "$BUNDLE_ROOT/RECOVERY_MANIFEST.txt"

cat > "$BUNDLE_ROOT/RESTORE_AND_PUBLISH.txt" <<'COMMANDS'
RECOVERY PURPOSE
1. Upload this recovery ZIP to a new chat.
2. Ask the new chat to read CHAT_CONTEXT/NekoFlash-chat-context.txt first.
3. SOURCE/ contains the complete code if the original workspace is lost.

NEXT GITHUB PUBLICATION
Use the separately generated NekoFlash-reviewed-source-*.zip. Publish only from
clean protected main through a short-lived Pull Request branch.

cd "$HOME/NekoFlash"
git fetch origin --prune --tags
git switch main
git pull --ff-only origin main
BASE_MAIN_SHA="3f416744347ec5a44cc7d64668760b0778bf473e"
git merge-base --is-ancestor "$BASE_MAIN_SHA" origin/main || {
  echo "STOP: confirmed Batch 1 baseline is not present in origin/main"
  exit 1
}
test -z "$(git status --porcelain)" || {
  git status --short
  echo "STOP: main working tree is not clean"
  exit 1
}

SOURCE_ZIP="$HOME/storage/downloads/THIS_REVIEWED_SOURCE.zip"
SOURCE_SHA256_FILE="$SOURCE_ZIP.sha256"
(
  cd "$(dirname "$SOURCE_ZIP")"
  sha256sum -c "$(basename "$SOURCE_SHA256_FILE")"
)
EXPECTED_SHA="$(awk '{print $1}' "$SOURCE_SHA256_FILE")"

NEKOFLASH_PR_BRANCH="termux/audit-native-usbfs-raii-batch2" \
bash scripts/termux-publish.sh \
  --source-zip "$SOURCE_ZIP" \
  --sha256 "$EXPECTED_SHA" \
  "Harden native USBFS resource ownership with RAII"

Use the PR_URL printed by the publisher:

gh pr checks --repo Ncorror/NekoFlash --watch PR_URL
gh pr merge --repo Ncorror/NekoFlash --merge --delete-branch PR_URL

cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main
git fetch origin --prune
bash scripts/termux-ci.sh

FALLBACK WHEN ONLY THE RECOVERY ZIP SURVIVES
Repack its inner SOURCE directory into a plain source ZIP before invoking the
old publisher:

RECOVERY_ZIP="$HOME/storage/downloads/THIS_RECOVERY_BUNDLE.zip"
TMP_RECOVERY="$(mktemp -d)"
unzip -q "$RECOVERY_ZIP" -d "$TMP_RECOVERY"
SOURCE_DIR="$(find "$TMP_RECOVERY" -type f -name PROJECT_MASTER_TRACKER.md -print -quit | xargs dirname)"
(
  cd "$(dirname "$SOURCE_DIR")"
  zip -qr "$HOME/storage/downloads/NekoFlash-reviewed-source-repacked.zip" "$(basename "$SOURCE_DIR")"
)
rm -rf "$TMP_RECOVERY"

Never force-push, push directly to protected main, replace .git, or copy signing
files from an archive.
COMMANDS

(
  cd "$TMP_DIR"
  zip -qr "$OUTPUT_ZIP" "$BUNDLE_NAME"
  zip -qr "$OUTPUT_SOURCE_ZIP" "$SOURCE_NAME"
)
(
  cd "$OUTPUT_DIR"
  sha256sum "$(basename "$OUTPUT_ZIP")" > "$(basename "$OUTPUT_SHA")"
  sha256sum "$(basename "$OUTPUT_SOURCE_ZIP")" > "$(basename "$OUTPUT_SOURCE_SHA")"
)

printf 'Recovery bundle:    %s\n' "$OUTPUT_ZIP"
printf 'Recovery SHA file:  %s\n' "$OUTPUT_SHA"
printf 'Reviewed source:    %s\n' "$OUTPUT_SOURCE_ZIP"
printf 'Source SHA file:    %s\n' "$OUTPUT_SOURCE_SHA"
printf 'Upload the recovery ZIP to a new chat; publish the reviewed-source ZIP through Termux.\n'
