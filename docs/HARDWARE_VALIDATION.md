# Аппаратная проверка NekoFlash V6

Этот документ содержит только reviewed sanitised summary. Raw logs с serial, USB topology, пользовательскими путями, account ID, cookie values и tokens в Git не коммитятся. Исторический факт не является PASS для текущей сборки.


## Current alpha10 validation boundary

Current source state: `6.0.0-alpha10` / `versionCode 228`. This audit refreshed static/source evidence only. Full Android Lint/Debug/Signed Release verdict and physical USB/ADB/Fastboot evidence must be produced on an environment with Android SDK/Gradle cache and real devices.

Do not treat archived CI runs below as proof for the current alpha10 exact source. They remain historical evidence for earlier heads only. Before APK publication, collect fresh exact-head `lintDebug`, `assembleDebug`, signed `assembleRelease`, certificate continuity and hardware smoke evidence.

## Историческая база до V6

| Устройство/flow | Подтверждённый факт | Статус V6 |
|---|---|---|
| Xiaomi/POCO `onyx` | Ранее распознавались ADB Recovery и ADB Sideload | Полный V6 retest required |
| Xiaomi/POCO `vayu` | Ранее выполнялись read-only Fastboot DATA diagnostics | Quick Flash mutation не подтверждена |
| Xiaomi/POCO `marble` | Ранее выполнялись read-only Fastboot DATA diagnostics | Quick Flash mutation не подтверждена |
| Mi Unlock | Владелец сообщал об успешной работе старого flow | Требуется отдельный V6 audit |

## Slice B.1 — logs menu/privacy Android CI PASS

Exact code head: `d90ff154820eb5878114b15dd2f685c0b34dd6ce`.  
GitHub Actions run: `30397037090`, status **SUCCESS**, branch `feature/recovery-first-quick-flash`.

Confirmed by CI:

