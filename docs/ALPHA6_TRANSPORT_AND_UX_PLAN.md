# Alpha6 transport truth and guided UX plan

## Status at pause

Active milestone: **V6.0.0-alpha6** (`6.0.0-alpha6-dev-nekoflash`, version code `218`).

Latest confirmed report exact head: `a40262ed615c108229e2e7d9b18bb896b3f628e6` (GitHub Actions run `30847362607`).

Confirmed:

- Android Lint `0 warnings / 0 errors`;
- Debug/Release APK `BUILD SUCCESSFUL`;
- pure/JVM `27/27 PASS`;
- static/documentation/checksum/USB/A-B/flash-safety/diagnostic logging PASS.

Current consolidation package changes documentation, continuity guards and Git/Termux branch workflow only. Runtime behavior is unchanged from the confirmed baseline. The exact current action is owned only by [`PROJECT_STATE.md`](PROJECT_STATE.md); branch transition details are in [`BRANCH_CONSOLIDATION.md`](BRANCH_CONSOLIDATION.md).

## Frozen execution order

1. **Gate 0 — exact-head device smoke.**
2. **Gate 1 — close Slice B with real ADB Sideload.**
3. **Gate 2 — complete/accept Slice C calm UI.**
4. **Gate 3 — Diagnostic Center.**
5. **Gate 4 — Alpha6 stabilization and signed RC.**
6. **Gate 5 — mainline release review and tag.**

Порядок не меняется без maintainer decision, записанного в `PROJECT_STATE.md`.

## Gate 0 — exact-head device smoke

### Readiness

| Task | Scope | Status |
|---|---|---|
| ALPHA6-DEVICE-SMOKE-001 | Console/Logs/privacy/navigation/ADB reconnect/read-only Fastboot | READY_DEVICE_AFTER_NEW_CI |
| ALPHA6-START-001 | Cold start Home + collapsed Console | READY_DEVICE |
| ALPHA6-CONSOLE-001 | Bottom Sheet collapsed/half/expanded, output/input/IME/Back | DONE_CI / DEVICE_RETEST |
| ALPHA6-TOPBAR-001 | Mode/transition/error colors | READY_DEVICE |
| ALPHA6-HOME-001 | Calm Home hierarchy | READY_DEVICE |
| ALPHA6-UNLOCK-UX-001 | Login/read-only preflight UI, no final unlock | READY_DEVICE_READ_ONLY |

### Acceptance

- APK получен из зелёного CI exact handoff head.
- Welcome и Recovery-first card не имеют regression.
- Cold start открывает Home; Console collapsed.
- Half-expanded Console показывает body, current output, PRO input и action «Логи» без clipping.
- Expanded state, IME и Back chain работают в portrait/landscape и после process restart.
- Logs menu/history/share/cleanup работают; sanitized copy не содержит raw IDs/tokens/paths.
- ADB attach/RSA/reconnect/read-only shell и cable detach fail-closed.
- Fastboot только read-only handshake; mutation/data/unlock не запускаются.

Любая ошибка = Gate 0 FAIL. Сохранить screenshot + sanitised summary и остановиться.

## Gate 1 — real ADB Sideload

| Task | Scope | Status |
|---|---|---|
| ALPHA6-SIDELOAD-STATE-001 | Persisted VERIFIED/STALE/transfer/result card | DONE_DEVICE_PARTIAL |
| SIDELOAD-001 | Real transfer, cancel, detach/reconnect, Recovery result | RETEST_REQUIRED |

Acceptance:

1. `VERIFIED` переживает полный process restart.
2. Изменение отдельной копии ZIP переводит state в `STALE` и блокирует transfer.
3. Recovery Sideload peer подтверждён.
4. Real transfer показывает progress/WakeLock и итог operation.
5. Отдельно проверены cancel и cable detach/reconnect.
6. Persistent card фиксирует `SUCCESS`, `FAILED` или `UNKNOWN`.

Gate 1 не запускать в день Gate 0 до явного полного PASS Gate 0.

## Gate 2 — Slice C device acceptance

Закрывает реализованную calm hierarchy без изменения protected functionality:

- `ALPHA6-START-001`;
- `ALPHA6-CONSOLE-001`;
- `ALPHA6-TOPBAR-001`;
- `ALPHA6-HOME-001`;
- `ALPHA6-UNLOCK-UX-001`.

No speculative redesign. Исправление допускается только после воспроизводимого device defect.

## Gate 3 — Diagnostic Center

| Task | Scope | Status |
|---|---|---|
| ALPHA6-DIAGNOSTIC-CENTER-001 | Atomic sanitised diagnostic bundle | PLANNED_AFTER_DEVICE_GATES |
| ALPHA6-DIAGNOSTIC-ERRORS-001 | Structured operation errors/safety blocks | PLANNED |
| ALPHA6-DIAGNOSTIC-TRACE-001 | Raw trace only after explicit opt-in | PLANNED |
| ALPHA6-DIAGNOSTIC-SHARE-001 | Sanitize, hash, atomic share | PLANNED |

Partial archive must never be exposed. Raw trace remains opt-in and outside Git.

## Gate 4 — final Alpha6 stabilization

| Task | Scope | Status |
|---|---|---|
| ALPHA6-STABILIZATION-001 | Lint/toolchain/dependency cleanup | DONE_CI |
| ALPHA6-ANDROID-MATRIX-001 | Android 12–16 runtime regression | PLANNED_FINAL |
| ALPHA6-SIGNED-RC-001 | Signed RC + certificate continuity + install | PLANNED_FINAL |

Open decision: targetSdk 35/36 migration after exact-head device evidence. Do not mix runtime target migration with hardware defect fixes.

## Gate 5 — mainline release review

| Task | Scope | Status |
|---|---|---|
| ALPHA6-MAINLINE-RELEASE-001 | Final reviewed diff, signed RC evidence, release notes/checksums and annotated tag on `main` | BLOCKED_BY_GATES |

Requirements: exact-head main CI, completed hardware gates, signed RC evidence and reviewed release diff. Force push remains forbidden; ordinary reviewed changes are published through short-lived Pull Request branches into protected main.

## Transport/safety invariants

- Expected disconnect after fully written `reboot:*` may be success; ordinary shell/sync/install disconnect remains failure.
- Fastboot DATA evidence is session-bound and never authorizes a later generation.
- Inventory/topology is read-only and fail-closed on missing/ambiguous evidence.
- One confirmation authorizes one concrete partition and one mutation call; no automatic retry.
- File path/URI/size/mtime/hash and topology are rechecked immediately before mutation.
- Locked/unknown bootloader state blocks mutation.
- Full Mi Flash remains outside V6.

## Pause and resume rule

Current state: **READY_FOR_BRANCH_CONSOLIDATION**. First complete the fail-closed fast-forward into the single `main` branch and confirm green main CI. On 4 August 2026, download that exact main run's Debug APK and execute Gate 0 only. Record evidence in [`PROJECT_STATE.md`](PROJECT_STATE.md) and [`HARDWARE_VALIDATION.md`](HARDWARE_VALIDATION.md) before changing task statuses.
