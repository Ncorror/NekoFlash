#!/usr/bin/env python3
"""Run one command with a hard process-group timeout.

Unlike a shell-level timeout around a launcher script, this also terminates Java
children that inherited stdout/stderr and could otherwise keep CI/local runners
waiting after the compiler launcher exits.
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import time


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: run-with-timeout.py SECONDS COMMAND [ARG ...]", file=sys.stderr)
        return 2
    try:
        timeout_seconds = float(sys.argv[1])
    except ValueError:
        print(f"invalid timeout: {sys.argv[1]!r}", file=sys.stderr)
        return 2
    if timeout_seconds <= 0:
        print("timeout must be positive", file=sys.stderr)
        return 2

    command = sys.argv[2:]
    process = subprocess.Popen(command, start_new_session=True)
    try:
        return process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        print(
            f"TIMEOUT after {timeout_seconds:g}s: {' '.join(command[:3])}",
            file=sys.stderr,
            flush=True,
        )
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        return 124


if __name__ == "__main__":
    raise SystemExit(main())
