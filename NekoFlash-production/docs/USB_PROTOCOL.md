# USB, ADB и Fastboot

## USB discovery

`UsbDeviceInspector` предпочитает canonical/compat интерфейсы, но generic vendor bulk pair тоже допускается к реальному Fastboot handshake. Ошибка handshake не создаёт persistent quarantine: пользователь может повторить подключение.

## ADB

`AdbPacketDispatcher` — единый reader. Записи сериализованы. `shell`, sync/push/install и sideload идут в реальный transport без read-only mutation filter. Sideload требует peer mode `SIDELOAD`, потому что это wire-level режим Recovery, а не host policy.

## Fastboot

`FastbootProtocol` отправляет реальные command packets и DATA. Inventory/getvar используются для отображения и post-verification, не как preauthorization. `flash`, `boot`, `erase`, `format`, `set_active`, `oem`, `flashing` и raw command достигают bootloader; окончательный `OKAY/FAIL` выдаёт устройство.

## Native DATA

Native USBFS — production backend. Pipeline bounded двумя URB; block size адаптируется при `ENOMEM`. При невозможности доказать drain backend не переиспользуется до process restart, чтобы не освободить buffer, которым ещё может владеть kernel.

## Retry rules

Повтор разрешён после обычного connect/handshake failure. Автоматический mid-stream retry Fastboot DATA не выполняется: после неоднозначного OUT нельзя определить, сколько bytes peer уже принял.
