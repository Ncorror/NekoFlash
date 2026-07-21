#!/data/data/com.termux/files/usr/bin/bash
# Launch or collect one NekoFlash GitHub Actions run from Termux.
#
# Start a new run:
#   bash scripts/termux-ci.sh
#
# Collect an existing run without launching another:
#   bash scripts/termux-ci.sh --run-id 29832274659
#
# Optional environment variables:
#   NEKOFLASH_REPO=Ncorror/NekoFlash
#   NEKOFLASH_WORKFLOW=build.yml
#   NEKOFLASH_BRANCH=main
#   NEKOFLASH_DOWNLOAD_DIR=$HOME/storage/downloads
set -uo pipefail

REPO="${NEKOFLASH_REPO:-Ncorror/NekoFlash}"
WORKFLOW="${NEKOFLASH_WORKFLOW:-build.yml}"
BRANCH="${NEKOFLASH_BRANCH:-main}"
PROJECT_DIR="${NEKOFLASH_PROJECT_DIR:-$HOME/NekoFlash}"
DOWNLOAD_DIR="${NEKOFLASH_DOWNLOAD_DIR:-$HOME/storage/downloads}"
RUN_ID=""
LAUNCH_NEW=1
POLL_SECONDS="${NEKOFLASH_POLL_SECONDS:-15}"
MAX_POLLS="${NEKOFLASH_MAX_POLLS:-720}"

usage() {
  cat <<USAGE
Usage:
  bash scripts/termux-ci.sh
  bash scripts/termux-ci.sh --run-id RUN_ID

The script waits until GitHub reports status=completed. Only then it downloads
logs and artifacts, creates a compact error summary when needed, and writes a
ZIP to Android Download.
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --run-id)
      [ "$#" -ge 2 ] || fail "--run-id requires a value"
      RUN_ID="$2"
      LAUNCH_NEW=0
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

for cmd in gh jq git grep sed awk zip find cp date; do
  command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"
done

[ -d "$PROJECT_DIR/.git" ] || fail "Git repository not found: $PROJECT_DIR"
[ -d "$DOWNLOAD_DIR" ] || fail "Android Download is unavailable. Run: termux-setup-storage"
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authenticated. Run: gh auth login"

cd "$PROJECT_DIR" || fail "cannot enter $PROJECT_DIR"

if ! gh workflow view "$WORKFLOW" --repo "$REPO" >/dev/null 2>&1; then
  gh workflow list --all --repo "$REPO" || true
  fail "workflow not found: $WORKFLOW"
fi

if [ "$LAUNCH_NEW" -eq 1 ]; then
  BEFORE_ID="$(
    gh run list \
      --repo "$REPO" \
      --workflow "$WORKFLOW" \
      --branch "$BRANCH" \
      --event workflow_dispatch \
      --limit 1 \
      --json databaseId \
      --jq '.[0].databaseId // empty' 2>/dev/null || true
  )"

  printf 'Launching %s on %s...\n' "$WORKFLOW" "$BRANCH"
  gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" || fail "workflow dispatch failed"

  printf 'Waiting for GitHub to register the new run...\n'
  for _ in $(seq 1 30); do
    CANDIDATE_ID="$(
      gh run list \
        --repo "$REPO" \
        --workflow "$WORKFLOW" \
        --branch "$BRANCH" \
        --event workflow_dispatch \
        --limit 1 \
        --json databaseId \
        --jq '.[0].databaseId // empty' 2>/dev/null || true
    )"

    if [ -n "$CANDIDATE_ID" ] && [ "$CANDIDATE_ID" != "${BEFORE_ID:-}" ]; then
      RUN_ID="$CANDIDATE_ID"
      break
    fi
    sleep 2
  done

  [ -n "$RUN_ID" ] || fail "new workflow run was not found within 60 seconds"
fi

[[ "$RUN_ID" =~ ^[0-9]+$ ]] || fail "invalid RUN_ID: $RUN_ID"

RESULT_NAME="NekoFlash-CI-$RUN_ID"
LOCAL_RESULT_DIR="$HOME/NekoFlash-CI/run-$RUN_ID"
ANDROID_RESULT_DIR="$DOWNLOAD_DIR/$RESULT_NAME"
ZIP_PATH="$DOWNLOAD_DIR/$RESULT_NAME.zip"

rm -rf "$LOCAL_RESULT_DIR" "$ANDROID_RESULT_DIR"
mkdir -p "$LOCAL_RESULT_DIR" "$ANDROID_RESULT_DIR"

printf 'RUN_ID=%s\n' "$RUN_ID"

RUN_STATUS=""
RUN_CONCLUSION=""
RUN_URL=""
RUN_SHA=""

