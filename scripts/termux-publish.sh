#!/data/data/com.termux/files/usr/bin/bash
# Run canonical checks, commit reviewed changes, and push the current branch without force.
set -euo pipefail

COMMIT_MESSAGE="${1:-NekoFlash reviewed update}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for cmd in git gh python3 bash; do
  command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
done

[ -d .git ] || fail "not a Git repository: $ROOT"
CURRENT_BRANCH="$(git branch --show-current)"
[ -n "$CURRENT_BRANCH" ] || fail "detached HEAD is not supported"
TARGET_BRANCH="${NEKOFLASH_BRANCH:-$CURRENT_BRANCH}"
[ "$CURRENT_BRANCH" = "$TARGET_BRANCH" ] || fail "checkout $TARGET_BRANCH before publishing"
[ -n "$(git config user.name || true)" ] || fail "configure git user.name"
[ -n "$(git config user.email || true)" ] || fail "configure git user.email"
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authenticated"

git fetch origin --prune
if git show-ref --verify --quiet "refs/remotes/origin/$TARGET_BRANCH"; then
  git pull --ff-only origin "$TARGET_BRANCH"
fi

python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
python3 scripts/check-ab-safety.py
python3 scripts/check-usb-connectivity.py
python3 scripts/check-flash-safety.py
python3 scripts/check-diagnostic-logging.py
bash scripts/run-tests.sh

rm -rf .gradle build app/build
find . -type d -name '__pycache__' -prune -exec rm -rf {} +
find . -type f \( -name '*.pyc' -o -name '*.tmp' -o -name '*.partial' \) -delete

python3 scripts/update-checksums.py
python3 scripts/check-documentation.py

git add -A
if git diff --cached --quiet; then
  printf 'No source changes to commit.\n'
  exit 0
fi

git commit -m "$COMMIT_MESSAGE"
git push -u origin "$TARGET_BRANCH"

LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git ls-remote origin "refs/heads/$TARGET_BRANCH" | awk '{print $1}')"
printf 'BRANCH    =%s\n' "$TARGET_BRANCH"
printf 'LOCAL_SHA =%s\n' "$LOCAL_SHA"
printf 'REMOTE_SHA=%s\n' "$REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] || fail "remote SHA does not match local HEAD"

printf 'Published successfully without force push.\n'
printf 'Push-triggered CI applies only when build.yml includes this branch.\n'
printf 'For a manual run use: gh workflow run build.yml --ref %s\n' "$TARGET_BRANCH"
