# Аппаратная проверка NekoFlash V6

Этот документ содержит только reviewed sanitised summary. Raw logs с serial, USB topology, пользовательскими путями, account ID, cookie values и tokens в Git не коммитятся. Исторический факт не является PASS для текущей сборки.

## Историческая база до V6

| Устройство/flow | Подтверждённый факт | Статус V6 |
|---|---|---|
| Xiaomi/POCO `onyx` | Ранее распознавались ADB Recovery и ADB Sideload | Полный V6 retest required |
| Xiaomi/POCO `vayu` | Ранее выполнялись read-only Fastboot DATA diagnostics | Quick Flash mutation не подтверждена |
| Xiaomi/POCO `marble` | Ранее выполнялись read-only Fastboot DATA diagnostics | Quick Flash mutation не подтверждена |
| Mi Unlock | Владелец сообщал об успешной работе старого flow | Требуется отдельный V6 audit |

## Android smoke evidence alpha5

### Smoke 1 — baseline UI без USB

Build: `6.0.0-alpha5-dev-nekoflash+6ef9da644a82.29860864789`.

Подтверждено:

- приложение запускается и создаёт compact log/trace/session summary;
- transport session отсутствует, operation не стартует;
- Recovery-first Quick Flash визуально принят maintainer и зафиксирован как защищённый экран.

Обнаружено:

- перегруженные welcome/Sideload/Fastboot DATA panels;
- диагностические taps без устройства не различались в compact log;
- Mi Account completion возвращал общий cancellation result.

### Smoke 2 — hardware-polish без USB

Build: `6.0.0-alpha5-dev-nekoflash+0747c4ec72e3.29866798716`.

Подтверждено:

- Fastboot DATA main/advanced taps оставляют точный no-device отказ;
- импорт и анализ ZIP работают без transport session;
- интерактивный Xiaomi login доходит до получения account identity.

Обнаружено:

- background token exchange блокировал официальный `https://unlock.update.miui.com/sts`;
- Sideload pre-verify note показывал misleading green success icon.

### Smoke 3 — `/sts` exchange

Build: `6.0.0-alpha5-dev-nekoflash+8d9923ec0878.29870485300`.

Подтверждено:

- Xiaomi login завершён;
- unlockApi service session и ожидаемые service cookies получены;
- следующий Mi Unlock action корректно остановлен из-за отсутствия Fastboot-устройства;
- смена data-center preference журналируется без account secrets.

Остаточный дефект:

- на первом проходе UI иногда показывал stale blocked-host banner;
- после restart сохранённая session использовалась успешно;
- причина локализована: поздний `onPageFinished` повторно обрабатывал уже завершённый `/sts` callback.

Текущий source содержит first-pass race fix. Его device PASS ещё не зафиксирован: нужен fresh login без restart и без banner.

## Открытые V6 gates

### Welcome/Sideload smoke

- welcome panel: прозрачность/контур и положение без изменения permission logic;
- Sideload: до verify нет зелёного success-status;
- Import/Verify geometry и тексты остаются читаемыми на целевых размерах экрана.

### Terminal

- ADB read-only shell;
- Fastboot `getvar product`, `current-slot`, `unlocked`;
- cancel/detach;
- sanitised log export.

### ADB Sideload

- recovery sideload mode;
- выбор ZIP и integrity;
- progress и cancel;
- recovery result;
- reconnect после завершения.

### Recovery-first Quick Flash

На восстанавливаемом устройстве:

- inventory и slot resolution;
- один concrete target;
- файл, размер и SHA-256 до confirmation;
- одна контролируемая flash operation;
- отсутствие auto retry;
- reboot только отдельным ручным действием.

### Mi Unlock

- fresh login без restart и stale banner;
- разделить standard Fastboot unlock и Xiaomi account/server flow;
- подтвердить wipe warning и typed/manual confirmation;
- исключить автоматическую отправку unlock-команды.

## Формат нового доказательства

Для каждого теста сохраняется sanitised ZIP вне source tree и краткая запись: version/build ID, модель/codename без serial, режим, шаги, результат и SHA-256 проверяемого файла. Cookie values, tokens, account ID и raw USB identifiers не включаются.
