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
EXPECTED_MODULES = {':app', ':core:model', ':core:diagnostics', ':protocol:adb', ':transport:usb-android'}
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
        return fail(f'production modules drifted: expected {sorted(EXPECTED_MODULES)}, got {sorted(modules)}')

    if (ROOT / 'usb').exists():
        return fail('legacy-style top-level usb tree must not be transplanted into the rewrite')

    protocol_root = ROOT / 'protocol'
    if protocol_root.exists():
        protocol_children = {p.name for p in protocol_root.iterdir() if p.is_dir()}
        if protocol_children != {'adb'}:
            return fail(f'protocol tree drifted: expected only protocol/adb, got {sorted(protocol_children)}')

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
        'NekoFlash-phase2-debug-${{ github.sha }}',
        'NekoFlash-phase2-verification-${{ github.sha }}',
    ]
    missing_workflow = [token for token in workflow_tokens if token not in workflow]
    if missing_workflow:
        return fail(f'CI/status contract drifted: missing {missing_workflow}')

    roadmap = (ROOT / 'docs/03_ROADMAP.md').read_text(encoding='utf-8')
    if '## Phase 1 — DONE' not in roadmap:
        return fail('roadmap must identify the audited bootstrap as Phase 1 DONE')

    if '## Phase 2 — IN PROGRESS' not in roadmap:
        return fail('roadmap must identify Phase 2 as IN PROGRESS')

    retention_tokens = ['NATIVE_USBFS', 'ASYNC_USB_REQUEST', 'no backend switch/retry']
    missing_retention = [token for token in retention_tokens if token not in roadmap]
    if missing_retention:
        return fail(f'Fastboot DATA retention contract drifted: missing {missing_retention}')

    diagnostics_export = (ROOT / 'core/diagnostics/src/main/kotlin/io/github/ncorror/nekoflash/core/diagnostics/DiagnosticTextExport.kt').read_text(encoding='utf-8')
    usb_screen = (ROOT / 'app/src/main/kotlin/io/github/ncorror/nekoflash/ui/Phase2UsbEvidenceScreen.kt').read_text(encoding='utf-8')
    export_tokens = ['<redacted>', 'formatDiagnosticEvidence']
    missing_export = [token for token in export_tokens if token not in diagnostics_export]
    if missing_export:
        return fail(f'diagnostics export contract drifted: missing {missing_export}')
    screen_export_tokens = ['ActivityResultContracts.CreateDocument("text/plain")', 'Intent.ACTION_SEND', 'bulk-pair interfaces=[']
    missing_screen_export = [token for token in screen_export_tokens if token not in usb_screen]
    if missing_screen_export:
        return fail(f'USB evidence export UI drifted: missing {missing_screen_export}')

    evidence_bundle_tokens = [
        'export-manifest.txt',
        'summary.txt',
        'usb-events.txt',
        'adb-events.txt',
        'usb-descriptors.txt',
        'device-info.txt',
        'app-build.txt',
        'session-info.txt',
    ]
    missing_bundle = [token for token in evidence_bundle_tokens if token not in roadmap]
    if missing_bundle:
        return fail(f'evidence ZIP contract drifted: missing {missing_bundle}')

    session_tokens = [
        'New evidence session',
        'target label',
        'fresh `UsbManager` scan',
        'POCO X3 Pro',
        'POCO F5',
    ]
    missing_session = [token for token in session_tokens if token not in roadmap]
    if missing_session:
        return fail(f'per-target evidence session contract drifted: missing {missing_session}')

    diagnostics_source = (ROOT / 'core/diagnostics/src/main/kotlin/io/github/ncorror/nekoflash/core/diagnostics/Diagnostics.kt').read_text(encoding='utf-8')
    if 'fun clear()' not in diagnostics_source:
        return fail('per-target evidence reset requires InMemoryDiagnosticSink.clear()')

    app_source = (ROOT / 'app/src/main/kotlin/io/github/ncorror/nekoflash/NekoFlashApplication.kt').read_text(encoding='utf-8')
    if 'beginEvidenceSession' not in app_source or 'evidenceSessionId' not in app_source:
        return fail('Application-scoped evidence session owner is missing')

    screen_source = (ROOT / 'app/src/main/kotlin/io/github/ncorror/nekoflash/ui/Phase2UsbEvidenceScreen.kt').read_text(encoding='utf-8')
    if 'refreshDevicesForExport' not in screen_source or 'usb_new_evidence_session' not in screen_source:
        return fail('fresh export scan / new-session UI contract is missing')
    if 'adb_probe_handshake' not in screen_source or 'adbProbe.probe' not in screen_source:
        return fail('minimal ADB CNXN/AUTH probe UI wiring is missing')

    factory_source = (ROOT / 'app/src/main/kotlin/io/github/ncorror/nekoflash/diagnostics/EvidenceBundleFactory.kt').read_text(encoding='utf-8')
    if 'targetLabel' not in factory_source or 'sessionId' not in factory_source:
        return fail('bundle target/session identity fields are missing')

    adb_engine = ROOT / 'protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbHandshake.kt'
    if not adb_engine.is_file():
        return fail('minimal protocol/adb handshake boundary is missing')
    adb_text = adb_engine.read_text(encoding='utf-8')
    for forbidden in ['A_OPEN', 'A_WRTE', 'shell:', 'sync:', 'push:']:
        if forbidden in adb_text:
            return fail(f'Phase 2 ADB handshake slice must not contain service traffic: {forbidden}')
    if 'A_CNXN' not in adb_text or 'A_AUTH' not in adb_text:
        return fail('Phase 2 ADB handshake boundary must contain CNXN/AUTH only')

    bundle_source = ROOT / 'core/diagnostics/src/main/kotlin/io/github/ncorror/nekoflash/core/diagnostics/DiagnosticBundle.kt'
    if not bundle_source.is_file():
        return fail('deterministic DiagnosticBundle writer is missing')

    manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
    if '.evidence-files' not in manifest or '@xml/evidence_file_paths' not in manifest:
        return fail('evidence ZIP FileProvider wiring is missing')

    for relative, expected in EXPECTED_HASHES.items():
        actual = sha256(ROOT / relative)
        if actual != expected:
            return fail(f'{relative} hash drifted: {actual}')

    tests = 0
    for path in ROOT.rglob('*.kt'):
        if '/src/test/' in path.as_posix():
            tests += len(re.findall(r'^\s*@Test\b', path.read_text(encoding='utf-8'), flags=re.MULTILINE))

    print(f'docs consistency: PASS (one status source; 5 modules; Phase 2 USB + ADB handshake boundaries; full evidence export; multi-file evidence ZIP; Native USBFS retained; brand hashes exact; {tests} @Test methods)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
