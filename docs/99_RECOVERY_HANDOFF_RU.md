# NekoFlash — Recovery Handoff

Если новая сессия потеряла контекст, читать в таком порядке:

1. `00_START_HERE_RU.md`
2. `01_PRODUCT_CHARTER_RU.md`
3. `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`
4. `02_CORE_ARCHITECTURE_RU.md`
5. `05_FINAL_UI_UX_AND_BRAND_RU.md`
6. `12_FOUNDING_DECISIONS_LOG_RU.md`
7. `16_AGENT_OPERATING_PROMPT_RU.md` и `/CLAUDE.md` — операционные правила сессии
8. `17_TERMUX_SETUP_RU.md` — как доставляются и пушатся изменения
9. актуальный roadmap/status нового repository

## Нельзя забывать

- Это всё ещё **NekoFlash**, не новый бренд.
- Новый repo — clean architecture; Legacy/A2 только sources/reference/evidence.
- **NO ARTIFICIAL CAPABILITY RESTRICTIONS.**
- Системные/device ограничения и protocol invariants остаются реальными.
- Product-level hard guards отсутствуют (D031 отменил прежний lock guard). Подтверждённый `LOCKED` — самое сильное предупреждение плюс typed confirmation `yes` в guided UI; `UNKNOWN != LOCKED`; raw console выполняет без prompt; отказ принадлежит устройству; Mi Unlock не затронут.
- GUI и raw tools используют один core.
- ADB сразу multi-stream + generic services.
- Fastboot — один generic transaction engine, включая DATA OUT и DATA IN.
- Sideload сохраняет hardware-proven mutation/correlation protections.
- Mi Unlock — first-class feature поверх generic core.
- Welcome art + NekoFlash icon сохраняются как brand identity.
- Никаких Old/New/Temp/.orig/stubs/obsolete policy docs.
- Hardware-sensitive изменения доказываются на железе.
- Нет профилей «Новичок / Эксперт» или unlock Expert Mode как permission system.
- Canonical docs обновляются одновременно с behavior/architecture/status.
- Termux — рабочий Git/worktree; GitHub Actions — authoritative build/test CI.
- `reference/archives/` — immutable historical snapshots, не production source.
- UI bilingual: English default + Русский; user-facing strings — resources, protocol/raw/evidence data не переводятся ядром.

## Текущий статус

A2 read-only framing gate на `vayu` для commit `a2479b333ee2f25b0bc86a530d948c48a3423a68` завершён: **PASS**.

Phase 0, Phase 1 и Phase 2 — COMPLETE. Legacy/A2 frozen.

Phase 2 закрыта аппаратным прогоном: discovery, permission с выдачей и отказом, идентичность target с уточнением серийным номером, `SessionGeneration`, detach и re-enumeration, захват и освобождение интерфейса, выгрузка evidence. Критерий PASS и полная таблица проверок — `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` §6.10.

Модулей шесть: `:app`, `:core:model`, `:core:diagnostics`, `:core:operation`, `:usb:api`, `:usb:android`. Тестов 131.

Текущая работа — **Phase 3: настоящий ADB foundation + Terminal**. Первым делом переносится доказанный на `vayu` inbound framing invariant из A2 — он обязателен и записан в `00_START_HERE_RU.md`.

Актуальный статус каждого пункта фазы всегда смотреть в чеклисте `09_IMPLEMENTATION_ROADMAP_RU.md`, а не в этом файле: чеклист обновляется тем же changeset, что и код.
