#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_DOCS = [
    'docs/00_START_HERE.md',
    'docs/01_PRODUCT_AND_PROTOCOL_RULES.md',
    'docs/02_ARCHITECTURE.md',
    'docs/03_ROADMAP.md',
    'docs/04_HARDWARE_EVIDENCE.md',
    'docs/05_CAPABILITY_MATRIX.md',
]
EXPECTED_MODULES = {':app', ':core:model', ':core:diagnostics'}
EXPECTED_HASHES = {
    'app/src/main/res/drawable-nodpi/bg_welcome.jpg': 'd16195d6ab022a4ec8f9686a1d750a4dd83595140e68f718c992df8a0a60a8f7',
    'reference/brand/nekoflash-launcher-reference.png': 'fc098f5bea87aea9c2ad0dbddb74ea8277c94145403fb4d76032f36bb0ce1832',
}


def fail(message: str) -> int:
    print(f'docs consistency: FAIL - {message}', file=sys.stderr)
    return 1


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    missing = [path for path in REQUIRED_DOCS if not (ROOT / path).is_file()]
    if missing:
        return fail(f'missing canonical docs: {missing}')

    marker_files = []
    for path in ROOT.glob('docs/**/*.md'):
        if '<!-- CURRENT_STATUS_SOURCE -->' in path.read_text(encoding='utf-8'):
            marker_files.append(path.relative_to(ROOT).as_posix())
    if marker_files != ['docs/03_ROADMAP.md']:
        return fail(f'exactly docs/03_ROADMAP.md must own current status marker; got {marker_files}')

    settings = (ROOT / 'settings.gradle.kts').read_text(encoding='utf-8')
    modules = set(re.findall(r'include\("([^"]+)"\)', settings))
    if modules != EXPECTED_MODULES:
        return fail(f'Phase 1 modules drifted: expected {sorted(EXPECTED_MODULES)}, got {sorted(modules)}')

    if (ROOT / 'protocol').exists() or (ROOT / 'usb').exists():
        return fail('protocol/usb production trees must not exist in Phase 1 bootstrap')

    start_here = (ROOT / 'docs/00_START_HERE.md').read_text(encoding='utf-8')
    handoff_tokens = [
        'production-reset',
        '03_ROADMAP.md',
        'only current phase/status authority',
        'code/tests/docs/evidence drift audit',
        'exact Legacy/A2/current snapshots',
    ]
    missing_handoff = [token for token in handoff_tokens if token not in start_here]
    if missing_handoff:
        return fail(f'new-session recovery contract drifted: missing {missing_handoff}')

    workflow = (ROOT / '.github/workflows/android-ci.yml').read_text(encoding='utf-8')
    workflow_tokens = [
        'branches: [production-reset]',
        './scripts/ci/check_all.sh',
        'NekoFlash-phase1-debug-${{ github.sha }}',
        'NekoFlash-phase1-verification-${{ github.sha }}',
    ]
    missing_workflow = [token for token in workflow_tokens if token not in workflow]
    if missing_workflow:
        return fail(f'CI/status contract drifted: missing {missing_workflow}')

    roadmap = (ROOT / 'docs/03_ROADMAP.md').read_text(encoding='utf-8')
    if '## Phase 1 — IN PROGRESS' not in roadmap:
        return fail('roadmap must identify Phase 1 as IN PROGRESS until the final closeout gate closes')

    for relative, expected in EXPECTED_HASHES.items():
        actual = sha256(ROOT / relative)
        if actual != expected:
            return fail(f'{relative} hash drifted: {actual}')

    tests = 0
    for path in ROOT.rglob('*.kt'):
        if '/src/test/' in path.as_posix():
            tests += len(re.findall(r'^\s*@Test\b', path.read_text(encoding='utf-8'), flags=re.MULTILINE))

    print(f'docs consistency: PASS (one status source; 3 modules; brand hashes exact; {tests} @Test methods)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