for attempt in $(seq 1 "$MAX_POLLS"); do
  RUN_JSON="$(
    gh run view "$RUN_ID" \
      --repo "$REPO" \
      --json status,conclusion,url,headSha 2>/dev/null || true
  )"

  RUN_STATUS="$(printf '%s' "$RUN_JSON" | jq -r '.status // empty' 2>/dev/null)"
  RUN_CONCLUSION="$(printf '%s' "$RUN_JSON" | jq -r '.conclusion // empty' 2>/dev/null)"
  RUN_URL="$(printf '%s' "$RUN_JSON" | jq -r '.url // empty' 2>/dev/null)"
  RUN_SHA="$(printf '%s' "$RUN_JSON" | jq -r '.headSha // empty' 2>/dev/null)"

  printf '\rstatus=%-14s conclusion=%-12s poll=%s/%s' \
    "${RUN_STATUS:-unknown}" \
    "${RUN_CONCLUSION:--}" \
    "$attempt" \
    "$MAX_POLLS"

  if [ "$RUN_STATUS" = "completed" ]; then
    printf '\n'
    break
  fi

  sleep "$POLL_SECONDS"
done

if [ "$RUN_STATUS" != "completed" ]; then
  printf '\n'
  fail "workflow did not complete within the configured wait period"
fi

cat > "$LOCAL_RESULT_DIR/run-info.txt" <<INFO
Repository: $REPO
Workflow: $WORKFLOW
Branch: $BRANCH
Run ID: $RUN_ID
Conclusion: ${RUN_CONCLUSION:-unknown}
Head SHA: ${RUN_SHA:-unknown}
URL: ${RUN_URL:-unknown}
Collected from Termux: $(date '+%Y-%m-%d %H:%M:%S %z')
INFO

gh run view "$RUN_ID" \
  --repo "$REPO" \
  --json status,conclusion,url,event,headBranch,headSha,createdAt,updatedAt,jobs \
  > "$LOCAL_RESULT_DIR/run-result.json" 2>&1 || true

gh run view "$RUN_ID" \
  --repo "$REPO" \
  --json jobs \
  --jq '.jobs[] | [.name, .status, .conclusion] | @tsv' \
  > "$LOCAL_RESULT_DIR/jobs.tsv" 2>&1 || true

gh run view "$RUN_ID" \
  --repo "$REPO" \
  --log \
  > "$LOCAL_RESULT_DIR/full.log" 2>&1 || true

if [ "$RUN_CONCLUSION" = "success" ]; then
  mkdir -p "$LOCAL_RESULT_DIR/artifacts"
  gh run download "$RUN_ID" \
    --repo "$REPO" \
    -D "$LOCAL_RESULT_DIR/artifacts" \
    > "$LOCAL_RESULT_DIR/artifact-download.log" 2>&1 || true
else
  gh run view "$RUN_ID" \
    --repo "$REPO" \
    --log-failed \
    > "$LOCAL_RESULT_DIR/failed.log" 2>&1 || true

  grep -nEi \
    '(^|[[:space:]])e: |error: |unresolved reference|type mismatch|no value passed|too many arguments|cannot access|overload resolution ambiguity|compilation error|execution failed for task|exception|failed' \
    "$LOCAL_RESULT_DIR/full.log" \
    > "$LOCAL_RESULT_DIR/compiler-errors.log" || true

  grep -nEi \
    '\.kt:[0-9]+|\.java:[0-9]+|\.xml:[0-9]+|\.gradle(\.kts)?:[0-9]+' \
    "$LOCAL_RESULT_DIR/full.log" \
    > "$LOCAL_RESULT_DIR/source-locations.log" || true

  {
    printf '=== RUN ===\n'
    cat "$LOCAL_RESULT_DIR/run-info.txt"
    printf '\n=== COMPILER/BUILD ERRORS ===\n'
    if [ -s "$LOCAL_RESULT_DIR/compiler-errors.log" ]; then
      cat "$LOCAL_RESULT_DIR/compiler-errors.log"
    else
      printf 'No compiler-error lines were matched automatically. Inspect full.log and failed.log.\n'
    fi
    printf '\n=== LAST 250 FAILED LINES ===\n'
    tail -n 250 "$LOCAL_RESULT_DIR/failed.log" 2>/dev/null || true
  } > "$LOCAL_RESULT_DIR/error-summary.txt"
fi

cp -a "$LOCAL_RESULT_DIR/." "$ANDROID_RESULT_DIR/"
rm -f "$ZIP_PATH"
(
  cd "$DOWNLOAD_DIR" || exit 1
  zip -qr "$ZIP_PATH" "$RESULT_NAME"
) || fail "could not create ZIP: $ZIP_PATH"

printf '\nConclusion: %s\n' "${RUN_CONCLUSION:-unknown}"
printf 'Folder:     %s\n' "$ANDROID_RESULT_DIR"
printf 'ZIP:        %s\n' "$ZIP_PATH"
printf 'GitHub:     %s\n' "${RUN_URL:-unknown}"

if [ "$RUN_CONCLUSION" != "success" ]; then
  printf '\nError summary:\n'
  sed -n '1,120p' "$ANDROID_RESULT_DIR/error-summary.txt" 2>/dev/null || true
  exit 1
fi

exit 0
