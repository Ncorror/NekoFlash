# Границы продукта NekoFlash V6

## Входит в продукт

1. **Terminal** — ADB и Fastboot команды с журналом и экспортом.
2. **Quick Flash** — отдельные образы, прежде всего recovery-related boot chain.
3. **ADB Sideload** — выбор ZIP, передача, отмена и проверка результата recovery.
4. **Mi Unlock** — отдельный управляемый сценарий разблокировки загрузчика.

На главном экране сохраняются верхняя панель подключения и карточка устройства/рабочей папки.

## Quick Flash targets

Основные:

- `recovery`;
- `boot`;
- `init_boot`;
- `vendor_boot`.

Экспертные, скрытые по умолчанию:

- `dtbo`;
- `vbmeta`;
- `vendor_kernel_boot`;
- ручной partition name.

`system`, `vendor`, `product`, `super`, radio/bootloader и полный ROM workflow не входят в обычный Quick Flash.

## Не входит в V6

- полный Mi Flash/Fastboot ROM flasher;
- автоматическая прошивка всех разделов;
- resume полной ROM-прошивки;
- device profile database и inventory history;
- сервер хранения raw hardware logs;
- автоматическая отправка mutation-команд без финального подтверждения.

Полное старое состояние сохранено в `archive/full-miflash-v5.9.17` и `v5.9.17-full-miflash`.
