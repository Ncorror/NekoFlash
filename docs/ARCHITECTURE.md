# Архитектура NekoFlash

## Слои

```text
MainActivity / Android UI
        |
        v
DeviceViewModel / operation owner
        |
        +--> AdbProtocol --------> Android USB Host
        |
        +--> FastbootProtocol ---> NativeUsbfsBackend (JNI/C++ usbfs)
                          \------> UsbRequest / bulk fallback
```

## MainActivity

Отвечает за UI, выбор файла/раздела, terminal parsing и отображение progress/logs. UI не создаёт отдельный mutation ticket, safety profile, typed confirmation или qualification permission.

## DeviceViewModel

Единственный owner lifecycle USB transport. Сериализует длинные операции, переключает ADB/Fastboot sessions, держит foreground/wakelock состояние и публикует progress. Не хеширует payload ради разрешения на flash/sideload и не хранит VERIFIED mutation evidence.

## ADB

`AdbProtocol` реализует handshake/auth, единый packet reader, shell/sync/package operations и `sideload-host`. Mutation services не фильтруются host allow-list. Локальные проверки blank/NUL path и archive traversal относятся к корректности host filesystem, а не к разрешению команды.

## Fastboot

`FastbootProtocol` реализует command state machine, `getvar`, partition inventory, download/DATA, flash/boot/update-super и raw commands. Diagnostics остаются наблюдаемыми данными и не авторизуют mutation. Ответ `FAIL` от bootloader — нормальный protocol verdict и не заменяется host-side запретом.

## Native USBFS

C++ backend получает Linux usbfs fd из Android `UsbDeviceConnection`, использует RAII и bounded URB pipeline. Реальный файл читается `pread` прямо в transfer buffers. При `ENOMEM` block size адаптивно уменьшается. Если submitted URB невозможно доказанно reap/discard, backend остаётся poisoned до process restart: освобождать kernel-owned buffer в таком состоянии нельзя.

## Concurrency

- один owner bulk endpoint за операцию;
- transport transition ждёт завершения активного JNI call перед закрытием `UsbDeviceConnection`;
- coroutine I/O выполняется вне main thread;
- cancellation не означает, что blocking kernel I/O уже вернул управление — закрытие зависит от фактического drain.
