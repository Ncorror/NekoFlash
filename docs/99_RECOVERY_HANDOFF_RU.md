# NekoFlash — Recovery Handoff

Если новая сессия потеряла контекст, порядок чтения задан в `/CLAUDE.md` —
единственном месте, где он живёт. Своего списка здесь больше нет: их было три,
они разошлись, и по одному из них не читались документы из другого.

Этот файл — не оглавление, а короткий свод того, что нельзя забыть, и указатель
на актуальный статус.

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

Phase 0, 1, 2 и 3 — **COMPLETE**. Текущая работа — **Phase 4: ADB professional
services**. Legacy/A2 frozen.

Модулей семь: `:app`, `:core:model`, `:core:diagnostics`, `:core:operation`,
`:usb:api`, `:usb:android`, `:protocol:adb`. Тестов 451 (`@Test` в текущем дереве).

**Что готово, а что нет — только в чеклисте `09_IMPLEMENTATION_ROADMAP_RU.md`.**
Пересказа здесь намеренно нет: он уже расходился с действительностью и устаревал
раньше самого чеклиста. Аппаратные гейты — `07` §6; открытые видны по заголовку
«прогон не проведён».

Порядок фаз читать в `09`, а не по памяти: Fastboot — Phase 5, Recovery и
Sideload — Phase 7.
