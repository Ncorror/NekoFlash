# NekoFlash — current project state

Дата: **2026-08-08**.

## Current audit cleanup — 6.0.0-alpha8 build 222

- Version/build identifiers are synchronized to `6.0.0-alpha8` / `222`.
- Local APK scripts fail early when release signing material is absent instead of advertising unsigned release artifacts.
- Recovery bundle restore instructions are generic and no longer embed stale branch names, baseline SHAs or old commit messages.
- Terminal Fastboot size parsing rejects numeric overflow instead of allowing wrapped byte counts.
- Operation brightness reduction is idempotent across repeated operation-state emissions.
- Unreferenced terminal keyboard drawable and unused coroutine imports were removed.

## Runtime profile

Текущий профиль — production-only работа с физическим USB-устройством. Симуляции, dry-run, test-only transports, self-test/qualification flows, persisted authorization evidence и host-side mutation gates удалены.

### ADB

- `shell`, raw services, `push`, package install и `sideload-host` идут напрямую через `AdbProtocol`.
- Sideload не требует предварительного ZIP scan/SHA/sidecar/VERIFIED state.
- Recovery log используется только постфактум для отображения результата.
- Bulk IN читает данные сразу в конечный buffer offset без временного массива на каждый chunk.

### Fastboot

- `flash`, `boot`, `erase`, `format`, `set_active`, `oem`, `flashing` и raw-команды не имеют host-side allow-list/Novice-Pro/High-Risk/typed-confirm gate.
- `unlocked=no`, fastbootd/topology diagnostics и inventory больше не используются как authorization preflight.
- DATA payload не проходит предварительный SHA/MD5 sidecar authorization и download-only qualification.
- Quick Flash и очередь передают выбранный читаемый непустой файл напрямую в настоящий `download -> DATA -> flash/boot/...` path.
- Generic vendor bulk interface допускается к Fastboot handshake; ответ peer определяет пригодность.

### Native USBFS

- backend выполняет реальный Fastboot DATA, а не diagnostic-only transfer;
- два URB используются как bounded pipeline;
- block size стартует с 256 KiB и уменьшается при `ENOMEM` до 16 KiB;
- повтор после `ENOMEM` читает тот же file offset через `pread`;
- RAII владеет fd/UTF chars/heap buffers;
- backend poison сохраняется только если невозможно доказать, что kernel вернул все submitted URB. В этом состоянии повторное использование запрещено ради lifetime корректности памяти.

### UI / operation lifecycle

- UI не выдаёт отдельное разрешение на mutation поверх ADB/Fastboot;
- одновременно выполняется одна USB-операция, чтобы два владельца не читали/писали один endpoint;
- обычная ошибка handshake не помещает USB target в quarantine: повтор подключения разрешён;
- hardware/protocol FAIL возвращается пользователю как фактический ответ устройства.

## Removed artifacts

Удалены `src/test`/`androidTest`-эквиваленты из `tools/`, mocks/stubs, тестовые Python/shell checks, test dependency, production self-tests, DATA qualification/staging evidence, Quick Flash mutation gates и sideload verification state.

## Validation boundary

Сборочная проверка — Gradle lint/assemble. Реальная работоспособность USB/ADB/Fastboot может быть подтверждена только физическим устройством. Static/source validation не подменяет hardware execution.
