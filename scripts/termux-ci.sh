#!/data/data/com.termux/files/usr/bin/bash
# Launch or collect one NekoFlash GitHub Actions run from Termux.
#
# Default: collect lightweight CI evidence (metadata, logs and report artifacts).
# APK artifacts are not downloaded unless --with-apk is explicitly requested.
#
# Start a new run:
#   bash scripts/termux-ci.sh
#
# Collect an existing run without launching another:
#   bash scripts/termux-ci.sh --run-id 29832274659
#
# Collect CI evidence and also download APKs into a separate archive:
#   bash scripts/termux-ci.sh --run-id 29832274659 --with-apk
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
WITH_APK=0
POLL_SECONDS="${NEKOFLASH_POLL_SECONDS:-15}"
MAX_POLLS="${NEKOFLASH_MAX_POLLS:-720}"

usage() {
  cat <<USAGE
Usage:
  bash scripts/termux-ci.sh
  bash scripts/termux-ci.sh --run-id RUN_ID
  bash scripts/termux-ci.sh --run-id RUN_ID --with-apk

Default output is a lightweight CI evidence archive with metadata, logs and
report artifacts. APKs are downloaded only with --with-apk and are written to a
separate NekoFlash-APK-<RUN_ID>.zip archive.
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
    --with-apk)
      WITH_APK=1
      shift
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

for cmd in gh jq git grep sed awk zip find cp date seq; do
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
APK_RESULT_NAME="NekoFlash-APK-$RUN_ID"
LOCAL_RESULT_DIR="$HOME/NekoFlash-CI/run-$RUN_ID"
LOCAL_APK_DIR="$HOME/NekoFlash-CI/apk-$RUN_ID"
ANDROID_RESULT_DIR="$DOWNLOAD_DIR/$RESULT_NAME"
ANDROID_APK_DIR="$DOWNLOAD_DIR/$APK_RESULT_NAME"
ZIP_PATH="$DOWNLOAD_DIR/$RESULT_NAME.zip"
APK_ZIP_PATH="$DOWNLOAD_DIR/$APK_RESULT_NAME.zip"

rm -rf "$LOCAL_RESULT_DIR" "$ANDROID_RESULT_DIR"
mkdir -p "$LOCAL_RESULT_DIR" "$ANDROID_RESULT_DIR"

if [ "$WITH_APK" -eq 1 ]; then
  rm -rf "$LOCAL_APK_DIR" "$ANDROID_APK_DIR"
  mkdir -p "$LOCAL_APK_DIR" "$ANDROID_APK_DIR"
fi

printf 'RUN_ID=%s\n' "$RUN_ID"

RUN_STATUS=""
RUN_CONCLUSION=""
RUN_URL=""
RUN_SHA=""
RUN_HEAD_BRANCH=""
RUN_EVENT=""

for attempt in $(seq 1 "$MAX_POLLS"); do
  RUN_JSON="$(
    gh run view "$RUN_ID" \
      --repo "$REPO" \
      --json status,conclusion,url,headSha,headBranch,event 2>/dev/null || true
  )"

  RUN_STATUS="$(printf '%s' "$RUN_JSON" | jq -r '.status // empty' 2>/dev/null)"
  RUN_CONCLUSION="$(printf '%s' "$RUN_JSON" | jq -r '.conclusion // empty' 2>/dev/null)"
  RUN_URL="$(printf '%s' "$RUN_JSON" | jq -r '.url // empty' 2>/dev/null)"
  RUN_SHA="$(printf '%s' "$RUN_JSON" | jq -r '.headSha // empty' 2>/dev/null)"
  RUN_HEAD_BRANCH="$(printf '%s' "$RUN_JSON" | jq -r '.headBranch // empty' 2>/dev/null)"
  RUN_EVENT="$(printf '%s' "$RUN_JSON" | jq -r '.event // empty' 2>/dev/null)"

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
Requested branch: $BRANCH
Head branch: ${RUN_HEAD_BRANCH:-unknown}
Event: ${RUN_EVENT:-unknown}
Run ID: $RUN_ID
Conclusion: ${RUN_CONCLUSION:-unknown}
Head SHA: ${RUN_SHA:-unknown}
URL: ${RUN_URL:-unknown}
Collection mode: $([ "$WITH_APK" -eq 1 ] && printf 'evidence + separate APK archive' || printf 'evidence only (no APK)')
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

ARTIFACTS_JSON="$(
  gh api \
    -H 'Accept: application/vnd.github+json' \
    "/repos/$REPO/actions/runs/$RUN_ID/artifacts?per_page=100" 2>/dev/null || printf '{"artifacts":[]}'
)"
printf '%s\n' "$ARTIFACTS_JSON" > "$LOCAL_RESULT_DIR/artifacts.json"

