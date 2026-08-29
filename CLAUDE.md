# NekoFlash — инструкции для агента

Перед любым изменением прочитай в этом порядке: `docs/00_START_HERE_RU.md` → `docs/01_PRODUCT_CHARTER_RU.md` → `docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` → `docs/02_CORE_ARCHITECTURE_RU.md` → `README.md` (Project status) → `docs/09_IMPLEMENTATION_ROADMAP_RU.md` → `docs/16_AGENT_OPERATING_PROMPT_RU.md`.

Не нарушаемое правило: никаких искусственных ограничений возможностей пользователя — ни profiles «Новичок/Эксперт», ни скрытых capability tiers, ни allowlist/denylist команд. Полная формулировка и единственное принятое исключение (Verified Bootloader Lock Protection) — `docs/01` §3 и `docs/16` §2.

Перед реализацией любого протокольного поведения открой конкретный файл в `reference/archives/` — таблица соответствий в `docs/16` §3. Не додумывай поведение Legacy/A2 по памяти.

Разбор реального кода Legacy/A2 (файлы, строки, антипаттерны) и стандарт идеального кода для новой архитектуры — `docs/15_CLEAN_REBUILD_BLUEPRINT_RU.md`.

При конфликте между этим файлом и `docs/00`–`docs/14` побеждают документы в `docs/`.
