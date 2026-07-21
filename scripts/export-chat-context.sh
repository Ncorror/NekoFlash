#!/data/data/com.termux/files/usr/bin/bash
# Build one compact text handoff for uploading beside the source archive.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_OUTPUT="$HOME/storage/downloads/NekoFlash-chat-context.txt"
OUTPUT="${1:-$DEFAULT_OUTPUT}"

mkdir -p "$(dirname "$OUTPUT")"

{
  printf 'NEKOFLASH CHAT CONTEXT\n'
  printf 'Generated: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
  if [ -d "$ROOT/.git" ]; then
    printf 'Branch: %s\n' "$(git -C "$ROOT" branch --show-current 2>/dev/null || true)"
    printf 'Commit: %s\n' "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
  fi
  printf '\n'

  for rel in \
    docs/AI_START_HERE.md \
    PROJECT_MASTER_TRACKER.md \
    docs/RECOVERY_FIRST_PLAN.md \
    docs/ALPHA5_HARDWARE_POLISH_PLAN.md \
    docs/SCOPE.md \
    docs/SAFETY_MODEL.md \
    docs/TERMUX_WORKFLOW.md \
    CHANGELOG.md; do
    printf '\n===== %s =====\n\n' "$rel"
    cat "$ROOT/$rel"
  done
} > "$OUTPUT"

printf 'Chat context created: %s\n' "$OUTPUT"
