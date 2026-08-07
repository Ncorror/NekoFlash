#!/data/data/com.termux/files/usr/bin/bash
# One-time, fail-closed consolidation of the reviewed feature line into a
# protected main branch through a GitHub Pull Request.
# The script never force-pushes or rebases published history.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${NEKOFLASH_REPO:-Ncorror/NekoFlash}"
WORKFLOW="${NEKOFLASH_WORKFLOW:-build.yml}"
SOURCE_BRANCH="${NEKOFLASH_SOURCE_BRANCH:-feature/recovery-first-quick-flash}"
TARGET_BRANCH="${NEKOFLASH_TARGET_BRANCH:-main}"
ARCHIVE_TAG="${NEKOFLASH_ARCHIVE_TAG:-archive/recovery-first-quick-flash-final-2026-08-03}"
PR_TITLE="${NEKOFLASH_PR_TITLE:-Consolidate recovery-first development into main}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for cmd in git gh jq awk sed grep sort head; do
  command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
done

cd "$ROOT"
[ -d .git ] || fail "not a Git repository: $ROOT"
gh auth status -h github.com >/dev/null 2>&1 || fail "GitHub CLI is not authenticated"

CURRENT_BRANCH="$(git branch --show-current)"
[ "$CURRENT_BRANCH" = "$SOURCE_BRANCH" ] || \
  fail "checkout $SOURCE_BRANCH before consolidation (current: ${CURRENT_BRANCH:-detached})"
[ -z "$(git status --porcelain)" ] || {
  git status --short
  fail "working tree must be clean"
}

printf 'Fetching current remote state...\n'
git fetch origin --prune --tags

git show-ref --verify --quiet "refs/remotes/origin/$TARGET_BRANCH" || \
  fail "remote target branch does not exist: $TARGET_BRANCH"

SOURCE_SHA="$(git rev-parse HEAD)"
REMOTE_SOURCE_EXISTS=0
if git show-ref --verify --quiet "refs/remotes/origin/$SOURCE_BRANCH"; then
  REMOTE_SOURCE_EXISTS=1
  REMOTE_SOURCE_SHA="$(git rev-parse "origin/$SOURCE_BRANCH")"
  [ "$SOURCE_SHA" = "$REMOTE_SOURCE_SHA" ] || \
    fail "local source HEAD differs from origin/$SOURCE_BRANCH"
fi

UNEXPECTED_BRANCHES="$({
  git for-each-ref --format='%(refname:strip=3)' refs/remotes/origin \
    | grep -v '^HEAD$' \
    | grep -v -F -x "$SOURCE_BRANCH" \
    | grep -v -F -x "$TARGET_BRANCH" \
    | sort -u
} || true)"
[ -z "$UNEXPECTED_BRANCHES" ] || {
  printf 'Unexpected remote branches:\n%s\n' "$UNEXPECTED_BRANCHES" >&2
  fail "review unexpected branches before consolidating"
}

# Before merge, main must be an ancestor of the reviewed source. After a
# manual/automatic PR merge, the reviewed source must be an ancestor of main.
ALREADY_MERGED=0
if git merge-base --is-ancestor "$SOURCE_SHA" "origin/$TARGET_BRANCH"; then
  ALREADY_MERGED=1
else
  git merge-base --is-ancestor "origin/$TARGET_BRANCH" "$SOURCE_SHA" || \
    fail "main/source histories diverged; consolidation is unsafe"
fi

SUCCESS_RUN_ID="$(
  gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --branch "$SOURCE_BRANCH" \
    --limit 50 \
    --json databaseId,headSha,status,conclusion \
    --jq ".[] | select(.headSha == \"$SOURCE_SHA\" and .status == \"completed\" and .conclusion == \"success\") | .databaseId" \
    | head -1
)"
[ -n "$SUCCESS_RUN_ID" ] || \
  fail "no successful $WORKFLOW run found for exact source HEAD $SOURCE_SHA"

DEFAULT_BRANCH="$(gh repo view "$REPO" --json defaultBranchRef --jq '.defaultBranchRef.name')"
[ "$DEFAULT_BRANCH" = "$TARGET_BRANCH" ] || \
  fail "GitHub default branch must already be $TARGET_BRANCH (current: $DEFAULT_BRANCH)"

printf 'SOURCE_BRANCH=%s\n' "$SOURCE_BRANCH"
printf 'TARGET_BRANCH=%s\n' "$TARGET_BRANCH"
printf 'SOURCE_SHA=%s\n' "$SOURCE_SHA"
printf 'GREEN_CI_RUN=%s\n' "$SUCCESS_RUN_ID"
printf 'ARCHIVE_TAG=%s\n' "$ARCHIVE_TAG"

REMOTE_TAG_OBJECT="$(git ls-remote origin "refs/tags/$ARCHIVE_TAG" | awk '{print $1}')"
REMOTE_TAG_DEREF="$(git ls-remote origin "refs/tags/$ARCHIVE_TAG^{}" | awk '{print $1}')"
if [ -z "$REMOTE_TAG_OBJECT" ]; then
  if git show-ref --verify --quiet "refs/tags/$ARCHIVE_TAG"; then
    LOCAL_TAG_SHA="$(git rev-list -n 1 "$ARCHIVE_TAG")"
    [ "$LOCAL_TAG_SHA" = "$SOURCE_SHA" ] || fail "local archive tag points to another commit"
  else
    git tag -a "$ARCHIVE_TAG" "$SOURCE_SHA" \
      -m "Final recovery-first feature head before protected-main consolidation"
  fi
  git push origin "refs/tags/$ARCHIVE_TAG"
