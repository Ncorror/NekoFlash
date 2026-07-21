# Safety model NekoFlash V6

Safety V6 должен быть небольшим и непосредственно связанным с реальными функциями.

## Перед Quick Flash

Обязательны:

- подключённое устройство в Fastboot;
- стабильная USB session;
- подтверждённое состояние загрузчика, когда оно доступно;
- существующий partition target;
- корректное A/B разрешение;
- читаемый файл, размер и SHA-256;
- показ пользователю файла и итогового раздела;
- явное финальное подтверждение.

Recovery-first UI показывает только concrete candidates из read-only inventory/topology builder. Expert targets скрыты до явного включения, а multi-flash queue и выбор обоих слотов не доступны в активном UI.

При detach, смене режима или устройства draft инвалидируется. Recovery-first UI фиксирует transport session до хеширования и не разрешает использовать inventory/candidate evidence после смены сессии. Mutation-команда не повторяется автоматически после ошибки.

## Recovery topology

Приложение не предполагает наличие отдельного `recovery`. Оно проверяет inventory и предлагает `recovery`, `boot`, `init_boot` или `vendor_boot`; окончательный target всегда виден пользователю.

## Expert targets

`vbmeta`, `dtbo`, `vendor_kernel_boot` и ручное имя раздела скрыты по умолчанию. Параметры отключения verification не добавляются автоматически. Radio/bootloader/persist-разделы не предлагаются.

## Sideload

Передача разрешена только в подтверждённом ADB Sideload mode. Отмена должна завершать текущий transfer без автоматического restart. Результат установки берётся из recovery protocol/log, а не предполагается по окончанию upload.

## Unlock

Разблокировка требует отдельного предупреждения о сбросе данных и ручного подтверждения. Стандартный Fastboot unlock и Xiaomi account/server flow должны быть явно разделены.

## Логи

Экспорт по умолчанию sanitised. Raw device logs не входят в source tree.
