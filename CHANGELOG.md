# История изменений NekoFlash

## V6.0.0-alpha5 development baseline — `6.0.0-alpha5-dev-nekoflash` (`217`)

- Реализован Slice C Recovery-first UI: Recovery — главный target, primary и expert actions разделены, Expert Mode выключен по умолчанию.
- UI выбирает image первым, вычисляет SHA-256 и показывает только concrete partitions из `QuickFlashTopologyCandidateBuilder.buildFromInventory`; filename не авторизует target.
- Для A/B пользователь выбирает один точный partition (`_a` или `_b`); вариант `BOTH` и legacy multi-flash queue скрыты из активного Quick Flash.
- Inventory evidence и confirmation связаны одним transport session ID; смена USB/Fastboot-сессии инвалидирует candidate flow.
- Добавлен pure regression module `quick-flash-ui`, static guard защищает порядок Recovery-first, hidden Expert Mode, candidate-only flow и protected Home components.
- Pure runner совместим с bounded K2: `flash-operation-draft` использует top-level entry point и корректно упаковывается как executable test JAR.
- Termux publish разделён с проверками и CI: `termux-publish.sh` теперь выполняет только безопасный импорт source ZIP, commit и push feature-ветки; локальные тесты и Android CI запускаются отдельно.
- Реализован Slice A Recovery-first Quick Flash: pure модели target/candidate/plan и fail-closed validator без Android UI dependencies.
- Реализован Slice B: `QuickFlashTopologyCandidateBuilder` объединяет concrete partition inventory, slot resolver и bounded point-query; filename остаётся только hint.
- Добавлен pure regression module `quick-flash-topology` для A/B, legacy A-only, unknown topology, point-query, Expert/Manual gates, archive и broken-session сценариев.
- Python `__pycache__`/`.pyc` исключены из source tree; Termux publication остаётся быстрым push-only действием без локальной сборки и CI.
- Confirmation payload сериализуется детерминированно и связывает device session, concrete partition, slot, file URI, размер и SHA-256.
- Primary и Expert targets разделены; ручной target требует Expert Mode и точного повторного ввода, а full-ROM/radio/bootloader partitions блокируются.
- Добавлен pure regression module `quick-flash-plan`; один валидный план содержит ровно одну команду `fastboot flash`.
- Зафиксирован воспроизводимый Termux workflow: bootstrap, safe publish и CI collection scripts теперь входят в source tree.
- `termux-ci.sh` ждёт `status=completed`, затем скачивает logs/artifacts; сетевой обрыв больше не трактуется как CI failure.
- Добавлена точка входа `docs/AI_START_HERE.md` для нового чата без дублирования живого статуса.
- Добавлен подробный `docs/RECOVERY_FIRST_PLAN.md` с slices, safety-инвариантами и acceptance criteria alpha5.
- Tracker переведён на Recovery-first milestone и содержит последний maintainer-confirmed green alpha4 CI evidence.
- Функциональная реализация Recovery-first Quick Flash в этом baseline ещё не объявлена завершённой.

## V6.0.0-alpha4 — `6.0.0-alpha4-nekoflash` (`216`)

- Исправлена Android compilation regression после scope cleanup: восстановлены transient-модели `PendingUnlockVerification` и `PendingSideloadVerification`.
- Устранены каскадные `Unresolved reference` в проверке результата Mi Unlock и ADB Sideload.
- Static compile guard теперь требует обе модели и полный набор их полей, чтобы дефект не вернулся.
- Product scope, верхняя панель, Home device info, Terminal, Quick Flash, Sideload и Mi Unlock функционально не изменены.

## V6.0.0-alpha3 — `6.0.0-alpha3-nekoflash` (`215`)

- Проведён полный V6 source audit после удаления Mi Flash.
- Удалена скрытая недоступная страница «Сервис» и её осиротевшие ресурсы.
- Удалены `DeviceProfileManager`, `PartitionInventoryHistory` и связанные вложения отчётов.
- Raw hardware logs, auto-journal и исторические V5 документы удалены из активного source tree; аппаратные факты сведены в проверяемый sanitised summary.
- Диагностический ZIP обновлён до schema `forum-report.v6`.
- Документация переписана под четыре функции V6, планы и release gates синхронизированы.
- `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001` сохранены.
- Сокращённая test matrix остаётся ориентированной на Terminal, Quick Flash, Sideload, Mi Unlock и безопасные логи.

## V6.0.0-alpha2 — `6.0.0-alpha2-nekoflash` (`214`)

- Закреплена карточка информации об устройстве и рабочей папке.
- Добавлены действия «Открыть папку» и «Копировать путь».
- На Home добавлены четыре прямых перехода: Terminal, Quick Flash, ADB Sideload и Mi Unlock.
- Верхняя панель сохранена функционально без изменения transport/state binding.

## V6.0.0-alpha1 — `6.0.0-alpha1-nekoflash` (`213`)

- Полный Mi Flash и относящиеся к нему state machines, tests и CI job удалены из активной ветки.
- Рабочие ADB/Fastboot transports, Terminal, Quick Flash, Sideload, Mi Unlock и логи сохранены.
- Полное предыдущее состояние сохранено в `archive/full-miflash-v5.9.17` и `v5.9.17-full-miflash`.

История до V6 доступна в архивной Git-ветке и теге; она намеренно не дублируется в активной документации.