else
  REMOTE_TAG_SHA="${REMOTE_TAG_DEREF:-$REMOTE_TAG_OBJECT}"
  [ "$REMOTE_TAG_SHA" = "$SOURCE_SHA" ] || \
    fail "remote archive tag points to another commit: $REMOTE_TAG_SHA"
fi

PR_NUMBER="$(
  gh pr list \
    --repo "$REPO" \
    --base "$TARGET_BRANCH" \
    --head "$SOURCE_BRANCH" \
    --state open \
    --json number \
    --jq '.[0].number // empty'
)"
if [ -z "$PR_NUMBER" ]; then
  PR_NUMBER="$(
    gh pr list \
      --repo "$REPO" \
      --base "$TARGET_BRANCH" \
      --head "$SOURCE_BRANCH" \
      --state merged \
      --limit 20 \
      --json number,headRefOid \
      --jq ".[] | select(.headRefOid == \"$SOURCE_SHA\") | .number" \
      | head -1
  )"
fi

if [ -z "$PR_NUMBER" ]; then
  [ "$REMOTE_SOURCE_EXISTS" -eq 1 ] || \
    fail "source branch is absent remotely and no merged PR for source SHA was found"
  PR_BODY="$(cat <<BODY
## Consolidation evidence

- Reviewed source SHA: \`$SOURCE_SHA\`
- Successful exact-head CI run: \`$SUCCESS_RUN_ID\`
- Recovery tag: \`$ARCHIVE_TAG\`
- Target: protected \`$TARGET_BRANCH\`

This PR replaces the failed direct-push path with the repository-required protected-branch review path. No force push or rebase is used.
BODY
)"
  PR_URL="$(
    gh pr create \
      --repo "$REPO" \
      --base "$TARGET_BRANCH" \
      --head "$SOURCE_BRANCH" \
      --title "$PR_TITLE" \
      --body "$PR_BODY"
  )"
  PR_NUMBER="$(gh pr view "$PR_URL" --repo "$REPO" --json number --jq '.number')"
else
  PR_URL="$(gh pr view "$PR_NUMBER" --repo "$REPO" --json url --jq '.url')"
fi

PR_HEAD_SHA="$(gh pr view "$PR_NUMBER" --repo "$REPO" --json headRefOid --jq '.headRefOid')"
[ "$PR_HEAD_SHA" = "$SOURCE_SHA" ] || \
  fail "PR head SHA differs from reviewed source HEAD: $PR_HEAD_SHA"

printf 'PR_NUMBER=%s\n' "$PR_NUMBER"
printf 'PR_URL=%s\n' "$PR_URL"
PR_STATE="$(gh pr view "$PR_NUMBER" --repo "$REPO" --json state --jq '.state')"
if [ "$PR_STATE" != "MERGED" ]; then
  printf 'Waiting for Pull Request checks...\n'
  if ! gh pr checks "$PR_NUMBER" --repo "$REPO" --watch; then
    printf 'Pull Request checks did not pass. The source branch and recovery tag were preserved.\n' >&2
    printf 'Inspect: %s\n' "$PR_URL" >&2
    exit 2
  fi
  printf 'Merging reviewed Pull Request into protected main...\n'
  if ! gh pr merge "$PR_NUMBER" --repo "$REPO" --merge --delete-branch; then
    printf 'GitHub policy requires a manual approval or merge. No branch was deleted.\n' >&2
    printf 'Open and merge this reviewed PR, then rerun the same script: %s\n' "$PR_URL" >&2
    exit 3
  fi
fi

printf 'Verifying protected main after PR merge...\n'
git fetch origin --prune --tags
REMOTE_TARGET_SHA="$(git rev-parse "origin/$TARGET_BRANCH")"
git merge-base --is-ancestor "$SOURCE_SHA" "origin/$TARGET_BRANCH" || \
  fail "reviewed source SHA is not an ancestor of remote $TARGET_BRANCH"

git cat-file -e "origin/$TARGET_BRANCH:gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || \
  fail "consolidated main is missing gradle/wrapper/gradle-wrapper.jar"

if git show-ref --verify --quiet "refs/remotes/origin/$SOURCE_BRANCH"; then
  printf 'Deleting source branch only after merged-main verification...\n'
  git push origin --delete "$SOURCE_BRANCH"
  git fetch origin --prune
fi

git switch "$TARGET_BRANCH"
git merge --ff-only "origin/$TARGET_BRANCH"
if git show-ref --verify --quiet "refs/heads/$SOURCE_BRANCH"; then
  git branch -d "$SOURCE_BRANCH"
fi

REMAINING_BRANCHES="$({
  git for-each-ref --format='%(refname:strip=3)' refs/remotes/origin \
    | grep -v '^HEAD$' \
    | sort -u
} || true)"
[ "$REMAINING_BRANCHES" = "$TARGET_BRANCH" ] || {
  printf 'Remaining remote branches:\n%s\n' "$REMAINING_BRANCHES" >&2
  fail "repository was not reduced to main after merged PR"
}

printf '\nBRANCH CONSOLIDATION COMPLETED\n'
printf 'CANONICAL_BRANCH=%s\n' "$TARGET_BRANCH"
printf 'CANONICAL_SHA=%s\n' "$REMOTE_TARGET_SHA"
printf 'REVIEWED_SOURCE_SHA=%s\n' "$SOURCE_SHA"
printf 'ARCHIVE_TAG=%s\n' "$ARCHIVE_TAG"
printf 'SOURCE_CI_RUN=%s\n' "$SUCCESS_RUN_ID"
printf 'PULL_REQUEST=%s\n' "$PR_URL"
printf 'The PR merge/push to main starts the normal GitHub Actions workflow.\n'
printf 'Collect that run with: bash scripts/termux-ci.sh --run-id RUN_ID\n'
