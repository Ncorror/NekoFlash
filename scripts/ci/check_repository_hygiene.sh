#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'repository hygiene: %s\n' "$1" >&2
  exit 1
}

mapfile -t tracked_files < <(git ls-files)

bad_artifacts="$(printf '%s\n' "${tracked_files[@]}" | grep -E '(^|/)([^/]+\.(orig|bak|rej)|[^/]*~)$' || true)"
if [[ -n "$bad_artifacts" ]]; then
  printf '%s\n' "$bad_artifacts" >&2
  fail 'backup/reject artifacts are tracked'
fi

bad_secret_paths="$(printf '%s\n' "${tracked_files[@]}" | grep -E '(^|/)(\.env($|\.)|[^/]+\.(jks|keystore|p12|pfx|pem|key)|id_rsa|id_ed25519)$' || true)"
if [[ -n "$bad_secret_paths" ]]; then
  printf '%s\n' "$bad_secret_paths" >&2
  fail 'secret-bearing file types are tracked'
fi

if ! cmp -s \
  docs/brand-reference/nekoflash-icon-reference.png \
  app/src/main/res/drawable-nodpi/nekoflash_launcher_icon.png; then
  fail 'Phase 1 launcher artwork drifted from the preserved brand reference'
fi

mapfile -t production_sources < <(
  printf '%s\n' "${tracked_files[@]}" \
    | grep -E '(^|/)src/main/.*\.(kt|java)$' || true
)

if (( ${#production_sources[@]} > 0 )); then
  set +e
  stub_scan="$(git grep -nE '(^|[^[:alnum:]_])(FIXME|HACK)([^[:alnum:]_]|$)|TODO[(]|NotImplementedError|UnsupportedOperationException[(]"Not implemented' -- "${production_sources[@]}" 2>&1)"
  stub_status=$?
  set -e
  case "$stub_status" in
    0)
      printf '%s\n' "$stub_scan" >&2
      fail 'production stub or unresolved marker found'
      ;;
    1) ;;
    *)
      printf '%s\n' "$stub_scan" >&2
      fail "git grep failed while scanning production stubs (status $stub_status)"
      ;;
  esac
fi

mapfile -t text_files < <(
  printf '%s\n' "${tracked_files[@]}" \
    | grep -E '\.(kt|kts|java|xml|md|yml|yaml|toml|properties|sh|py|txt)$' || true
)

if (( ${#text_files[@]} > 0 )); then
  set +e
  whitespace_scan="$(git grep -nI -E '[[:blank:]]+$' -- "${text_files[@]}" 2>&1)"
  whitespace_status=$?
  set -e
  case "$whitespace_status" in
    0)
      printf '%s\n' "$whitespace_scan" >&2
      fail 'trailing whitespace found in tracked text files'
      ;;
    1) ;;
    *)
      printf '%s\n' "$whitespace_scan" >&2
      fail "git grep failed while scanning trailing whitespace (status $whitespace_status)"
      ;;
  esac
fi

printf 'repository hygiene: PASS\n'
