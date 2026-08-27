# NekoFlash — canonical documentation for the clean new project

Дата фиксации: **2026-08-27**
Статус: **каноническая стартовая база нового clean repository**.

Это не новый бренд и не отдельный продукт «NEXT». Мы продолжаем делать **NekoFlash**, но начинаем его новую кодовую базу с чистой архитектуры.

Начинать с `00_START_HERE_RU.md`.

## Канонические документы

1. `00_START_HERE_RU.md` — иерархия решений и правила восстановления контекста.
2. `01_PRODUCT_CHARTER_RU.md` — что такое NekoFlash и какие продуктовые принципы нельзя незаметно изменить.
3. `02_CORE_ARCHITECTURE_RU.md` — архитектура от USB до UI и предлагаемая структура модулей.
4. `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` — технические hard invariants и граница между correctness и искусственными запретами.
5. `04_CAPABILITY_MATRIX_RU.md` — обязательная финальная поверхность ADB/Fastboot/Recovery/Mi Unlock/Terminal.
6. `05_FINAL_UI_UX_AND_BRAND_RU.md` — Welcome, иконка, фирменный стиль и финальная модель интерфейса.
7. `06_OPERATIONS_STORAGE_LIFECYCLE_RU.md` — операции, cancellation, foreground ownership, SAF и большие файлы.
8. `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` — тестирование, CI и аппаратные доказательства.
9. `08_MIGRATION_FROM_LEGACY_AND_A2_RU.md` — что брать из Legacy/A2, а что не переносить.
10. `09_IMPLEMENTATION_ROADMAP_RU.md` — порядок построения новой кодовой базы.
11. `10_CLEAN_REPOSITORY_AND_DOCS_GOVERNANCE_RU.md` — чистота дерева, ADR и защита от повторного «заражения» старыми правилами.
12. `11_CURRENT_A2_FINAL_GATE_RU.md` — historical record завершённого A2 final framing gate (**PASS**).
13. `12_FOUNDING_DECISIONS_LOG_RU.md` — короткий журнал основополагающих решений.
14. `13_RESEARCH_REFERENCES.md` — официальные protocol/platform references, проверенные перед фиксацией.
15. `14_DIALOG_DECISIONS_CROSSCHECK_RU.md` — контроль: договорённости текущего диалога → канонические документы.
16. `99_RECOVERY_HANDOFF_RU.md` — минимальный handoff для восстановления будущей сессии.

`brand-reference/` содержит реальные Legacy-ресурсы Welcome и иконки как **визуальный reference**, а не готовый контракт пиксель-в-пиксель.

## Источники опыта

Immutable historical snapshots находятся в:

- `../reference/archives/NekoFlash-main-legacy.zip`
- `../reference/archives/NekoFlash-A2-frozen.zip`
- `../reference/archives/NekoFlash-canonical-20260827.zip`

Их integrity фиксируется в `../reference/SHA256SUMS`.

`https://github.com/Ncorror/NekoFlash` остаётся **официальным URL нового clean repository**. Старую Legacy-кодовую базу нельзя идентифицировать по этому текущему URL; её источник для migration/evidence — immutable reference archive.

Legacy и A2 заморожены как **reference/evidence**. Ни один их старый документ не имеет приоритета над этой канонической базой.

## Текущий статус

- Phase 0: **COMPLETE / PASS**.
- Legacy/A2: **FROZEN / REFERENCE ONLY**.
- Current work: **Phase 1 — clean repository bootstrap**.
- Termux: Git/worktree/edit/commit/push.
- GitHub Actions: authoritative build/test/lint/CI environment.
- Canonical docs обновляются вместе с кодом и решениями.
