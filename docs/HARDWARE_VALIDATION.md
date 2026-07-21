# Аппаратная проверка NekoFlash V6

Этот файл содержит только sanitised summary. Raw журналы с serial, USB topology и пользовательскими путями в Git не коммитятся. Исторический факт не является автоматическим PASS для текущей V6 сборки.

## Сохранённые исторические факты

| Устройство | Подтверждённый факт до V6 | Статус для V6 |
|---|---|---|
| Xiaomi/POCO `onyx` | Распознавались ADB Recovery и `ADB SIDELOAD`; владелец ранее подтверждал рабочую sideload-сессию | Полный V6 retest required |
| Xiaomi/POCO `vayu` | Выполнялись read-only Fastboot transport/DATA diagnostics | Quick Flash mutation не подтверждена |
| Xiaomi/POCO `marble` | Выполнялись read-only Fastboot transport/DATA diagnostics | Quick Flash mutation не подтверждена |
| Mi Unlock | Владелец сообщал об успешной работе старой реализации | Нужен отдельный V6 audit и новый sanitised report |

## Обязательные V6 проверки

### Terminal

- ADB `devices`/read-only shell;
- Fastboot `getvar product`, `current-slot`, `unlocked`;
- cancel/detach;
- sanitised log export.

### Quick Flash

На восстанавливаемом устройстве:

- inventory и slot resolution;
- Recovery-first target selection;
- файл/размер/SHA-256;
- явное confirmation;
- одна контролируемая операция;
- отсутствие auto retry;
- post-operation reboot только вручную.

### ADB Sideload

- переход recovery в sideload;
- выбор ZIP и integrity;
- progress;
- cancel;
- recovery result;
- reconnect после завершения.

### Mi Unlock

- определить фактический поддерживаемый flow;
- проверить account/session handling;
- подтвердить предупреждение о wipe;
- исключить автоматическую отправку unlock-команды.

## Формат доказательства

Для каждого теста сохраняется sanitised ZIP вне исходного дерева и краткая запись: версия, модель/codename без serial, режим, шаги, результат и SHA-256 проверяемого файла.
