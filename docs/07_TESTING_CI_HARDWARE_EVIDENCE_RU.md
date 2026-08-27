# NekoFlash — Testing, CI & Hardware Evidence

## 1. Тесты строятся по слоям

### Pure unit
- codecs/parsers;
- state machines;
- byte accounting;
- advisory classification;
- artifact logic;
- correlation logic.

### Protocol simulation
Fake peer/transport должен уметь моделировать:
- short reads/writes;
- packet fragmentation;
- delayed responses;
- duplicate/out-of-order relevant events;
- stream concurrency;
- malformed frames;
- `INFO/TEXT/FAIL` sequences;
- DATA length mismatch;
- disconnect at every interesting byte/state boundary.

### Integration
- Android USB permission/session ownership;
- foreground operation service;
- persistence/process recreation;
- SAF seekable/non-seekable providers where testable.

### Hardware
Нужен matrix реальных устройств/режимов, потому что USB host stacks и Recoveries имеют vendor quirks.

## 2. Hardware evidence policy

Hardware-sensitive change не считается proven только по unit tests.

Evidence bundle должен содержать:
- app commit/build identity;
- host device identity;
- target identity;
- mode;
- timestamps;
- relevant structured logs/transcript;
- exact byte counts;
- screenshots только как дополнительное подтверждение, не вместо logs;
- PASS criteria.

## 3. Reference devices

### `vayu`
Стабильный reference target для A2 evidence. Доказаны Native USBFS Fastboot DATA transfer/flash, cancel/drain semantics и финальный ADB inbound framing gate — **PASS**.

Frozen A2 archive не содержит финальный diagnostics bundle этого framing gate; статус PASS зафиксирован как project evidence по подтверждению владельца и должен учитываться отдельно от наличия самого bundle.

### `onyx` / POCO F7 / Redmi Turbo 4 Pro
USB-unstable stress patient. Не использовать как единственный reference gate. Quirk должен стать explicit observed capability/advisory, а не глобальным hard-block устройства.

## 4. Regression matrix нового проекта

Минимум постепенно покрыть:
- ADB Android mode;
- ADB Recovery mode;
- concurrent ADB streams;
- shell_v2 и legacy fallback;
- push/pull крупного файла;
- install;
- bootloader Fastboot;
- fastbootd;
- DATA OUT крупного image;
- DATA IN/fetch;
- physical detach during read-only operation;
- detach before and after mutation boundary;
- Sideload normal flow;
- Sideload duplicate requests;
- Sideload verifier reconnect/correlation;
- Mi Unlock staging на безопасных этапах и отдельно destructive/server-sensitive gate.

## 5. CI minimum

**Authoritative build/test environment нового NekoFlash — GitHub Actions.** Termux используется как Git/worktree environment и не обязан быть полноценной Android/Gradle build machine.

Каждый merge в main:
- compile;
- unit/protocol tests;
- static analysis/lint;
- formatting;
- repository hygiene scan;
- no generated binary drift where relevant;
- test report artifact;
- build identity embedded/exportable in diagnostics;
- canonical documentation updated in the same changeset when behavior, architecture, gates or project status changes.

## 5.1. Phase 1 bootstrap CI evidence — 2026-08-28

Первый успешный bootstrap run подтвердил:
- `:core:model`: 3 tests, 0 failures/errors;
- `:core:diagnostics`: 1 test, 0 failures/errors;
- `:core:operation`: 3 tests, 0 failures/errors;
- Android Lint: 0 errors;
- `assembleDebug`: PASS, debug APK собран;
- Gradle Wrapper `9.5.0` сгенерирован authoritative CI и принят для commit только после сверки wrapper JAR SHA-256 с официальным Gradle checksum.

Наличие warning не превращается в failure автоматически: intentional compatibility/tooling warnings документируются, а actionable warnings устраняются отдельными проверяемыми changesets.

## 6. Hygiene CI

CI должен запрещать production tree leftovers:
- `*.orig`;
- `*.bak`;
- `*.rej`;
- obvious `Old*`, `New*`, `Temp*` duplicate implementations;
- TODO/FIXME/HACK в production без явно принятой policy/issue reference;
- `NotImplemented`/throw-stub production paths;
- accidental secrets/private keys beyond intentionally generated local ADB keys handled by secure app storage.

Не превращать это в тупой regex, который ломает legit test fixtures: правила должны быть узкими и объяснимыми.

## 7. Definition of Done для core capability

1. Один production path.
2. Public/internal API определён.
3. Unit/protocol tests PASS.
4. Fault-injection cases покрыты.
5. Peer response не теряется.
6. Cancellation/Unknown semantics определены.
7. Hardware-sensitive path доказан.
8. Diagnostics позволяют разобрать failure без догадок.
9. Документация обновлена одновременно с кодом.
