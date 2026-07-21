# NekoFlash: старт для нового чата

Этот файл — точка входа для нового чата, ревьюера или нового сеанса разработки. Он не дублирует живой статус проекта: единственный источник текущего состояния и следующего шага — [`PROJECT_MASTER_TRACKER.md`](../PROJECT_MASTER_TRACKER.md).

## Порядок чтения

1. [`PROJECT_MASTER_TRACKER.md`](../PROJECT_MASTER_TRACKER.md) — где остановились, подтверждённые доказательства и ближайшие задачи.
2. [`README.md`](../README.md) — краткое описание продукта и навигация.
3. [`SCOPE.md`](SCOPE.md) — что входит и не входит в V6.
4. [`SAFETY_MODEL.md`](SAFETY_MODEL.md) — обязательные safety-инварианты.
5. [`RECOVERY_FIRST_PLAN.md`](RECOVERY_FIRST_PLAN.md) — план текущего milestone Quick Flash.
6. [`ARCHITECTURE.md`](ARCHITECTURE.md) — компоненты и границы ответственности.
7. [`TERMUX_WORKFLOW.md`](TERMUX_WORKFLOW.md) — публикация, CI, логи и artifacts из Termux.
8. [`CHANGELOG.md`](../CHANGELOG.md) — история активной линии V6.

## Правила продолжения работы

- Не возвращать полный Mi Flash в активную ветку.
- Не менять функциональное поведение верхней панели и Home device info без отдельного решения; их границы защищены `TOPBAR-001` и `HOMEINFO-001`.
- Не считать локальные static/pure-проверки доказательством Android lint/assemble.
- Не считать Android CI доказательством аппаратной прошивки.
- Не применять `force push`, автоматические mutation-retry или скрытый выбор опасного раздела.
- Перед изменениями проверить `git status`, текущую ветку и последний CI evidence из трекера.
- После изменений обновить трекер, changelog при смене версии, документацию feature milestone и `SHA256SUMS`.

## Готовый запрос для нового чата

```text
Прочитай docs/AI_START_HERE.md и затем PROJECT_MASTER_TRACKER.md.
Считай PROJECT_MASTER_TRACKER.md единственным источником текущего статуса.
Сохрани V6 scope, TOPBAR-001, HOMEINFO-001 и HOMEACTIONS-001.
Не возвращай полный Mi Flash и не заявляй PASS без соответствующего доказательства.
Сначала кратко перечисли: текущую версию, последний подтверждённый CI,
открытый milestone, ближайший безопасный шаг и файлы, которые потребуется изменить.
После этого продолжай работу по docs/RECOVERY_FIRST_PLAN.md.
```

## Проверки перед передачей следующему чату

```bash
python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
bash scripts/run-tests.sh
```

Для Android lint/debug/release используется GitHub Actions через [`scripts/termux-ci.sh`](../scripts/termux-ci.sh). Перед открытием нового чата можно создать один компактный handoff-файл:

```bash
bash scripts/export-chat-context.sh
```

Он появится как `Download/NekoFlash-chat-context.txt`.
