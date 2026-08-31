# NekoFlash — Clean Implementation Roadmap

Принцип: строим **вертикальными срезами**, а не полгода пишем абстрактное ядро без железа и UI.

## Phase 0 — A2 evidence и freeze — COMPLETE / PASS

- `vayu` read-only inbound framing gate завершён успешно;
- framing fix принят как proven correctness invariant;
- Legacy и A2 frozen/reference only;
- никаких новых A2 product features.

Примечание: финальный diagnostics bundle gate отсутствует внутри frozen A2 archive; PASS зафиксирован как project status по подтверждению владельца.

## Phase 1 — bootstrap нового repository — COMPLETE / PASS

Phase 1 closure CI: **VERIFIED / PASS** (2026-08-28). Подтверждены 7 core unit tests, Android Lint `0 errors`, debug APK assembly, checked-in verified Gradle Wrapper `9.5.0`, AGP `9.3.2`, launcher/adaptive/monochrome icon, explicit no-backup/D2D policy и executable repository/localization hygiene checks. Остались только два осознанных informational lint warnings: `targetSdk 36` при `compileSdk 37` и наличие более новой Gradle версии; они не подавляются без отдельного behavior/toolchain review.

- package/application identity;
- Gradle/Compose baseline;
- canonical docs из этого пакета;
- GitHub Actions как authoritative CI/build/test environment;
- Termux как Git/worktree environment без требования локальной Android-сборки;
- structured logging/evidence skeleton;
- clean module boundaries;
- NekoFlash icon/Welcome references;
- минимальный adaptive app shell;
- bilingual resource foundation: English default + Russian `values-ru`, generated per-app locale config, без hardcoded user-facing strings;
- launcher icon wired from the preserved NekoFlash brand reference;
- automatic backup/D2D migration disabled by explicit XML policy for app-managed data;
- repository hygiene and localization parity enforced as CI steps;
- AGP patch baseline updated to `9.3.2`, Gradle intentionally retained at verified `9.5.0`.

Сразу сделать Home/Target Bar skeleton, чтобы core развивался внутри реального product shell.

## Phase 2 — USB + Target/Session vertical slice — CURRENT

- `usb:api` — платформенно-независимый контракт: дескрипторы, классификация интерфейсов, идентичность target, сессии и `SessionGeneration`, политика разрешений. **Готово**;
- манифест: `uses-feature android.hardware.usb.host` и обработка `USB_DEVICE_ATTACHED`. **Готово**;
- `usb:android` — маппер дескрипторов, перечисление устройств, приёмники подключения и отключения, запрос разрешения. **Готово**;
- descriptor discovery;
- permission — политика и Android-механика. **Готово**;
- Target identity;
- SessionGeneration;
- связывание событий USB в состояние сессий. **Готово**;
- claim/release;
- detach/re-enumeration;
- basic diagnostics export;
- UI показывает target/mode/session.

Hardware test сразу.

## Phase 3 — настоящий ADB foundation + Terminal

- CNXN/AUTH;
- single physical reader;
- AdbStreamRouter;
- generic `openService`;
- `shell,v2`;
- legacy fallback;
- interactive PTY shell;
- real terminal UI;
- concurrent stream tests.

Это первый главный product milestone: **USB → ADB → настоящий профессиональный Shell**.

## Phase 4 — ADB professional services

- Sync core;
- push/pull;
- install/install-multiple strategy;
- reboot;
- raw services;
- forward/reverse, если current protocol layer готов;
- file/transfer UI;
- large-file and process-death tests.

## Phase 5 — Fastboot generic engine

- bootloader/fastbootd classification;
- one transaction engine;
- INFO/TEXT/OKAY/FAIL;
- raw command;
- getvar explorer;
- DATA OUT;
- flash/boot/erase/format/set_active/reboot;
- OEM/flashing;
- typed UI + raw console используют один engine;
- Lock state как advisory: подтверждённый `LOCKED` даёт предупреждение и typed confirmation `yes` в guided UI для записи образа; команда не отменяется на стороне хоста, raw console выполняет её без prompt, Mi Unlock не затронут.

Перенести доказанные A2 USBFS ideas без старой широкой host authorization policy. Не расширять узкий verified-lock guard на другие команды по аналогии.

## Phase 6 — Fastboot DATA IN + modern partition workflows

- DATA IN abstraction;
- fetch/upload class;
- partial output semantics;
- logical partitions/fastbootd workflows;
- Quick Flash/Plan поверх public API.

## Phase 7 — Recovery + Sideload

- generic Recovery evidence client поверх нового ADB core;
- request-driven Sideload;
- mutation boundary;
- unique progress;
- baseline/correlation/verdict;
- full Recovery workspace.

Переносить A2 contract как correctness evidence, не как ADB capability restriction.

## Phase 8 — Operation/Artifact hardening

Часть operation engine существует раньше, но здесь закрываем полный production contract:
- foreground connected-device ownership;
- persistence/history;
- process death;
- artifact source/sink;
- non-seekable SAF staging;
- hashes;
- exact progress/rates;
- Unknown outcome UX;
- Operations Center.

## Phase 9 — UX completion

- final adaptive navigation;
- Device Workspace;
- command palette;
- terminal tabs/history;
- supporting diagnostics pane;
- refined Welcome;
- brand/theme polish: замена дефолтной Material-палитры на опорную палитру Legacy (`accent #E9782B` и роли из `05_FINAL_UI_UX_AND_BRAND_RU.md` §2), вывод светлой схемы;
- accessibility/localization foundations.

Важно: UI не «ждёт Phase 9». Каждый предыдущий vertical slice имеет рабочий production UI. Phase 9 — completion/polish, а не первая GUI-интеграция.

## Phase 10 — Mi Unlock

Mi Unlock проектируется заранее как first-class feature, но implementation идёт после стабильного core:
- Legacy protocol/network audit;
- vendor:xiaomi client;
- target/device/account state model;
- Fastboot integration через public core;
- Operations/evidence integration;
- polished dedicated UX;
- реальные error/server states;
- hardware/server-safe test plan.

## Phase 11 — hardening/release

- full hardware matrix;
- performance/memory profiling;
- long transfer endurance;
- background/lifecycle stress;
- accessibility;
- diagnostics privacy review;
- release signing/build reproducibility;
- stale/dead code audit;
- documentation audit;
- recovery bundle verification.

## Commit rule

Каждый changeset должен быть понятным, тестируемым и оставлять **один** production path. Не копить огромный «rewrite everything» branch, который невозможно доказать на железе.
