# NekoFlash — единый трекер проекта

Последнее обновление: **2026-07-21**  
Текущий milestone: **V6.0.0 — alpha5 Recovery-first Quick Flash**  
Версия: **`6.0.0-alpha5-dev-nekoflash`**  
Version code: **`217`**

## Новый чат

Начать с [`docs/AI_START_HERE.md`](docs/AI_START_HERE.md), затем читать этот файл как единственный источник живого статуса. Не восстанавливать старый Mi Flash scope и не переносить статус из исторических документов.

## Последнее подтверждённое доказательство

- Предыдущая стабильная development-версия: `6.0.0-alpha4-nekoflash` (`216`).
- GitHub Actions run: `29832274659`.
- Commit из run: `90871fb`.
- Результат: maintainer confirmed all workflow steps green.
- Alpha4 исправила отсутствующие `PendingUnlockVerification` и `PendingSideloadVerification`; regression guard сохранён.
- Перед началом alpha5 этот точный alpha4 commit следует зафиксировать annotated tag `v6.0.0-alpha4`.

## Цель продукта

NekoFlash остаётся компактным Android-инструментом с четырьмя основными сценариями: Terminal, Recovery-first Quick Flash, ADB Sideload и Mi Unlock. Верхняя панель подключения и карточка устройства сохраняются функционально; меняется только косметика.

## Статус задач

| ID | Задача | Статус | Следующее доказательство |
|---|---|---|---|
| SCOPE-001 | Удалить полный Mi Flash из активной ветки | DONE_CODE | Архив V5.9.17 и guard не допускают возврата файлов |
| AUDIT-001 | Удалить хвосты, исторический груз и осиротевший код | DONE_CI | Alpha4 Android CI green |
| ALPHA4-CI | Подтвердить compile hotfix | DONE_CI | Tag `v6.0.0-alpha4` на green commit |
| CONTEXT-001 | Самодостаточный handoff для нового чата | DONE_CODE | `docs/AI_START_HERE.md` и этот tracker входят в source ZIP |
| TERMUX-001 | Воспроизводимые bootstrap/publish/CI scripts | DONE_CODE | Self-test в чистом Termux и следующий completed run |
| TOPBAR-001 | Сохранить функциональное поведение верхней панели | DONE_CODE | Android UI smoke test |
| HOMEINFO-001 | Сохранить карточку устройства и рабочей папки | DONE_CODE | Android UI smoke test |
| HOMEACTIONS-001 | Четыре главных перехода на Home | DONE_CODE | Android UI smoke test |
| TERMINAL-001 | Проверить ADB/Fastboot terminal на устройстве | OPEN | Команды read-only и экспорт лога |
| FLASH-001 | Recovery-first Quick Flash | IN_PROGRESS | Slice A: pure model и fail-closed tests |
| SIDELOAD-001 | Подтвердить ADB Sideload в V6 | RETEST_REQUIRED | ZIP transfer, cancel, recovery result |
| UNLOCK-001 | Провести отдельный аудит Mi Unlock | OPEN | Разделить стандартный Fastboot unlock и Xiaomi flow |
| TEST-001 | Сокращённая релевантная test matrix | DONE_CI | 19/19 плюс Android lint/debug/release для alpha4 |
| RELEASE-001 | Signing и аппаратный release gate | OPEN | После стабилизации alpha/beta |

## Что намеренно сохранено

- USB/ADB/Fastboot transport;
- Terminal и read-only self-test;
- Quick Flash draft, partition inventory, slot resolver и минимальный preflight;
- Sideload и recovery result verifier;
- Mi Account компоненты, которые используются Xiaomi unlock flow;
- sanitization и экспорт диагностических отчётов;
- верхняя панель и карточка устройства.

## Текущий следующий шаг

Полный план alpha5 находится в [`docs/RECOVERY_FIRST_PLAN.md`](docs/RECOVERY_FIRST_PLAN.md). Проверки scope/safety определены в `docs/SAFETY_MODEL.md`, а canonical documentation guard запускается через `scripts/check-documentation.py`. Архивная база остаётся в `archive/full-miflash-v5.9.17`.

1. Зафиксировать green alpha4 commit annotated tag `v6.0.0-alpha4`.
2. Создать рабочую ветку `feature/recovery-first-quick-flash` от `main` после тега.
3. Реализовать Slice A: pure Kotlin модели `QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan` и fail-closed validator.
4. Добавить tests и guards до изменения Android UI.
5. Затем выполнить Slice B topology resolver и только после него Slice C UI.
6. Публиковать через `scripts/termux-publish.sh`, CI и сбор логов выполнять через `scripts/termux-ci.sh`; перед новым чатом создавать `scripts/export-chat-context.sh`.
7. После зелёного Android CI провести отдельный sanitised hardware retest Terminal/Sideload и контролируемый Quick Flash.

Реальная прошивка всегда требует подключённого Fastboot-устройства, существующего раздела, корректного slot, проверенного файла и явного подтверждения. Автоматического повторения mutation-команд нет.
