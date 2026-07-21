#!/usr/bin/env bash
#
# Unified NekoFlash protocol/policy test runner.
#
#   scripts/run-tests.sh                         # all modules
#   scripts/run-tests.sh fastboot-core adb-core # selected modules
#   scripts/run-tests.sh --list                 # module names
#   scripts/run-tests.sh --no-cache             # force recompilation
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/tools/tests.manifest"
CACHE_DIR="$ROOT/build/protocol-test-cache"
[[ -f "$MANIFEST" ]] || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 2; }

names=(); sources=()
while IFS=$'\t' read -r name srcs; do
  [[ -z "${name// }" || "${name#\#}" != "$name" ]] && continue
  names+=("$name"); sources+=("$srcs")
done < "$MANIFEST"

use_cache=true
selection=()
for arg in "$@"; do
  case "$arg" in
    --list) printf '%s\n' "${names[@]}"; exit 0 ;;
    --no-cache) use_cache=false ;;
    *) selection+=("$arg") ;;
  esac
done
[[ ${#selection[@]} -eq 0 ]] && selection=("${names[@]}")

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "ERROR: kotlinc not found on PATH; cannot run protocol/parser tests." >&2
  exit 2
fi
if ! command -v sha256sum >/dev/null 2>&1; then
  echo "ERROR: sha256sum not found on PATH." >&2
  exit 2
fi

mkdir -p "$CACHE_DIR"
KOTLIN_HEAP="${NEKOFLASH_KOTLIN_HEAP:-2048m}"
BACKEND_THREADS="${NEKOFLASH_KOTLIN_BACKEND_THREADS:-4}"
COMPILE_TIMEOUT="${NEKOFLASH_KOTLIN_COMPILE_TIMEOUT:-240}"
COMPILER_MODE="${NEKOFLASH_KOTLIN_COMPILER_MODE:-auto}"
[[ "$COMPILER_MODE" =~ ^(auto|k1|k2)$ ]] || { echo "ERROR: NEKOFLASH_KOTLIN_COMPILER_MODE must be auto, k1 or k2" >&2; exit 2; }
COMPILER_ID="$(kotlinc -version 2>&1 | tr '\n' ' ')"

find_index() {
  local want="$1" i
  for i in "${!names[@]}"; do
    [[ "${names[$i]}" == "$want" ]] && { echo "$i"; return 0; }
  done
  return 1
}

module_cache_key() {
  local name="$1"; shift
  {
    printf '%s\n' "runner-v3-bounded-k2-fallback" "$name" "$COMPILER_ID" "heap=$KOTLIN_HEAP" "threads=$BACKEND_THREADS" "mode=$COMPILER_MODE"
    local file
    for file in "$@"; do
      printf '%s  %s\n' "$(sha256sum "$file" | awk '{print $1}')" "${file#$ROOT/}"
    done
  } | sha256sum | awk '{print $1}'
}

run_module() {
  local name="$1" srcs="$2"
  local abs=() file
  for file in $srcs; do
    [[ -f "$ROOT/$file" ]] || { echo "  MISSING SOURCE: $file" >&2; return 3; }
    abs+=("$ROOT/$file")
  done

  local key jar
  key="$(module_cache_key "$name" "${abs[@]}")"
  jar="$CACHE_DIR/${name}-${key}.jar"

  if [[ "$use_cache" == true && -s "$jar" ]]; then
    echo ">>> [$name] cache hit"
  else
    local temporary="$jar.tmp.$$.jar"
    rm -f "$temporary"
    echo ">>> [$name] compiling ${#abs[@]} sources (heap=$KOTLIN_HEAP, backend-threads=$BACKEND_THREADS)"
    compile_module() {
      local mode="$1" threads="$2"
      local language_args=()
      [[ "$mode" == "k2" ]] && language_args=(-language-version 2.0)
      python3 "$ROOT/scripts/run-with-timeout.py" "$COMPILE_TIMEOUT" \
        kotlinc -J-Xmx"$KOTLIN_HEAP" -J-Dkotlin.environment.keepalive=false \
          "${language_args[@]}" -Xbackend-threads="$threads" \
          "${abs[@]}" -include-runtime -d "$temporary"
    }
    case "$COMPILER_MODE" in
      k1)
        compile_module k1 "$BACKEND_THREADS" || { rm -f "$temporary"; return 1; }
        ;;
      k2)
        compile_module k2 "$BACKEND_THREADS" || { rm -f "$temporary"; return 1; }
        ;;
      auto)
        if ! compile_module k1 "$BACKEND_THREADS"; then
          echo ">>> [$name] K1 compiler failed/timed out; retrying once with bounded K2" >&2
          rm -f "$temporary"
          if ! compile_module k2 "$BACKEND_THREADS"; then
            rm -f "$temporary"
            return 1
          fi
        fi
        ;;
    esac
    rm -rf "$jar"
    mv "$temporary" "$jar"
    # Keep only the newest two cache entries for this module.
    find "$CACHE_DIR" -maxdepth 1 -type f -name "${name}-*.jar" -printf '%T@ %p\n' \
      | sort -nr | awk 'NR>2 {sub(/^[^ ]+ /, ""); print}' | xargs -r rm -f
  fi

  echo ">>> [$name] running"
  java -jar "$jar" || return 1
  echo ">>> [$name] PASS"
}

failed=()
for want in "${selection[@]}"; do
  if idx="$(find_index "$want")"; then
    run_module "${names[$idx]}" "${sources[$idx]}" || failed+=("$want")
  else
    echo "ERROR: unknown module '$want' (use --list)" >&2
    failed+=("$want")
  fi
done

echo
if [[ ${#failed[@]} -eq 0 ]]; then
  echo "ALL TESTS PASSED (${#selection[@]} module(s))"
else
  echo "FAILED module(s): ${failed[*]}" >&2
  exit 1
fi
