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
| TERMUX-001 | Раздельные Termux push и CI scripts | DONE_CODE | Push-only self-test и следующий completed GitHub Actions run |
| TOPBAR-001 | Сохранить функциональное поведение верхней панели | DONE_CODE | Android UI smoke test |
| HOMEINFO-001 | Сохранить карточку устройства и рабочей папки | DONE_CODE | Android UI smoke test |
| HOMEACTIONS-001 | Четыре главных перехода на Home | DONE_CODE | Android UI smoke test |
| TERMINAL-001 | Проверить ADB/Fastboot terminal на устройстве | OPEN | Команды read-only и экспорт лога |
| FLASH-001 | Recovery-first Quick Flash | IN_PROGRESS | Slice D: передать подтверждённый QuickFlashPlan в mutation gate |
| SIDELOAD-001 | Подтвердить ADB Sideload в V6 | RETEST_REQUIRED | ZIP transfer, cancel, recovery result |
| UNLOCK-001 | Провести отдельный аудит Mi Unlock | OPEN | Разделить стандартный Fastboot unlock и Xiaomi flow |
| TEST-001 | Сокращённая релевантная test matrix | DONE_CI | Alpha4: 19/19 + Android CI; alpha5 local: 21/21, Android CI pending |
| RELEASE-001 | Signing и аппаратный release gate | OPEN | После стабилизации alpha/beta |

## Что намеренно сохранено

- USB/ADB/Fastboot transport;
- Terminal и read-only self-test;
- Quick Flash draft, partition inventory, slot resolver и минимальный preflight;
- Sideload и recovery result verifier;
- Mi Account компоненты, которые используются Xiaomi unlock flow;
- sanitization и экспорт диагностических отчётов;
- верхняя панель и карточка устройства.

## Локальное evidence alpha5

- Slice A реализован в `QuickFlashPlan.kt`: `QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan`, детерминированный confirmation codec и fail-closed validator.
- Pure module `quick-flash-plan` покрывает primary/expert targets, A-only/A/B concrete candidates, неоднозначность, отсутствие evidence, ручное подтверждение, hash/size и ровно одну mutation-команду.
- Slice B реализован в `QuickFlashTopologyCandidateBuilder.kt`: builder объединяет inventory, slot resolver, bounded point-query и filename classification только как hint.
- Pure module `quick-flash-topology` покрывает legacy A-only, A/B, unknown topology, point-query evidence, expert/manual gates, archive redirect и broken-session fail-closed.
- Python cache исключён через `.gitignore`; Termux publisher остаётся push-only и не запускает локальную сборку либо CI.
- Slice A/B имеют `DONE_CODE` и локальное pure evidence; Android lint/assemble и аппаратная прошивка этим не подтверждены.
- Slice C реализован в `QuickFlashUiPolicy.kt`, `activity_main.xml` и `MainActivity`: Recovery расположен первым, primary/expert targets разделены, Expert Mode выключен по умолчанию.
- Новый UI сначала выбирает image, вычисляет SHA-256, затем показывает только concrete candidates из `QuickFlashTopologyCandidateBuilder.buildFromInventory`; слот `BOTH` не предлагается.
- Legacy multi-flash queue сохранён только для совместимости внутренних ID/state, но скрыт из активного Recovery-first UI.
- Inventory evidence, candidate selector и confirmation связаны одним transport session ID; смена USB/Fastboot-сессии блокирует план fail-closed.

## Текущий следующий шаг

Полный план alpha5 находится в [`docs/RECOVERY_FIRST_PLAN.md`](docs/RECOVERY_FIRST_PLAN.md). Проверки scope/safety определены в `docs/SAFETY_MODEL.md`, а canonical documentation guard запускается через `scripts/check-documentation.py`. Архивная база остаётся в `archive/full-miflash-v5.9.17`.

1. Сверить в Git remote наличие annotated tag `v6.0.0-alpha4` на green commit `90871fb`; source ZIP не содержит `.git` и не доказывает состояние тега.
2. Считать Slice A и Slice B `DONE_CODE`, сохраняя их pure и независимыми от Android UI.
3. Считать Slice C `DONE_CODE`: Recovery-first UI использует только candidates из `QuickFlashTopologyCandidateBuilder`, Expert Mode скрыт по умолчанию, legacy queue не виден.
4. Сохранить `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001`; Android UI smoke test остаётся отдельным evidence gate.
5. Реализовать Slice D mutation gate: передать один подтверждённый `QuickFlashPlan` в flash service, сверить session/file identity и выполнить ровно одну команду без retry и повторного staging.
6. Публиковать без локальной сборки через `scripts/termux-publish.sh`, а CI и сбор логов выполнять отдельно через `scripts/termux-ci.sh`; перед новым чатом создавать `scripts/export-chat-context.sh`.
7. После зелёного Android CI провести отдельный sanitised hardware retest Terminal/Sideload и контролируемый Quick Flash.

Реальная прошивка всегда требует подключённого Fastboot-устройства, существующего раздела, корректного slot, проверенного файла и явного подтверждения. Автоматического повторения mutation-команд нет.
