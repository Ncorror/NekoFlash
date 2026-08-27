# NekoFlash — Dialog Decisions Cross-check

Цель: проверить, что ключевые договорённости текущего разговора не потерялись при пересоздании документации.

| Договорённость | Где закреплена |
|---|---|
| NekoFlash — профессиональный инструмент, возможности нельзя урезать «ради безопасности» | `01_PRODUCT_CHARTER_RU.md`, `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` |
| Реальные системные защиты/locked bootloader/AVB/peer FAIL остаются | `01_PRODUCT_CHARTER_RU.md`, `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` |
| Safety correctness A2 не путать с user capability restrictions | `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` |
| Старый A2 уже слишком противоречив; новый clean repository предпочтительнее бесконечного ремонта | `00_START_HERE_RU.md`, `08_MIGRATION_FROM_LEGACY_AND_A2_RU.md` |
| Legacy и A2 не выбрасывать: использовать как sources/reference/evidence | `00_START_HERE_RU.md`, `08_MIGRATION_FROM_LEGACY_AND_A2_RU.md` |
| Никакого wholesale copy старого `app/` | `00_START_HERE_RU.md`, `08_MIGRATION_FROM_LEGACY_AND_A2_RU.md` |
| ADB нужен полноценный: shell_v2, interactive shell, push/pull/install/raw services | `04_CAPABILITY_MATRIX_RU.md`, `02_CORE_ARCHITECTURE_RU.md` |
| ADB foundation должен быть multi-stream, а не один read-only stream | `02_CORE_ARCHITECTURE_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Fastboot должен быть полным, включая raw/OEM/erase/format/fetch/DATA IN | `04_CAPABILITY_MATRIX_RU.md`, `02_CORE_ARCHITECTURE_RU.md` |
| `unlocked/max-download-size/partition-size` не являются нашей системой разрешений | `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`, `05_FINAL_UI_UX_AND_BRAND_RU.md` |
| Один generic core для GUI, raw terminal, Quick Flash и vendor workflows | `02_CORE_ARCHITECTURE_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Mi Unlock нельзя забыть/спрятать; это first-class feature | `01_PRODUCT_CHARTER_RU.md`, `05_FINAL_UI_UX_AND_BRAND_RU.md`, `09_IMPLEMENTATION_ROADMAP_RU.md` |
| Welcome остаётся и должен сохранять NekoFlash identity | `05_FINAL_UI_UX_AND_BRAND_RU.md`, `brand-reference/` |
| Иконка остаётся частью бренда | `05_FINAL_UI_UX_AND_BRAND_RU.md`, `brand-reference/` |
| Старый Welcome onboarding с mandatory risk acceptance не переносить как permission model | `05_FINAL_UI_UX_AND_BRAND_RU.md` |
| Финальный UI — device-centered professional workspace, но не сухая безликая инженерная панель | `05_FINAL_UI_UX_AND_BRAND_RU.md` |
| Нужен настоящий Terminal, возможно tabs/history | `05_FINAL_UI_UX_AND_BRAND_RU.md`, `04_CAPABILITY_MATRIX_RU.md` |
| Operations — отдельный центр ownership/progress/history/evidence | `06_OPERATIONS_STORAGE_LIFECYCLE_RU.md`, `05_FINAL_UI_UX_AND_BRAND_RU.md` |
| UI будем дорабатывать по ходу; текущая UI vision — направление, не pixel-freeze | `05_FINAL_UI_UX_AND_BRAND_RU.md` |
| Никаких хвостов, заглушек, `.orig`, Old/New/Temp и параллельных engines | `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md` |
| Документация должна быть малой, канонической и не спорить сама с собой | `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md` |
| Старые recovery/checkpoint docs не должны «заражать» новый проект | `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md`, `99_RECOVERY_HANDOFF_RU.md` |
| A2 framing hardware gate на vayu завершён PASS; Phase 0 закрыта | `11_CURRENT_A2_FINAL_GATE_RU.md`, `09_IMPLEMENTATION_ROADMAP_RU.md` |
| Legacy/A2 frozen, новые features — только в новом repo | `08_MIGRATION_FROM_LEGACY_AND_A2_RU.md`, `09_IMPLEMENTATION_ROADMAP_RU.md` |
| Нет профилей «Новичок / Эксперт» и unlock Expert Mode как системы разрешений | `01_PRODUCT_CHARTER_RU.md`, `05_FINAL_UI_UX_AND_BRAND_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Документация обновляется вместе с кодом/архитектурой/status | `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md`, `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Termux — Git/worktree; GitHub Actions — authoritative build/test CI | `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md`, `09_IMPLEMENTATION_ROADMAP_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Frozen Legacy/A2/canonical ZIP живут только в immutable `reference/archives/` | `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md`, `12_FOUNDING_DECISIONS_LOG_RU.md` |
| Новый проект строить вертикальными hardware-tested slices | `09_IMPLEMENTATION_ROADMAP_RU.md`, `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` |

## Что сознательно не «заморожено навечно»

Мы не фиксируем сейчас до пикселя:
- точное расположение каждой кнопки;
- финальный набор bottom-nav destinations на каждом window size;
- конкретные версии Compose/Navigation dependencies;
- точные цвета/spacing tokens;
- окончательное внутреннее имя каждого Gradle module.

Эти вещи можно улучшать в ходе реализации, **если не нарушаются Product Charter, capability surface, protocol invariants и brand identity**.
