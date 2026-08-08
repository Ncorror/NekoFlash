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
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug
```

Signed release собирается отдельно при наличии постоянного release key через `./gradlew --no-daemon --warning-mode all assembleRelease` или `bash scripts/build-apk.sh release`.

Требования и offline/CI сценарии: [BUILDING.md](BUILDING.md).

## Architecture

- [Current architecture](docs/ARCHITECTURE.md)
- [USB / ADB / Fastboot](docs/USB_PROTOCOL.md)
- [Current project state](docs/PROJECT_STATE.md)
- [Code guide](docs/CODE_GUIDE.md)
- [Release process](docs/RELEASE_PROCESS.md)

Исторические alpha5/alpha6 планы и hardware reports сохранены только как архив решений и не определяют текущий runtime behavior.


## License and project identity

NekoFlash source code, documentation, build scripts, and ordinary UI resources
are licensed under the Apache License 2.0 unless a file or accompanying notice
states otherwise. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The NekoFlash launcher artwork and welcome-screen artwork are intentionally
excluded from the Apache-2.0 grant; exact paths and reuse terms are listed in
[ASSETS_LICENSE.md](ASSETS_LICENSE.md). Third-party dependency and Android-robot
attributions are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), while
non-bundled reference projects are listed in
[ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md).

NekoFlash is an independent project and is not affiliated with or endorsed by
Google, Xiaomi, MiForge, Termux, or the referenced open-source projects. Product
names and trademarks belong to their respective owners.

Current data-handling behavior is summarized in [PRIVACY.md](PRIVACY.md).
