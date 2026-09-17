#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'repository hygiene: FAIL - %s\n' "$1" >&2
  exit 1
}

mapfile -t tracked < <(git ls-files)

bad_paths="$(printf '%s\n' "${tracked[@]}" | grep -E '(^|/)(build|\.gradle|\.idea)/|\.(jks|keystore|p12|pfx|pem|key)$|(^|/)\.env($|\.)' || true)"
[[ -z "$bad_paths" ]] || { printf '%s\n' "$bad_paths" >&2; fail 'generated or secret-bearing files are tracked'; }

welcome_hash="$(sha256sum app/src/main/res/drawable-nodpi/bg_welcome.jpg | awk '{print $1}')"
[[ "$welcome_hash" == 'd16195d6ab022a4ec8f9686a1d750a4dd83595140e68f718c992df8a0a60a8f7' ]] || fail 'Welcome JPEG hash drifted'

icon_hash="$(sha256sum reference/brand/nekoflash-launcher-reference.png | awk '{print $1}')"
[[ "$icon_hash" == 'fc098f5bea87aea9c2ad0dbddb74ea8277c94145403fb4d76032f36bb0ce1832' ]] || fail 'launcher reference hash drifted'

[[ ! -d usb ]] || fail 'legacy-style top-level usb production tree must not be transplanted into the rewrite'

if [[ -d protocol ]]; then
  protocol_children="$(find protocol -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)"
  [[ "$protocol_children" == 'adb' ]] || fail "protocol production tree drifted; expected only protocol/adb, got: $protocol_children"
fi

printf 'repository hygiene: PASS\n'
