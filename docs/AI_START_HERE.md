# NekoFlash: старт для нового чата

Этот файл задаёт порядок чтения. Живой статус, последнее evidence и ближайший шаг находятся только в [`PROJECT_MASTER_TRACKER.md`](../PROJECT_MASTER_TRACKER.md).

## Порядок чтения

1. [`PROJECT_MASTER_TRACKER.md`](../PROJECT_MASTER_TRACKER.md) — текущий source state, evidence и следующий шаг.
2. [`SCOPE.md`](SCOPE.md) — границы четырёх функций V6.
3. [`SAFETY_MODEL.md`](SAFETY_MODEL.md) — обязательные safety-инварианты.
4. [`RECOVERY_FIRST_PLAN.md`](RECOVERY_FIRST_PLAN.md) — завершённые Quick Flash slices и открытый hardware gate.
5. [`ALPHA5_HARDWARE_POLISH_PLAN.md`](ALPHA5_HARDWARE_POLISH_PLAN.md) — активные UX/Mi Login задачи.
6. [`HARDWARE_VALIDATION.md`](HARDWARE_VALIDATION.md) — sanitised device evidence и незакрытые проверки.
7. [`ARCHITECTURE.md`](ARCHITECTURE.md) — границы компонентов.
8. [`TERMUX_WORKFLOW.md`](TERMUX_WORKFLOW.md) — push, CI evidence и APK.
9. [`CHANGELOG.md`](../CHANGELOG.md) — история активной линии V6.

## Неподвижные правила

- Не возвращать полный Mi Flash в активную ветку.
- Не менять функциональное поведение `TOPBAR-001`, `HOMEINFO-001`, `HOMEACTIONS-001` без отдельного решения.
- Не менять `cardQuickFlashRecoveryFirst` в hardware-polish: maintainer принял его компоновку.
- Не считать local static/pure checks доказательством Android lint/assemble.
- Не считать Android CI доказательством реальной прошивки.
- Не публиковать raw device/account identifiers, tokens, serial или USB topology.
- Не применять force push, скрытый выбор partition или автоматический mutation retry.
- Перед изменениями сверить ветку, `git status` и evidence из tracker.
- После изменений обновить соответствующий plan/tracker, затем `SHA256SUMS`.

## Запрос для нового чата

```text
Прочитай docs/AI_START_HERE.md, затем PROJECT_MASTER_TRACKER.md.
Считай tracker единственным источником живого статуса.
Сохрани V6 scope, TOPBAR-001, HOMEINFO-001, HOMEACTIONS-001
и защищённый cardQuickFlashRecoveryFirst.
Не возвращай полный Mi Flash и не заявляй PASS без evidence.
Сначала перечисли текущую версию, последний подтверждённый Android CI,
последнее device evidence, открытый gate и ближайший безопасный шаг.
После этого продолжай по активному плану из tracker.
```

## Проверки перед handoff

```bash
python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
python3 scripts/check-ab-safety.py
python3 scripts/check-usb-connectivity.py
python3 scripts/check-flash-safety.py
python3 scripts/check-diagnostic-logging.py
bash scripts/run-tests.sh
```

Android lint/debug/release выполняются отдельно через [`scripts/termux-ci.sh`](../scripts/termux-ci.sh). Компактный handoff создаётся командой:

```bash
bash scripts/export-chat-context.sh
```

Результат: `Download/NekoFlash-chat-context.txt`.