REPORT_ARTIFACTS="$(
  printf '%s' "$ARTIFACTS_JSON" | jq -r \
    '.artifacts[]? | select(.expired == false) | .name | select(endswith("-reports"))'
)"
APK_ARTIFACTS="$(
  printf '%s' "$ARTIFACTS_JSON" | jq -r \
    '.artifacts[]? | select(.expired == false) | .name | select(endswith("-apk"))'
)"

{
  printf 'Default CI archive excludes APK artifacts.\n'
  printf 'Report artifacts selected:\n'
  if [ -n "$REPORT_ARTIFACTS" ]; then
    printf '%s\n' "$REPORT_ARTIFACTS"
  else
    printf '(none)\n'
  fi
  printf 'APK artifacts available:\n'
  if [ -n "$APK_ARTIFACTS" ]; then
    printf '%s\n' "$APK_ARTIFACTS"
  else
    printf '(none)\n'
  fi
  printf 'APK download requested: %s\n' "$([ "$WITH_APK" -eq 1 ] && printf yes || printf no)"
} > "$LOCAL_RESULT_DIR/artifact-selection.txt"

if [ "$RUN_CONCLUSION" = "success" ]; then
  mkdir -p "$LOCAL_RESULT_DIR/reports"
  : > "$LOCAL_RESULT_DIR/report-download.log"

  if [ -n "$REPORT_ARTIFACTS" ]; then
    while IFS= read -r artifact_name; do
      [ -n "$artifact_name" ] || continue
      printf 'Downloading report artifact: %s\n' "$artifact_name" >> "$LOCAL_RESULT_DIR/report-download.log"
      gh run download "$RUN_ID" \
        --repo "$REPO" \
        --name "$artifact_name" \
        -D "$LOCAL_RESULT_DIR/reports/$artifact_name" \
        >> "$LOCAL_RESULT_DIR/report-download.log" 2>&1 || true
    done <<EOF_REPORTS
$REPORT_ARTIFACTS
EOF_REPORTS
  else
    printf 'No non-expired report artifact was found; full.log remains available.\n' \
      >> "$LOCAL_RESULT_DIR/report-download.log"
  fi

  if [ "$WITH_APK" -eq 1 ]; then
    : > "$LOCAL_APK_DIR/apk-download.log"
    cp "$LOCAL_RESULT_DIR/run-info.txt" "$LOCAL_APK_DIR/run-info.txt"
    cp "$LOCAL_RESULT_DIR/artifact-selection.txt" "$LOCAL_APK_DIR/artifact-selection.txt"

    if [ -n "$APK_ARTIFACTS" ]; then
      while IFS= read -r artifact_name; do
        [ -n "$artifact_name" ] || continue
        printf 'Downloading APK artifact: %s\n' "$artifact_name" >> "$LOCAL_APK_DIR/apk-download.log"
        gh run download "$RUN_ID" \
          --repo "$REPO" \
          --name "$artifact_name" \
          -D "$LOCAL_APK_DIR/$artifact_name" \
          >> "$LOCAL_APK_DIR/apk-download.log" 2>&1 || true
      done <<EOF_APKS
$APK_ARTIFACTS
EOF_APKS
    else
      printf 'No non-expired APK artifact was found.\n' >> "$LOCAL_APK_DIR/apk-download.log"
    fi
  fi
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

if [ "$WITH_APK" -eq 1 ] && [ "$RUN_CONCLUSION" = "success" ]; then
  cp -a "$LOCAL_APK_DIR/." "$ANDROID_APK_DIR/"
  rm -f "$APK_ZIP_PATH"
  (
    cd "$DOWNLOAD_DIR" || exit 1
    zip -qr "$APK_ZIP_PATH" "$APK_RESULT_NAME"
  ) || fail "could not create APK ZIP: $APK_ZIP_PATH"
fi

printf '\nConclusion: %s\n' "${RUN_CONCLUSION:-unknown}"
printf 'Evidence:   %s\n' "$ANDROID_RESULT_DIR"
printf 'Evidence ZIP: %s\n' "$ZIP_PATH"
if [ "$WITH_APK" -eq 1 ] && [ "$RUN_CONCLUSION" = "success" ]; then
  printf 'APK folder: %s\n' "$ANDROID_APK_DIR"
  printf 'APK ZIP:    %s\n' "$APK_ZIP_PATH"
else
  printf 'APK:        not downloaded (use --with-apk when needed)\n'
fi
printf 'GitHub:     %s\n' "${RUN_URL:-unknown}"

if [ "$RUN_CONCLUSION" != "success" ]; then
  printf '\nError summary:\n'
  sed -n '1,120p' "$ANDROID_RESULT_DIR/error-summary.txt" 2>/dev/null || true
  exit 1
fi

exit 0
