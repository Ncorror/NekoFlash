# USB, ADB и Fastboot в NekoFlash V6

## Владение соединением

`DeviceViewModel` владеет текущим режимом и operation lifecycle. `MainActivity` только отображает состояние и отправляет пользовательские события. Верхняя панель использует этот существующий источник данных.

## ADB

`AdbPacketDispatcher` является единым reader. Записи сериализованы. Поддерживаются terminal shell, sync/package inspection и sideload. Read-only diagnostics не должны запускать mutation services.

## Fastboot

`FastbootProtocol` поддерживает getvar, partition inventory, DATA transfer и отдельные flash operations. Quick Flash использует inventory/slot/preflight; полный ROM workflow отсутствует.

## USB lifecycle

Detach, mode switch или смена USB identity отменяют активную операцию и инвалидируют UI draft. После protocol desynchronization требуется reconnect.

## Ограничения

- один активный operation owner;
- bounded reads/logs;
- no automatic mutation retry;
- no silent partition substitution;
- no raw serial in shared reports.
