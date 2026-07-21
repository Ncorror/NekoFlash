#!/usr/bin/env python3
"""Regenerate deterministic repository SHA256SUMS."""

from __future__ import annotations

import hashlib
from pathlib import Path

from checksum_inventory import iter_source_files

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "SHA256SUMS"


def main() -> None:
    lines = []
    for path in iter_source_files(ROOT):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  ./{path.relative_to(ROOT).as_posix()}")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"checksums: {len(lines)} files -> {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
