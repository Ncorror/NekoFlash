#!/data/data/com.termux/files/usr/bin/bash
# Publish one reviewed NekoFlash tree to both GitHub branches without force-push.
#
# First use after extracting an archive:
#   bash scripts/termux-publish-both-branches.sh https://github.com/OWNER/REPO.git "V6 scope update"
#
# Later uses inside the same clone:
#   bash scripts/termux-publish-both-branches.sh "" "Update message"
set -euo pipefail

REMOTE_URL="${1:-}"
COMMIT_MESSAGE="${2:-NekoFlash reviewed update}"
MAIN_BRANCH="main"
REFACTOR_BRANCH="claude-ai-refactor"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail "git is not installed. Run: pkg install git"
command -v python3 >/dev/null 2>&1 || fail "python is not installed. Run: pkg install python"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -d .git ]]; then
  [[ -n "$REMOTE_URL" ]] || fail "repository URL is required on the first run"
  git init
fi

if git remote get-url origin >/dev/null 2>&1; then
  [[ -z "$REMOTE_URL" ]] || git remote set-url origin "$REMOTE_URL"
else
  [[ -n "$REMOTE_URL" ]] || fail "origin is missing; pass the GitHub repository URL"
  git remote add origin "$REMOTE_URL"
fi

AUTHOR_NAME="$(git config user.name || true)"
AUTHOR_EMAIL="$(git config user.email || true)"
[[ -n "$AUTHOR_NAME" ]] || fail "configure identity: git config user.name 'Your Name'"
[[ -n "$AUTHOR_EMAIL" ]] || fail "configure identity: git config user.email 'you@example.com'"

if [[ "${SKIP_CHECKS:-0}" != "1" ]]; then
  python3 scripts/update-checksums.py
  python3 scripts/check-documentation.py
  python3 scripts/test-checksum-inventory.py
  python3 scripts/check_project.py
  python3 scripts/check-ab-safety.py
  python3 scripts/check-usb-connectivity.py
  python3 scripts/check-flash-safety.py
  python3 scripts/check-diagnostic-logging.py
fi

# Fetch all current remote tips. Authentication failures are intentionally fatal.
git fetch origin --prune

git add -A
TREE="$(git write-tree)"

parent_args=()
parent_ids=()
for ref in "refs/remotes/origin/$MAIN_BRANCH" "refs/remotes/origin/$REFACTOR_BRANCH"; do
  if git show-ref --verify --quiet "$ref"; then
    tip="$(git rev-parse "$ref")"
    parent_args+=("-p" "$tip")
    parent_ids+=("$tip")
  fi
done

if git rev-parse --verify HEAD >/dev/null 2>&1; then
  head_id="$(git rev-parse HEAD)"
  already_parent=false
  for parent in "${parent_ids[@]:-}"; do
    [[ "$parent" == "$head_id" ]] && already_parent=true
  done
  if [[ "$already_parent" == false ]]; then
    parent_args+=("-p" "$head_id")
  fi
fi

COMMIT_BODY="$COMMIT_MESSAGE

Canonical-tree publication created by scripts/termux-publish-both-branches.sh.
The commit keeps both remote branch histories as parents and does not force-push."
NEW_COMMIT="$(printf '%s\n' "$COMMIT_BODY" | git commit-tree "$TREE" "${parent_args[@]}")"

git update-ref "refs/heads/$MAIN_BRANCH" "$NEW_COMMIT"
git update-ref "refs/heads/$REFACTOR_BRANCH" "$NEW_COMMIT"
git checkout -f "$MAIN_BRANCH" >/dev/null

# Atomic means either both branch updates are accepted, or neither is changed.
git push --atomic origin \
  "$NEW_COMMIT:refs/heads/$MAIN_BRANCH" \
  "$NEW_COMMIT:refs/heads/$REFACTOR_BRANCH"

origin="$(git remote get-url origin)"
case "$origin" in
  git@github.com:*) web="https://github.com/${origin#git@github.com:}" ;;
  ssh://git@github.com/*) web="https://github.com/${origin#ssh://git@github.com/}" ;;
  https://github.com/*|http://github.com/*) web="$origin" ;;
  *) web="$origin" ;;
esac
web="${web%.git}"

printf '\nPublished commit: %s\n' "$NEW_COMMIT"
printf 'main: %s/tree/%s\n' "$web" "$MAIN_BRANCH"
printf 'claude-ai-refactor: %s/tree/%s\n' "$web" "$REFACTOR_BRANCH"
printf '\nIf GitHub rejected the push, check branch protection or authentication. The script never force-pushes.\n'