- static project, documentation, checksum, A/B, USB-connectivity, flash-safety and diagnostic-logging guards — **PASS**;
- pure/JVM policy/protocol matrix — **26/26 PASS**, including `logs-menu` and `sanitized-log-share`;
- Android `lintDebug` — **PASS**, `136` warnings and `0` errors;
- Debug APK and unsigned Release APK — **BUILD SUCCESSFUL**;
- CMake — **PASS** for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`;
- APK/report artifacts were uploaded. Release signing was not configured.
- Debug APK SHA-256: `847c8f3ef5e4c56a6a66948009613f848d1418bda8606ca3a3d89395105b83c9`.
- Unsigned Release APK SHA-256: `7bf0a5a765899e0995dd6d24285c1e0544d792bd661dedabdac370383d9b1536`.
- Report artifact digest: `a6f0553854aedf3bd33caa5f23c4ed072750406798b31a3076e9e965c8f9c3eb`; APK artifact digest: `3bdfd2018fc7c8d624a97fc5fb333ea1c5b0d37a63c8cdb040278db0a8e4f21f`.

This closes the compile/CI gate for the global logs menu and broader device-ID sanitisation. It does not replace device UI/share validation: the next safe evidence is a smoke test of all log-menu actions and a sanitized export containing no raw IMEI/ICCID/Android ID/CPUID/UFS/fingerprint UID.

## Alpha6 Slice B — CI PASS, guided DATA hardware PASS, Sideload preflight PASS

Build: `6.0.0-alpha6-dev-nekoflash+1c90383a73f8.30301994393`.  
Exact head: `1c90383a73f8ec29ba19bf6a1a2f781d304118f5`.  
GitHub Actions run: `30301994393`, status **SUCCESS**.  
Device: `POCO X3 Pro / vayu`; host: Android 16.  
Дата расширенной сессии: 2026-07-28.

Reviewed evidence: compact log, protocol trace и `diagnostic-session.v3` summary. Raw-файлы остаются вне Git. Сводка сессии: `11` operations started, `8` succeeded, `3` failed, `0` cancelled, `0` verification-pending; `50` success messages, `8` warnings, `0` error messages и `0` safety blocks. Эти счётчики сохранены как факт сессии, но hardware verdict строится по явным protocol/result lines: часть non-success operation outcomes относится к разрыву долгоживущего interactive shell и пользовательским действиям, а не к ложному transport PASS.

### ADB и переключения режимов

Подтверждено:

- первая ADB-сессия создала локальный RSA-ключ приложения; последующие подключения использовали сохранённый ключ и успешно авторизовались;
- `shell_v2` обнаружен, single-reader dispatcher запускался после каждого ADB reconnect;
- интерактивный shell работал; неизвестная команда вернула обычный shell exit `127`, не превращая ошибку команды в transport failure;
- большой вывод `getprop` прошёл через interactive shell; физическое отключение кабеля закрыло shell и dispatcher fail-closed, без ложного reboot-success;
- `adb reboot bootloader` принят и привёл к bootloader Fastboot;
- `adb reboot fastboot` принят и привёл к userspace Fastboot (`fastbootd`);
- legacy alias `adb reboot systems` нормализован в `reboot:` и вернул устройство из ADB Sideload в обычный Android;
- после каждого ожидаемого one-way reboot WakeLock освобождался, а reconnect создавал новую transport generation.

Открыто: malformed/partial ADB packet остаётся покрыт pure regression, но отдельного device/fault-injection evidence в этой сессии нет.

### Bootloader Fastboot и guided DATA

Первая Fastboot-сессия была bootloader Fastboot (`is-userspace=no`):

- handshake: `product=vayu`;
- загрузчик: `unlocked=no`, `secure=yes`; mutation оставалась заблокированной;
- slot variables были unsupported/empty и трактовались как legacy A-only compatibility без создания `_a`/`_b`;
- reported max download size: `768 MiB`.

Guided Slice B actions:

1. Main recommended test, `32 MiB`, UsbRequest — **PASS**, финальный `OKAY`, средняя скорость около `23.34 MB/s`.
2. Повтор main test, `32 MiB`, UsbRequest — **PASS**, финальный `OKAY`, средняя скорость около `23.29 MB/s`.
3. Selected-image qualification: `recovery.img`, `128 MiB` — source/staged SHA-256 совпал, internal staging завершён, UsbRequest DATA — **PASS**, финальный `OKAY`, средняя скорость около `23.65 MB/s`.
4. Guided comparison, одинаковый `32 MiB` payload:
   - UsbRequest — **PASS**, около `23.17 MB/s`;
   - synchronous `bulkTransfer` — **PASS**, около `3.65 MB/s`;
   - итог: оба штатных транспорта получили финальный `OKAY`.

Во всех четырёх действиях выполнялся только `download → DATA payload → final response`. Команды `flash`, `boot`, `stage`, `update-super` и `unlock` не отправлялись. При disconnect private staging cleanup сообщил `deleted=1`, `failed=0`. После новой Fastboot generation прошлые DATA results корректно отображались как history from another session и не считались current-generation authorization.

### Fastbootd и ADB Sideload preflight

После `adb reboot fastboot` устройство было корректно определено как userspace Fastboot:

- `is-userspace=yes`;
- `super-partition-name=super`;
- max download size: `256 MiB`;
- текущая generation не наследовала size-aware authorization от предыдущей bootloader Fastboot-сессии;
- пользовательское отключение завершило generation fail-closed.

Recovery peer затем определился как `ADB SIDELOAD` по banner/mode. Это подтверждает transition и reconnect path, но реальная ZIP-передача в этой сессии не запускалась. Команда возврата `adb reboot systems` была принята и устройство снова подключилось как обычный ADB device.

### Sideload ZIP verification UI

Отдельным smoke в том же exact build подтверждено:

- ZIP импортируется без transport session и проходит полный scan;
- карточка показывает имя, размер, `VERIFIED` и число entries, а полный path/hash dump остаётся только в Details;
- повторные проверки дают тот же package fingerprint и завершаются `SUCCESS`;
- WakeLock освобождается после каждой проверки;
- попытка отправки без ADB соединения fail-closed завершается явной ошибкой, а не ложным PASS.

Не подтверждено:

- persistence после полного process restart;
- автоматический переход в `STALE` после изменения файла;
- реальная Sideload transfer, cancel/reconnect во время transfer и Recovery `SUCCESS/FAILED/UNKNOWN`;
- controlled Quick Flash mutation — загрузчик оставался locked, а `flash:*` отсутствует.

### Длина журналов и privacy finding

Compact log вырос из-за полного stdout команды `getprop`; protocol trace вырос из-за per-block Fastboot timing. Это ожидаемые raw diagnostics, но такие файлы могут содержать IMEI-подобные и другие аппаратные идентификаторы из произвольного terminal output. Raw compact/trace нельзя коммитить или отправлять как публичный отчёт.

`ReportSanitizer` при «Поделиться санитизированной копией» дополнительно маскирует IMEI/MEID/IMSI/ICCID, Android ID, CPUID/UFS ID и fingerprint UID в bracketed `getprop`, text key/value и JSON forms. `sanitized-log-share` и глобальное logs menu прошли exact-head Android CI run `30397037090`; остаётся device UI/share smoke.

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

### Smoke 4 — first-pass login regression

Build: `6.0.0-alpha5-dev-nekoflash+5f119c469430.29913150722`.

Подтверждено:

- fresh Xiaomi login завершился в одном запуске приложения без stale blocked-host banner;
- unlockApi service session и ожидаемые cookie names получены;
- ручная смена data-center preference работает;
- следующий Mi Unlock action безопасно остановлен из-за отсутствия Fastboot-устройства;
- transport session не создавалась, mutation не выполнялась.

Обнаружено:

- compact log всё ещё показывал raw account ID в сообщении об успешном login. Текущий source удаляет ID из log line; для этой sanitisation нужен regression smoke.

First-pass callback race считается `DONE_DEVICE`. Полный Mi Unlock flow остаётся отдельным hardware gate.

### Smoke 5 — Welcome fullscreen overlay

Maintainer visual verdict: **PASS**. Полноэкранное artwork отображается одним viewport без вертикальной прокрутки; заголовок закреплён сверху, прозрачный контурный permission/risk gate — снизу поверх изображения. Расположение кнопок и читаемость приняты как эталонные. Exact build ID к скриншоту не приложен, поэтому для публикуемого head всё ещё требуется Android CI и короткая regression-проверка без изменения макета.

### Smoke 6 — exact-head closure пяти UI/login пунктов

Source/CI head: `b220d48b796d09b13974d8dc39d090efbc2afb55`.
Device build: `6.0.0-alpha5-dev-nekoflash+b220d48b796d.30042304245`.

CI evidence archive подтвердил:

- canonical documentation/static/safety guards — PASS;
- pure/JVM matrix — `23/23 PASS`;
- `lintDebug`, `assembleDebug`, `assembleRelease` — `BUILD SUCCESSFUL`;
- launcher identity и Welcome artwork locks — PASS.

На устройстве закрыты пять smoke-пунктов:

1. новый круглый launcher с котом отображается;
2. fullscreen Welcome с прозрачным нижним overlay подтверждён как эталонный;
3. ADB Sideload до реальной проверки ZIP не показывает ложную зелёную success-галочку;
4. Mi Account login завершается в том же запуске приложения без stale blocked-host banner;
5. compact log не содержит raw Mi Account ID, token/cookie values; выводятся только названия ожидаемых cookies.

Последующее нажатие Mi Unlock корректно остановлено сообщением об отсутствии Fastboot-устройства. Это не является проверкой полного unlock flow. Серверный запрос с device token/product и передача `encryptData` в bootloader ещё не подтверждены.

### Hardware 7 — `vayu` A-only, Fastboot DATA и Sideload preflight

Build: `6.0.0-alpha5-dev-nekoflash+b220d48b796d.30042304245`.

Подтверждено:

- Fastboot handshake стабильно определяет `product=vayu`; устройство работает в bootloader fastboot, bootloader сообщает `unlocked=no`;
- `current-slot`, `slot-count` и `slot-suffix` возвращают unsupported/variable-not-found. Для `vayu` это legacy A-only topology, а не разрешение синтетических `_a`/`_b` targets;
- source policy уже фиксирует `vayu` как legacy A-only. При Quick Flash concrete unsuffixed `recovery` всё равно обязан подтверждаться read-only inventory/point-query;
- шесть 100 MiB native DATA profiles получили финальный `OKAY`; 128 MiB выбранного `recovery.img` успешно прошли ASYNC и SYNC private-staged download-only qualification;
- одна намеренная отмена native transfer корректно перевела текущую Fastboot session в BROKEN. Один ранний reconnect получил read timeout, следующий полный вход восстановил handshake и дальнейшие тесты;
- отдельная terminal-команда `reboot-recovery` получила `OKAY`;
- recovery ADB Sideload peer распознан, ZIP импортирован, SHA-256 рассчитан, структура recovery package подтверждена.

Не подтверждено:

- `getvar:all`/partition inventory в этой hardware session не запускались, поэтому concrete `recovery` candidate не был получен;
- ни одной `flash:*` команды в trace нет; Quick Flash mutation не выполнялась и при `unlocked=no` должна оставаться заблокированной;
- реальная ADB Sideload передача, cancel во время transfer и recovery result не запускались.

Найденный source defect:

- после успешной ASYNC/SYNC qualification запуск native diagnostic-only profile удалял тот же private staged artifact;
- cleanup ошибочно журналировался как `qualification failed/cancelled`, попадал в `lastError` и инвалидировал ранее валидное artifact evidence;
- локальный patch сохраняет staged artifact/evidence после diagnostic-only PASS, если для того же SHA-256 уже есть current-generation ASYNC/SYNC qualification; standalone diagnostic-only staging удаляется с нейтральной причиной и без mutation authorization;
- patch требует canonical checks, Android CI и повторного device regression. Новый PASS пока не заявлен.

### Hardware 8 — Alpha6 Slice A handoff, `vayu`

Build: `6.0.0-alpha6-dev-nekoflash+9823538147e0.30170789394`.

Handoff CI evidence:

- `lintDebug`, `assembleDebug`, `assembleRelease` — PASS;
- pure/JVM tests and safety guards — PASS;
- lint warnings не блокировали release gate.

Device evidence (`POCO X3 Pro / vayu`):

- `adb reboot system` — PASS;
- `adb reboot bootloader` — PASS;
- Fastboot handshake — PASS;
- DATA 32 MiB: UsbRequest, synchronous bulkTransfer и Native USBFS — PASS;
- large DATA payloads и `recovery.img` 128 MiB — PASS;
- detach/cancel during DATA — fail-closed, staging cleanup confirmed;
- flash commands remain blocked at `unlocked=no`.

Открытые проверки перед закрытием Slice A:

- ADB detach во время ordinary shell operation;
- malformed/partial ADB packet;
- повторный Fastboot DATA test после восстановления transport.

Найденный diagnostic defect: session-summary определял severity поиском слов внутри готового текста, поэтому `warnings=103` мог стать WARNING, а пояснение о safety block — ERROR. Slice A.1 устраняет lexical inference, вводит structured levels и отдельный `SAFETY_BLOCK`; это local code evidence до нового Android CI/device smoke.

## Текущий аппаратный runbook после CI `30405691356`

### Gate 0 — exact-head UI/transport smoke

Использовать Debug APK exact head `a03d6257cad7bf5f0ca585a6a9abdc1f8b2410f1`, SHA-256 `5f412cb7b1555cfb9205bf8efbde27465185e649e06ccfed0dd1a229e03f89da`.

| Проверка | Ожидаемый результат | Evidence |
|---|---|---|
| Cold start и вкладки | Home открывается стабильно; protected Welcome/Recovery-first UI без regression | screenshot + compact summary |
| Console drag | min/max bounds, tap collapse/expand, double-tap reset, persistence после process restart | portrait + landscape screenshots |
| Logs menu | current summary/compact/trace/JSON/history доступны; active files не удаляются | screenshot + sanitised share |
| Privacy share | нет raw IMEI/MEID/IMSI/ICCID/Android ID/serial/CPUID/UFS/fingerprint UID | просмотр экспортированной копии |
| ADB reconnect | attach, RSA reuse, disconnect и повторный attach без stale session | compact log summary |
| Fastboot read-only | product/mode handshake без `download`, `flash`, `boot`, `stage`, `unlock` | compact/trace summary |

Gate считается закрытым только после фактического PASS по всем строкам. Ошибка фиксируется отдельно и не маскируется общим «работает».

### Gate 1 — real ADB Sideload

1. Подтвердить `VERIFIED` после полного process restart.
2. Изменить отдельную копию ZIP и подтвердить `STALE` + blocked transfer.
3. Перевести Recovery в `Apply update → Apply from ADB` и подтвердить Sideload peer.
4. Передать проверенный ZIP, записать progress/WakeLock и итог transport operation.
5. Повторить с cancel и отдельно с cable detach/reconnect во время transfer.
6. Зафиксировать Recovery result `SUCCESS`, `FAILED` или `UNKNOWN` в persistent card.

Raw logs остаются вне Git. В этот документ переносится только sanitised build/head/device/expected/actual/result summary.

## Открытые V6 gates

### Sideload smoke

- защищённый Welcome не менять; на exact-head APK только подтвердить отсутствие регрессии;
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

- login и log-sanitisation уже подтверждены exact-head smoke;
- на реальном Fastboot-устройстве сначала выполнить только read-only/preflight: product, token availability, region/host selection и серверные ошибки без финального unlock;
- отдельно проверить состав отправляемых полей и отсутствие `passToken` на unlock hosts;
- подтвердить wipe warning и отдельное typed/manual confirmation перед финальным `ahaUnlock`/`oem unlock`;
- исключить автоматический retry или автоматическую отправку unlock-команды.

## Формат нового доказательства

Для каждого теста сохраняется sanitised ZIP вне source tree и краткая запись: version/build ID, модель/codename без serial, режим, шаги, результат и SHA-256 проверяемого файла. Cookie values, tokens, account ID и raw USB identifiers не включаются.

## Alpha6 defect input — maintainer screenshots, 2026-07-25

Status: **DEFECT_CONFIRMED / FIX_PENDING_DEVICE**. Screenshots are UX evidence; they are not protocol PASS by themselves.

Observed:

- `adb reboot system` (and one input using legacy `systems`) rebooted the target into Android, while the result dialog reported `ADB service завершился ошибкой`. The likely cause is expected USB teardown after the one-way reboot service.
- Native USBFS DATA self-test reached PASS, but the progress dialog remained `0 B / 100.00 MB`, `speed=N/A` and a stationary bar until final completion; final UI fields could contradict the successful trace result.
- Fastboot DATA diagnostic menus expose too many sizes/transports; Sideload verification details are not retained as a visible card state; Home/Console/Unlock/nested dialogs need hierarchy reduction.
- Cold start/restore can expose Terminal/expanded Console instead of a calm Home default.

Planned evidence for alpha6 Slice A:

1. Exact alpha6 build/head and successful Android CI.
2. `adb reboot system`: command hand-off shown as success/transition and device reconnect confirmed.
3. Negative control: ordinary ADB service with cable detach remains FAILED.
4. Native DATA 32/100 MiB: live confirmed-byte progress, rate, elapsed/ETA and coherent final result.
5. Native cancel/detach: DISCARDURB → REAP remains fail-closed.
6. `vayu` A-only and DATA evidence-lifecycle regression from head `0495345…`.
