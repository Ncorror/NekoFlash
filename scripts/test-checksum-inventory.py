#!/usr/bin/env python3
"""Regression test for generated CI/local files not invalidating SHA256SUMS."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from checksum_inventory import is_excluded, iter_source_files

ROOT = Path(__file__).resolve().parents[1]
GENERATED = {
    "safety-checks.log": "static guard transcript\n",
    "protocol-tests.log": "protocol transcript\n",
    "lint_log.txt": "lint transcript\n",
    "debug_build_log.txt": "debug transcript\n",
    "release_build_log.txt": "release transcript\n",
    "usb-detection-test.log": "module transcript\n",
    "ci-failed-local.log": "failure transcript\n",
}


def fail(message: str) -> None:
    print(f"CHECKSUM INVENTORY TEST: FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    for relative in GENERATED:
        if not is_excluded(Path(relative)):
            fail(f"generated root artifact is not excluded: {relative}")

    if not is_excluded(Path("nested/.DS_Store")):
        fail(".DS_Store must be excluded anywhere")
    if not is_excluded(Path("app/module.iml")):
        fail("IDE module files must be excluded anywhere")

    if is_excluded(Path("docs/HARDWARE_VALIDATION.md")):
        fail("canonical hardware-validation summary must remain included")
    if is_excluded(Path("app/src/main/java/Example.kt")):
        fail("source file was excluded")

    created: list[Path] = []
    try:
        for relative, text in GENERATED.items():
            path = ROOT / relative
            if path.exists():
                fail(f"test refuses to overwrite existing file: {relative}")
            path.write_text(text, encoding="utf-8")
            created.append(path)

        inventory = {path.relative_to(ROOT).as_posix() for path in iter_source_files(ROOT)}
        leaked = sorted(set(GENERATED) & inventory)
        if leaked:
            fail("generated artifacts leaked into inventory: " + ", ".join(leaked))

        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "check-documentation.py")],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if result.returncode != 0:
            print(result.stdout, end="", file=sys.stderr)
            fail("documentation guard changed verdict after generated logs appeared")
    finally:
        for path in created:
            path.unlink(missing_ok=True)

    print("CHECKSUM INVENTORY TEST: OK")


if __name__ == "__main__":
    main()
