# NekoFlash

NekoFlash — Android-инструмент для работы с подключённым устройством через USB Host, ADB и Fastboot. Активный V6 scope состоит из четырёх сценариев:

- Terminal ADB/Fastboot;
- Recovery-first Quick Flash отдельных boot-chain образов;
- ADB Sideload;
- Mi Unlock.

Полный Mi Flash/Fastboot ROM workflow не входит в V6. Его последнее состояние сохранено в Git-ветке `archive/full-miflash-v5.9.17` и теге `v5.9.17-full-miflash`.

## Текущий статус

Активная версия: `6.0.0-alpha5-dev-nekoflash` (`217`). Recovery-first Quick Flash реализован по slices A–D, а baseline Slice E прошёл Android CI. Текущая ветка содержит последующие UX/Mi Login исправления, для которых ещё нужны новый Android CI и повторный smoke test на устройстве.

Аппаратные gates Terminal, Sideload и контролируемой Quick Flash остаются открытыми. Android CI не считается доказательством реальной прошивки.

Точный живой статус и ближайший шаг находятся только в [`PROJECT_MASTER_TRACKER.md`](PROJECT_MASTER_TRACKER.md).

## Начать или продолжить работу

1. Открыть [`docs/AI_START_HERE.md`](docs/AI_START_HERE.md).
2. Прочитать [`PROJECT_MASTER_TRACKER.md`](PROJECT_MASTER_TRACKER.md).
3. Следовать активному плану, указанному в tracker.
4. Перед передачей новому чату выполнить `bash scripts/export-chat-context.sh`.

## Основные документы

- [Единый трекер](PROJECT_MASTER_TRACKER.md)
- [Карта документации](docs/README.md)
- [Recovery-first план](docs/RECOVERY_FIRST_PLAN.md)
- [Alpha5 hardware polish](docs/ALPHA5_HARDWARE_POLISH_PLAN.md)
- [Аппаратные доказательства](docs/HARDWARE_VALIDATION.md)
- [Safety model](docs/SAFETY_MODEL.md)
- [Termux workflow](docs/TERMUX_WORKFLOW.md)
- [Сборка](BUILDING.md)
- [История V6](CHANGELOG.md)
