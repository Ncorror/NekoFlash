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

Mi Account WebView допускает top-level login только под `account.xiaomi.com`. Завершение интерактивного unlockApi login распознаётся только по точному HTTPS callback `unlock.update.miui.com/sts`; произвольные `miui.com`/`xiaomi.com` hosts и другие paths не разрешаются.

После WebView login background clientSign exchange может обратиться только к exact `/sts` на фиксированном наборе официальных unlock hosts для China, Singapore, India, Russia и Europe. `passToken`/`deviceId`/account cookies остаются host-scoped для `account.xiaomi.com` и не отправляются на unlock host. Из `/sts` сохраняются только ожидаемые `serviceToken`, `userId`, `cUserId` и `unlockApi_*` cookies. Ошибка входа должна возвращать конкретную sanitised причину, а не маскироваться как пользовательская отмена.

## Логи

Экспорт по умолчанию sanitised. Raw device logs не входят в source tree.
