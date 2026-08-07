# Руководство по production-коду NekoFlash

## Границы ответственности

| Компонент | Ответственность |
|---|---|
| `MainActivity` | UI, terminal parsing, выбор payload/target, вызов ViewModel |
| `DeviceViewModel` | USB lifecycle, одна активная операция, observable state |
| `AdbProtocol` | ADB auth/dispatcher/shell/sync/sideload |
| `FastbootProtocol` | Fastboot command/DATA state machine |
| `NativeUsbfsBackend` + C++ | реальный native DATA transport и URB lifetime |
| `DiagnosticLogStore` / sanitizer | bounded diagnostics/export |
| Xiaomi account classes | HTTPS login/token exchange для Mi Unlock |

## Правила

- Не добавлять mocks/stubs/fakes, dry-run/simulation и test-only DI в production path.
- Не добавлять host-side allow-list/authorization поверх реальных ADB/Fastboot mutation commands.
- Diagnostics/inventory могут предупреждать, но не должны решать, разрешена ли команда.
- Не делать полный hash/ZIP scan/copy только ради permission ticket перед USB transfer.
- Не запускать два reader/writer owner на одном USB endpoint.
- Не закрывать Java USB connection, пока blocking native call может владеть submitted URB.
- Не retry-ить неоднозначный mid-stream OUT автоматически: повтор может дублировать часть DATA.
- Проверять только техническую валидность локального payload/wire syntax и состояние реального transport.

## Performance

- горячие ADB reads пишут сразу в итоговый buffer;
- Fastboot async path читает `FileChannel` в `DirectByteBuffer`;
- C++ USBFS читает через `pread` в URB buffers и использует bounded pipeline;
- избегать повторного полного чтения больших firmware files;
- coroutine blocking I/O выполняется на `Dispatchers.IO`/transport scope, UI — на main.

## Handoff

```bash
python3 scripts/update-checksums.py
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug assembleRelease
```

Физический USB path проверяется только на реальном устройстве; source/lint/assemble не являются hardware verdict.
