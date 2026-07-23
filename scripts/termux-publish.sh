#!/data/data/com.termux/files/usr/bin/bash
# Import an optional reviewed source ZIP, create a commit, and push the feature branch.
# This script intentionally does not build the app, run tests, or launch GitHub Actions.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ZIP=""
EXPECTED_SHA256=""
COMMIT_MESSAGE="NekoFlash reviewed update"
MESSAGE_SET=0

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/termux-publish.sh "Commit message"
  bash scripts/termux-publish.sh --source-zip ZIP [--sha256 HASH] "Commit message"

The script only imports reviewed sources (when --source-zip is supplied),
creates a normal commit, and pushes the current feature branch without force.
It does not run local tests, Gradle, or GitHub Actions.
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --source-zip)
      [ "$#" -ge 2 ] || fail "--source-zip requires a path"
      SOURCE_ZIP="$2"
      shift 2
      ;;
    --sha256)
      [ "$#" -ge 2 ] || fail "--sha256 requires a hash"
      EXPECTED_SHA256="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      fail "unknown option: $1"
      ;;
    *)
      [ "$MESSAGE_SET" -eq 0 ] || fail "commit message must be one quoted argument"
      COMMIT_MESSAGE="$1"
      MESSAGE_SET=1
      shift
      ;;
  esac
done

for cmd in git gh awk sed wc tr dirname mktemp; do
  command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
done

if [ -n "$SOURCE_ZIP" ]; then
  for cmd in unzip rsync find sha256sum; do
    command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
  done
  [ -f "$SOURCE_ZIP" ] || fail "source ZIP not found: $SOURCE_ZIP"
fi

cd "$ROOT"
[ -d .git ] || fail "not a Git repository: $ROOT"

CURRENT_BRANCH="$(git branch --show-current)"
[ -n "$CURRENT_BRANCH" ] || fail "detached HEAD is not supported"
TARGET_BRANCH="${NEKOFLASH_BRANCH:-$CURRENT_BRANCH}"
[ "$CURRENT_BRANCH" = "$TARGET_BRANCH" ] || fail "checkout $TARGET_BRANCH before publishing"
[ "$TARGET_BRANCH" != "main" ] || fail "main branch is protected from direct publishing"
[ -n "$(git config user.name || true)" ] || fail "configure git user.name"
[ -n "$(git config user.email || true)" ] || fail "configure git user.email"
gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI is not authenticated"

git fetch origin --prune

if [ -n "$SOURCE_ZIP" ]; then
  [ -z "$(git status --porcelain)" ] || {
    git status --short
    fail "working tree must be clean before importing a source ZIP"
  }

  if git show-ref --verify --quiet "refs/remotes/origin/$TARGET_BRANCH"; then
    git pull --ff-only origin "$TARGET_BRANCH"
  fi

  if [ -n "$EXPECTED_SHA256" ]; then
    ACTUAL_SHA256="$(sha256sum "$SOURCE_ZIP" | awk '{print $1}')"
    [ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || fail "source ZIP SHA-256 mismatch"
  fi

  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  unzip -q "$SOURCE_ZIP" -d "$TMP_DIR/unpacked"

  if [ -f "$TMP_DIR/unpacked/PROJECT_MASTER_TRACKER.md" ]; then
    SOURCE_ROOT="$TMP_DIR/unpacked"
  else
    TRACKER_PATHS="$(find "$TMP_DIR/unpacked" -mindepth 2 -maxdepth 2 -type f -name PROJECT_MASTER_TRACKER.md -print)"
    TRACKER_COUNT="$(printf '%s\n' "$TRACKER_PATHS" | sed '/^$/d' | wc -l | tr -d ' ')"
    [ "$TRACKER_COUNT" = "1" ] || fail "source ZIP must contain exactly one NekoFlash project root"
    SOURCE_ROOT="$(dirname "$TRACKER_PATHS")"
  fi

  [ -f "$SOURCE_ROOT/scripts/termux-publish.sh" ] || fail "invalid NekoFlash source ZIP"

  rsync -a --delete \
    --exclude='.git/' \
    --exclude='local.properties' \
    --exclude='keystore.properties' \
    --exclude='*.jks' \
    --exclude='*.keystore' \
    "$SOURCE_ROOT/" "$ROOT/"

  printf 'Imported reviewed source ZIP: %s\n' "$SOURCE_ZIP"
else
  if git show-ref --verify --quiet "refs/remotes/origin/$TARGET_BRANCH"; then
    git merge-base --is-ancestor "origin/$TARGET_BRANCH" HEAD || \
      fail "remote branch contains commits missing locally; pull before publishing"
  fi
fi

printf '\nChanges to publish:\n'
git status --short

git add -A
if ! git diff --cached --quiet; then
  git commit -m "$COMMIT_MESSAGE"
else
  printf 'No new source changes to commit. Checking whether push is still needed.\n'
fi

git push -u origin "$TARGET_BRANCH"

LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git ls-remote origin "refs/heads/$TARGET_BRANCH" | awk '{print $1}')"
printf 'BRANCH    =%s\n' "$TARGET_BRANCH"
printf 'LOCAL_SHA =%s\n' "$LOCAL_SHA"
printf 'REMOTE_SHA=%s\n' "$REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] || fail "remote SHA does not match local HEAD"

printf 'PUSH COMPLETED. No local build or CI was started.\n'
printf 'Run CI separately only when needed: NEKOFLASH_BRANCH=%s bash scripts/termux-ci.sh\n' "$TARGET_BRANCH"
