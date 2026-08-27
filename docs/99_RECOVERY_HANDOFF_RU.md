# NekoFlash — Recovery Handoff

Если новая сессия потеряла контекст, читать в таком порядке:

1. `00_START_HERE_RU.md`
2. `01_PRODUCT_CHARTER_RU.md`
3. `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`
4. `02_CORE_ARCHITECTURE_RU.md`
5. `05_FINAL_UI_UX_AND_BRAND_RU.md`
6. `12_FOUNDING_DECISIONS_LOG_RU.md`
7. актуальный roadmap/status нового repository

## Нельзя забывать

- Это всё ещё **NekoFlash**, не новый бренд.
- Новый repo — clean architecture; Legacy/A2 только sources/reference/evidence.
- **NO ARTIFICIAL CAPABILITY RESTRICTIONS.**
- Системные/device ограничения и protocol invariants остаются реальными.
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

## Текущий статус

A2 read-only framing gate на `vayu` для commit `a2479b333ee2f25b0bc86a530d948c48a3423a68` завершён: **PASS**.

Phase 0 COMPLETE. Legacy/A2 frozen. Текущая работа — **Phase 1 clean repository bootstrap**.
