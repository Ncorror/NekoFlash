# История изменений NekoFlash

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
