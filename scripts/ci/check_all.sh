#!/usr/bin/env bash
set -euo pipefail

./scripts/ci/check_repository_hygiene.sh
python3 ./scripts/ci/check_localization.py
python3 ./scripts/ci/check_docs_consistency.py
./gradlew test lint assembleDebug --no-daemon --stacktrace
