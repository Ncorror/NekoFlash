# NekoFlash — Clean Implementation Roadmap

Принцип: строим **вертикальными срезами**, а не полгода пишем абстрактное ядро без железа и UI.

## Phase 0 — A2 evidence и freeze — COMPLETE / PASS

- `vayu` read-only inbound framing gate завершён успешно;
- framing fix принят как proven correctness invariant;
- Legacy и A2 frozen/reference only;
- никаких новых A2 product features.

Примечание: финальный diagnostics bundle gate отсутствует внутри frozen A2 archive; PASS зафиксирован как project status по подтверждению владельца.

## Phase 1 — bootstrap нового repository — CURRENT

- package/application identity;
- Gradle/Compose baseline;
- canonical docs из этого пакета;
- GitHub Actions как authoritative CI/build/test environment;
- Termux как Git/worktree environment без требования локальной Android-сборки;
- structured logging/evidence skeleton;
- clean module boundaries;
- NekoFlash icon/Welcome references;
- минимальный adaptive app shell.

Сразу сделать Home/Target Bar skeleton, чтобы core развивался внутри реального product shell.

## Phase 2 — USB + Target/Session vertical slice

- descriptor discovery;
- permission;
- Target identity;
- SessionGeneration;
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
- typed UI + raw console используют один engine.

Перенести доказанные A2 USBFS ideas без старого host authorization.

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
- brand/theme polish;
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
