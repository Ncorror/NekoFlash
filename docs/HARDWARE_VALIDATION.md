# Аппаратная проверка NekoFlash V6

Этот файл содержит только sanitised summary. Raw журналы с serial, USB topology и пользовательскими путями в Git не коммитятся. Исторический факт не является автоматическим PASS для текущей V6 сборки.

## Сохранённые исторические факты

| Устройство | Подтверждённый факт до V6 | Статус для V6 |
|---|---|---|
| Xiaomi/POCO `onyx` | Распознавались ADB Recovery и `ADB SIDELOAD`; владелец ранее подтверждал рабочую sideload-сессию | Полный V6 retest required |
| Xiaomi/POCO `vayu` | Выполнялись read-only Fastboot transport/DATA diagnostics | Quick Flash mutation не подтверждена |
| Xiaomi/POCO `marble` | Выполнялись read-only Fastboot transport/DATA diagnostics | Quick Flash mutation не подтверждена |
| Mi Unlock | Владелец сообщал об успешной работе старой реализации | Нужен отдельный V6 audit и новый sanitised report |


## Первый Android smoke test alpha5

Build `6.0.0-alpha5-dev-nekoflash+6ef9da644a82.29860864789` был запущен без подключённого USB-устройства.

Подтверждено:

- приложение стартует и создаёт compact log/trace/session summary;
- active transport session отсутствует, операции не стартовали;
- summary содержит `started=0`, `failed=0`, warnings/errors отсутствуют;
- Recovery-first Quick Flash визуально принят maintainer и зафиксирован как защищённый экран.

Обнаружено до аппаратной прошивки:

- welcome gate слишком высокий;
- Sideload card перегружена памяткой;
- Fastboot DATA diagnostics занимают слишком много места и не различают taps без устройства в compact log;
- Mi Account login возвращал общий результат «отменён» при official unlockApi completion callback.

Исправления находятся в `docs/ALPHA5_HARDWARE_POLISH_PLAN.md` и требуют нового Android smoke/CI evidence. Этот запуск не подтверждает ADB, Fastboot, Sideload, Mi Unlock либо Quick Flash transport.

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
