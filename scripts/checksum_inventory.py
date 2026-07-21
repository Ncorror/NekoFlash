#!/usr/bin/env python3
"""Canonical source-file inventory shared by checksum generation and validation."""

from __future__ import annotations

from fnmatch import fnmatch
from pathlib import Path
from typing import Iterator

CHECKSUM_FILE = "SHA256SUMS"
EXCLUDED_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "forum-build",
    "__pycache__",
}
EXCLUDED_ANY_FILE_NAMES = {".DS_Store"}
EXCLUDED_ANY_PATTERNS = {"*.iml"}
EXCLUDED_ROOT_FILES = {
    "local.properties",
    "safety-checks.log",
    "protocol-tests.log",
    "lint_log.txt",
    "debug_build_log.txt",
    "release_build_log.txt",
}
EXCLUDED_ROOT_PATTERNS = {
    "*-test.log",
    "ci-failed*.log",
}


def is_excluded(relative_path: Path) -> bool:
    """Return True for generated/local artifacts that are not repository source."""
    if relative_path.as_posix() == CHECKSUM_FILE:
        return True
    if any(part in EXCLUDED_DIR_NAMES for part in relative_path.parts):
        return True
    if relative_path.name in EXCLUDED_ANY_FILE_NAMES:
        return True
    if any(fnmatch(relative_path.name, pattern) for pattern in EXCLUDED_ANY_PATTERNS):
        return True
    if len(relative_path.parts) == 1:
        name = relative_path.name
        if name in EXCLUDED_ROOT_FILES:
            return True
        if any(fnmatch(name, pattern) for pattern in EXCLUDED_ROOT_PATTERNS):
            return True
    return False


def iter_source_files(root: Path) -> Iterator[Path]:
    """Yield canonical source files in stable repository-relative order."""
    root = root.resolve()
    for path in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if is_excluded(relative):
            continue
        yield path
