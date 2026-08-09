#!/data/data/com.termux/files/usr/bin/bash
# Import reviewed sources, create a short-lived PR branch, and open a Pull
# Request into protected main. This script never pushes directly to main.
# It intentionally does not build the app, run tests, or merge the PR.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${NEKOFLASH_REPO:-Ncorror/NekoFlash}"
TARGET_BRANCH="${NEKOFLASH_TARGET_BRANCH:-main}"
SOURCE_ZIP=""
EXPECTED_SHA256=""
COMMIT_MESSAGE="NekoFlash reviewed update"
MESSAGE_SET=0

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/termux-publish.sh "Commit message"
  bash scripts/termux-publish.sh --source-zip ZIP [--sha256 HASH] "Commit message"

Run from protected main. The script creates a short-lived termux/update-* branch,
imports reviewed sources when requested, commits, pushes that branch, and opens a
Pull Request into main. It does not run local tests, Gradle, GitHub Actions, or
merge the Pull Request.
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

for cmd in git gh awk sed wc tr dirname mktemp date; do
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
[ "$CURRENT_BRANCH" = "$TARGET_BRANCH" ] || \
  fail "checkout protected $TARGET_BRANCH before publishing (current: ${CURRENT_BRANCH:-detached})"
[ -n "$(git config user.name || true)" ] || fail "configure git user.name"
[ -n "$(git config user.email || true)" ] || fail "configure git user.email"
gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI is not authenticated"

git fetch origin --prune
LOCAL_MAIN_SHA="$(git rev-parse HEAD)"
REMOTE_MAIN_SHA="$(git rev-parse "origin/$TARGET_BRANCH")"
[ "$LOCAL_MAIN_SHA" = "$REMOTE_MAIN_SHA" ] || \
  fail "local $TARGET_BRANCH must exactly match origin/$TARGET_BRANCH before creating a PR branch"

if [ -n "$SOURCE_ZIP" ]; then
  [ -z "$(git status --porcelain)" ] || {
    git status --short
    fail "working tree must be clean before importing a source ZIP"
  }
  if [ -n "$EXPECTED_SHA256" ]; then
    ACTUAL_SHA256="$(sha256sum "$SOURCE_ZIP" | awk '{print $1}')"
    [ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || fail "source ZIP SHA-256 mismatch"
  fi
fi

WORK_BRANCH="${NEKOFLASH_PR_BRANCH:-termux/update-$(date +%Y%m%d-%H%M%S)}"
if git show-ref --verify --quiet "refs/heads/$WORK_BRANCH" || \
   git show-ref --verify --quiet "refs/remotes/origin/$WORK_BRANCH"; then
  fail "work branch already exists: $WORK_BRANCH"
fi

git switch -c "$WORK_BRANCH"

if [ -n "$SOURCE_ZIP" ]; then
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  unzip -q "$SOURCE_ZIP" -d "$TMP_DIR/unpacked"

  PROJECT_FILES="$(find "$TMP_DIR/unpacked" -mindepth 2 -maxdepth 7 -type f -path '*/app/build.gradle' -print)"
  PROJECT_COUNT="$(printf '%s\n' "$PROJECT_FILES" | sed '/^$/d' | wc -l | tr -d ' ')"
  [ "$PROJECT_COUNT" = "1" ] || fail "source ZIP must contain exactly one NekoFlash project root"
  SOURCE_ROOT="$(dirname "$(dirname "$PROJECT_FILES")")"

  [ -f "$SOURCE_ROOT/settings.gradle" ] || fail "invalid NekoFlash source ZIP: settings.gradle missing"
  [ -f "$SOURCE_ROOT/scripts/termux-publish.sh" ] || fail "invalid NekoFlash source ZIP"
  [ -s "$SOURCE_ROOT/gradle/wrapper/gradle-wrapper.jar" ] || \
    fail "source ZIP is missing gradle/wrapper/gradle-wrapper.jar"
  [ -s "$SOURCE_ROOT/gradle/wrapper/gradle-wrapper.properties" ] || \
    fail "source ZIP is missing gradle/wrapper/gradle-wrapper.properties"

  rsync -a --delete \
    --exclude='.git/' \
    --exclude='local.properties' \
    --exclude='keystore.properties' \
    --exclude='*.jks' \
    --exclude='*.keystore' \
    "$SOURCE_ROOT/" "$ROOT/"

  printf 'Imported reviewed source ZIP: %s\n' "$SOURCE_ZIP"
fi

printf '\nChanges to publish through Pull Request:\n'
git status --short

[ -s gradle/wrapper/gradle-wrapper.jar ] || \
  fail "gradle/wrapper/gradle-wrapper.jar is missing after source import"
[ -s gradle/wrapper/gradle-wrapper.properties ] || \
  fail "gradle/wrapper/gradle-wrapper.properties is missing after source import"

git add -A
git add -f gradle/wrapper/gradle-wrapper.jar
git add -f gradle/wrapper/gradle-wrapper.properties gradlew gradlew.bat

git ls-files --error-unmatch gradle/wrapper/gradle-wrapper.jar >/dev/null 2>&1 || \
  fail "gradle-wrapper.jar was not staged/tracked"
git cat-file -e :gradle/wrapper/gradle-wrapper.jar 2>/dev/null || \
  fail "gradle-wrapper.jar is missing from the staged tree"

if git diff --cached --quiet; then
  git switch "$TARGET_BRANCH"
  git branch -D "$WORK_BRANCH"
  printf 'No new source changes to publish. main remains unchanged.\n'
  exit 0
fi

git commit -m "$COMMIT_MESSAGE"
LOCAL_SHA="$(git rev-parse HEAD)"
git cat-file -e "${LOCAL_SHA}:gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || \
  fail "published commit does not contain gradle/wrapper/gradle-wrapper.jar"
git push -u origin "$WORK_BRANCH"

REMOTE_SHA="$(git ls-remote origin "refs/heads/$WORK_BRANCH" | awk '{print $1}')"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] || fail "remote PR branch SHA does not match local HEAD"

PR_BODY="$(cat <<BODY
## Reviewed Termux publication

- Source branch: \`$WORK_BRANCH\`
- Exact head: \`$LOCAL_SHA\`
- Base: protected \`$TARGET_BRANCH\`
- Local build/CI was intentionally not run by the publisher.

GitHub Actions on this Pull Request is the source of truth before merge.
BODY
)"
PR_URL="$(
  gh pr create \
    --repo "$REPO" \
    --base "$TARGET_BRANCH" \
    --head "$WORK_BRANCH" \
    --title "$COMMIT_MESSAGE" \
    --body "$PR_BODY"
)"

printf 'PULL REQUEST CREATED. No local build, CI wait, or merge was started.\n'
printf 'BRANCH    =%s\n' "$WORK_BRANCH"
printf 'LOCAL_SHA =%s\n' "$LOCAL_SHA"
printf 'REMOTE_SHA=%s\n' "$REMOTE_SHA"
printf 'PR_URL    =%s\n' "$PR_URL"
printf 'Watch checks: gh pr checks --repo %s --watch "%s"\n' "$REPO" "$PR_URL"
printf 'Merge after green checks: gh pr merge --repo %s --merge --delete-branch "%s"\n' "$REPO" "$PR_URL"
