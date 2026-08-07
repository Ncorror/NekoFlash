# NekoFlash

NekoFlash — Android USB Host приложение для прямой работы с физическими устройствами в режимах ADB, ADB Sideload и Fastboot без desktop `adb`/`fastboot` бинарников.

## Production profile

- только реальные Android USB Host/JNI transport paths;
- ADB shell, push, package install и sideload вызывают transport напрямую;
- Fastboot `flash`, `boot`, `erase`, `format`, `set_active`, `oem`, `flashing` и raw-команды не проходят host-side mutation allow-list;
- generic vendor bulk Fastboot-кандидаты допускаются к protocol handshake;
- Native USBFS используется для реального Fastboot DATA, с fallback на `UsbRequest`/bulk transfer;
- тестовые деревья, моки, стабы, self-test/qualification flows и test dependencies удалены.

В production остаются только ограничения корректности протокола и владения памятью: сериализация одной USB-операции, состояние Fastboot session, валидный endpoint/file descriptor, корректный DATA size и отказ от повторного использования native backend, если ядро не подтвердило возврат всех URB.

## Build

```bash
python3 scripts/update-checksums.py
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug assembleRelease
```

Требования и offline/CI сценарии: [BUILDING.md](BUILDING.md).

## Architecture

- [Current architecture](docs/ARCHITECTURE.md)
- [USB / ADB / Fastboot](docs/USB_PROTOCOL.md)
- [Current project state](docs/PROJECT_STATE.md)
- [Code guide](docs/CODE_GUIDE.md)
- [Release process](docs/RELEASE_PROCESS.md)

Исторические alpha5/alpha6 планы и hardware reports сохранены только как архив решений и не определяют текущий runtime behavior.
