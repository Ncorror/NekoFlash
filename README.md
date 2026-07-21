# NekoFlash

NekoFlash — Android-инструмент для работы с подключённым устройством через ADB и Fastboot. Активный V6 scope состоит из четырёх сценариев:

- терминал ADB/Fastboot;
- быстрая прошивка отдельных популярных разделов;
- ADB Sideload;
- Mi Unlock.

Главный экран сохраняет рабочую верхнюю панель подключения и карточку сведений об устройстве. Их поведение защищено правилами `TOPBAR-001` и `HOMEINFO-001`; разрешены только косметические изменения.

Полный Mi Flash workflow не входит в V6. Его последнее состояние сохранено в Git-ветке `archive/full-miflash-v5.9.17` и теге `v5.9.17-full-miflash`.

## Начать или продолжить в новом чате

Сначала открыть [инструкцию для нового чата](docs/AI_START_HERE.md), затем [единый трекер](PROJECT_MASTER_TRACKER.md). Трекер — единственный источник текущего статуса и ближайшего шага. Для отдельного компактного файла используется `bash scripts/export-chat-context.sh`.

## Основные документы

- [Статус и ближайший план](PROJECT_MASTER_TRACKER.md)
- [Recovery-first план alpha5](docs/RECOVERY_FIRST_PLAN.md)
- [Termux workflow](docs/TERMUX_WORKFLOW.md)
- [История V6](CHANGELOG.md)
- [Сборка](BUILDING.md)
- [Карта документации](docs/README.md)
- [Границы продукта](docs/SCOPE.md)
- [Аудит очистки V6](docs/V6_AUDIT.md)

## Текущий статус

Активная development-версия: `6.0.0-alpha5-dev-nekoflash` (`217`). Alpha4 compile hotfix прошёл maintainer-confirmed green Android CI run `29832274659`. Текущий milestone — Recovery-first Quick Flash; функциональная реализация alpha5 ещё не объявлена завершённой.
