# NekoFlash — Migration from Legacy & A2

Текущий официальный repository нового clean NekoFlash: `https://github.com/Ncorror/NekoFlash`. Этот URL больше не используется как locator исторического Legacy source.

## 1. Роли старых проектов

### Legacy — frozen reference snapshot
Источник: `../reference/archives/NekoFlash-main-legacy.zip`

- функционального охвата;
- реального пользовательского поведения;
- Shell/Terminal идей;
- push/pull/install;
- broad Fastboot/OEM capabilities;
- Mi Unlock;
- Welcome/иконки/брендового характера;
- понимания того, что профессиональный пользователь уже мог делать.

Legacy **не является** архитектурным шаблоном для копирования целиком.

### A2 — frozen reference snapshot
Источник: `../reference/archives/NekoFlash-A2-frozen.zip`

- descriptor-based USB work;
- application-scoped ownership ideas;
- ADB AUTH/transport lessons;
- hardware-proven inbound framing evidence/fix;
- Native USBFS Fastboot DATA OUT;
- exact byte accounting;
- async cancel/drain/poison lessons;
- Sideload request-driven transport;
- Sideload mutation boundary;
- unique-block progress;
- Recovery baseline/correlation/verdict;
- diagnostics/evidence discipline.

A2 **не является** фундаментом нового repo целиком.

## 2. Не переносить из A2 как архитектуру

- stage-era read-only ADB вместо generic services;
- single logical stream assumption;
- shared packet queue с closing чужих stream packets;
- широкую host-side Fastboot authorization policy по смеси `unlocked/max-download-size/partition-size`; из этого не переносится общий permission gate. В новом проекте отдельно принят только узкий `VERIFIED_LOCKED -> block ordinary flash:<partition>` guard;
- process entry authorization gate;
- giant `UsbSessionCoordinator`;
- global `OperationCoordinator` semantics, где pending verification блокирует unrelated operations;
- UI, отражающий временную migration stage как финальный product model;
- stale development checkpoints/recovery prompts;
- `.orig` и другие repository leftovers.

## 3. Не переносить из Legacy вслепую

- giant Activity/ViewModel/protocol classes;
- lifecycle ownership долгих операций экраном;
- whole-file buffering, если есть;
- implicit retry/timeout assumptions;
- protocol code, смешанный с UI/vendor logic;
- любые устаревшие Android platform patterns.

Legacy capability сохраняется, но implementation пересматривается.

## 4. Как переносить доказанный A2 mechanism

Для каждого механизма создаётся migration note/ADR только если решение нетривиально:

- Source: commit/file/evidence.
- What invariant/capability it solves.
- What exact part is reused conceptually or literally.
- New module/API.
- Tests moved/rewritten.
- Hardware evidence linked.
- Old A2-specific policy removed.

## 5. Branding migration

Welcome art и icon переносятся в новый product assets pipeline как references/исходники. Не копировать старый Welcome screen logic целиком.

Новый Welcome сохраняет identity, но permissions/risks model переделывается под новый Product Charter.

## 6. Freeze rule

A2 framing gate завершён: **PASS**.

С этого момента:
- Legacy — frozen functional reference;
- A2 — frozen engineering evidence;
- новые features туда не добавляются;
- critical discovery можно документировать, но implementation идёт только в новый clean repository;
- recovery bundles нового проекта не используют old stage docs как default prompt.
