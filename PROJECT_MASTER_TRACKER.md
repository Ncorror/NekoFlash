# NekoFlash — единый трекер проекта

Последнее обновление: **2026-07-21**  
Текущий milestone: **V6.0.0 — alpha4 Android compile hotfix**  
Версия: **`6.0.0-alpha4-nekoflash`**  
Version code: **`216`**

## Цель продукта

NekoFlash остаётся компактным Android-инструментом с четырьмя основными сценариями: Terminal, Recovery-first Quick Flash, ADB Sideload и Mi Unlock. Верхняя панель подключения и карточка устройства сохраняются функционально; меняется только косметика.

## Статус задач

| ID | Задача | Статус | Следующее доказательство |
|---|---|---|---|
| SCOPE-001 | Удалить полный Mi Flash из активной ветки | DONE_CODE | Архив V5.9.17 подтверждён, guard не допускает возврата файлов |
| AUDIT-001 | Удалить хвосты, исторический груз и осиротевший код | DONE_CODE | Android CI для alpha4 |
| TOPBAR-001 | Сохранить функциональное поведение верхней панели | DONE_CODE | Android UI smoke test |
| HOMEINFO-001 | Сохранить карточку устройства и рабочей папки | DONE_CODE | Android UI smoke test |
| HOMEACTIONS-001 | Четыре главных перехода на Home | DONE_CODE | Android UI smoke test |
| TERMINAL-001 | Проверить ADB/Fastboot terminal на устройстве | OPEN | Команды read-only и экспорт лога |
| FLASH-001 | Recovery-first Quick Flash | NEXT | Новый упрощённый экран и preflight для популярных разделов |
| SIDELOAD-001 | Подтвердить ADB Sideload в V6 | RETEST_REQUIRED | ZIP transfer, cancel, recovery result |
| UNLOCK-001 | Провести отдельный аудит Mi Unlock | OPEN | Разделить стандартный Fastboot unlock и Xiaomi flow |
| TEST-001 | Сокращённая релевантная test matrix | DONE_LOCAL | 19/19 local; alpha4 Android CI required |
| RELEASE-001 | Signing и аппаратный release gate | OPEN | После стабилизации alpha/beta |

## Что удалено аудитом alpha3

- скрытая недоступная страница «Сервис»;
- `DeviceProfileManager` и `PartitionInventoryHistory`;
- профильные/history-вложения диагностического ZIP;
- raw hardware logs и автоматический build journal из source tree;
- исторические V5 документы из активной ветки;
- осиротевшие ресурсы и устаревшие проверки документации.

## Что намеренно сохранено

- USB/ADB/Fastboot transport;
- terminal и read-only self-test;
- Quick Flash draft, partition inventory, slot resolver и минимальный preflight;
- Sideload и recovery result verifier;
- Mi Account компоненты, которые используются Xiaomi unlock flow;
- sanitization и экспорт диагностических отчётов;
- верхняя панель и карточка устройства.

## Текущий следующий шаг

Проверки scope/safety определены в `docs/SAFETY_MODEL.md`, а canonical documentation guard запускается через `scripts/check-documentation.py`. Архивная база остаётся в `archive/full-miflash-v5.9.17`.

1. Опубликовать alpha4 и подтвердить Android lint/debug/release в GitHub Actions.
2. Сделать `V6.0.0-alpha5`: Recovery-first Quick Flash для `recovery`, `boot`, `init_boot`, `vendor_boot`; дополнительные `dtbo`, `vbmeta`, `vendor_kernel_boot` скрыть в Expert Mode.
3. Провести аппаратный ретест Terminal и Sideload.
4. Отдельно проверить Mi Unlock и удалить всё, что не участвует в подтверждённом сценарии.
5. После стабилизации UI и функций перейти к beta и signing.

Реальная прошивка всегда требует подключённого Fastboot-устройства, существующего раздела, корректного slot, проверенного файла и явного подтверждения. Автоматического повторения mutation-команд нет.
