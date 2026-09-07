# NekoFlash — Clean Implementation Roadmap

Принцип: строим **вертикальными срезами**, а не полгода пишем абстрактное ядро без железа и UI.

## Phase 0 — A2 evidence и freeze — COMPLETE / PASS

- `vayu` read-only inbound framing gate завершён успешно;
- framing fix принят как proven correctness invariant;
- Legacy и A2 frozen/reference only;
- никаких новых A2 product features.

Примечание: финальный diagnostics bundle gate отсутствует внутри frozen A2 archive; PASS зафиксирован как project status по подтверждению владельца.

## Phase 1 — bootstrap нового repository — COMPLETE / PASS

Phase 1 closure CI: **VERIFIED / PASS** (2026-08-28). Подтверждены 7 core unit tests, Android Lint `0 errors`, debug APK assembly, checked-in verified Gradle Wrapper `9.5.0`, AGP `9.3.2`, launcher/adaptive/monochrome icon, explicit no-backup/D2D policy и executable repository/localization hygiene checks. Остались только осознанные informational lint warnings: `targetSdk 36` при `compileSdk 37`, наличие более новой версии Gradle и, с сентября 2026, более новой версии AGP; они не подавляются и не обновляются без отдельного behavior/toolchain review.

- package/application identity;
- Gradle/Compose baseline;
- canonical docs из этого пакета;
- GitHub Actions как authoritative CI/build/test environment;
- Termux как Git/worktree environment без требования локальной Android-сборки;
- structured logging/evidence skeleton;
- clean module boundaries;
- NekoFlash icon/Welcome references;
- минимальный adaptive app shell;
- bilingual resource foundation: English default + Russian `values-ru`, generated per-app locale config, без hardcoded user-facing strings;
- launcher icon wired from the preserved NekoFlash brand reference;
- automatic backup/D2D migration disabled by explicit XML policy for app-managed data;
- repository hygiene and localization parity enforced as CI steps;
- AGP patch baseline updated to `9.3.2`, Gradle intentionally retained at verified `9.5.0`.

Сразу сделать Home/Target Bar skeleton, чтобы core развивался внутри реального product shell.

## Phase 2 — USB + Target/Session vertical slice — COMPLETE

Статус каждого пункта проставляется по факту наличия работающего кода, а не по ощущению завершённости. Пункт, сделанный в составе другого, ссылается на него явно.

Укрупнённая отметка «готово» на строке, покрывающей сразу несколько пунктов, **запрещена**: именно так список однажды разошёлся с действительностью, и пропущенный пункт `basic diagnostics export` был замечен только со стороны.

| Пункт | Статус | Где |
|---|---|---|
| Манифест: `uses-feature android.hardware.usb.host`, `USB_DEVICE_ATTACHED`, `device_filter.xml` | **готово** | `app/src/main/AndroidManifest.xml`, `usb/android/src/main/AndroidManifest.xml`, `app/src/main/res/xml/device_filter.xml` |
| Descriptor discovery | **готово** | `AndroidUsbHost.devices`, `AndroidUsbDescriptorMapper`, `UsbInterfaceClassifier` |
| Target identity | **готово** | `UsbTargetIdentity` — с указанием источника, потому что имя подключения не переживает re-enumeration |
| `SessionGeneration` | **готово** | `UsbSessionRegistry` — монотонная выдача, необратимое завершение |
| Permission | **готово** | `UsbPermissionPolicy`, `UsbPermissionCallbackIdentity`, `AndroidUsbHost.requestPermission`, планирование таймаута в `NekoFlashApplication` |
| Связывание событий USB в состояние сессий | **готово** | `UsbSessionCoordinator` |
| Detach / re-enumeration | **готово** | `UsbSessionRegistry.closeDetached`, приёмник `ACTION_USB_DEVICE_DETACHED` |
| UI показывает target и сессию | **готово** | `NekoFlashApp`; протокольный режим не показывается — до handshake он неизвестен, и экран говорит об этом прямо |
| Basic diagnostics export | **готово** | `DiagnosticBundle` собирает детерминированный архив, `UsbSessionCoordinator` пишет события, `UsbDiagnosticReport` формирует разделы, выгрузка идёт через системный диалог сохранения файла |
| Claim / release | **готово** | `UsbHost.claim` и `UsbTransportHandle` в контракте, `UsbDeviceConnection.claimInterface(force)` в `usb:android`, захват и освобождение по действию оператора. Автоматического захвата нет намеренно — обоснование в `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` |
| Hardware test | **готово** | четыре прогона на POCO `25053PC47G` с целью `serial:eff4927c`. Evidence и критерий PASS — `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` §6.10 |

Фаза закрыта: все пункты имеют отметку «готово», каждый подтверждён кодом, тестами и аппаратным прогоном.

`Basic diagnostics export` намеренно поставлен перед `claim / release`. Без выгрузки логов аппаратный прогон даёт впечатления, а не evidence, которого требует `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md`. Сначала диагностика — тогда уже сделанное можно проверить на устройстве с настоящим отчётом, а `claim / release` ляжет на работающую запись событий и будет проверяем сразу. В обратном порядке его пришлось бы проверять дважды.

Перестановка записана здесь намеренно: правило из `16_AGENT_OPERATING_PROMPT_RU.md` не запрещает менять порядок, оно запрещает делать это молча.

## Phase 3 — настоящий ADB foundation + Terminal — COMPLETE

Список пунктов заменён таблицей с колонкой статуса — той же формы, что в Phase 2.
Голый список эту форму уже однажды не удержал: отметки укрупнялись, список
расходился с действительностью, и пропущенный пункт был замечен со стороны.

Отметка «готово» ставится только по факту работающего production-пути: контракт,
который никто не вызывает, готовым пунктом не считается (capability completeness
rule, `04_CAPABILITY_MATRIX_RU.md`). Колонка «Где» заполняется и у незакрытых
пунктов — она показывает, что уже есть и чего не хватает.

| Пункт | Статус | Где |
|---|---|---|
| Ввод-вывод на захваченном интерфейсе | **готово** | `UsbTransportHandle.receive`/`send` в `usb:api`, `bulkTransfer` и сброс halted-состояния в `usb:android`. Вызывается production-путём начиная с `AdbLinkController`, подтверждено на устройстве (`07` §6.13, §6.14) |
| Рамка пакета ADB | **готово** | `AdbPacketHeader`, `AdbChecksum`, `AdbInboundFraming` с доказанным на `vayu` инвариантом, `AdbPacketReader` и `AdbPacketWriter`. Работает на устройстве: рукопожатие и баннер прошли через эту рамку (`07` §6.13) |
| Автоподключение | **готово** | подтверждено на `onyx` 2026-09-03 (`07` §6.14): захват через 3,8 мс после разрешения, после ручного отключения повтора нет, после переподключения кабеля подключение снова автоматическое. `UsbAutoConnectPolicy` разрешает это каноническим и совместимым интерфейсам и запрещает generic vendor — правило Legacy |
| Режим peer'а | **готово** | режим читается из баннера `CNXN`; на устройстве подтверждены все три: `DEVICE`, `RECOVERY` (`07` §6.24) и `SIDELOAD` (`07` §6.27). Различение `fastboot` и `fastbootd` здесь невозможно в принципе: в Legacy оно бралось из `getvar is-userspace`, то есть после протокольного обмена, и придёт вместе с движком Fastboot |
| CNXN/AUTH | **готово** | подтверждены на железе: рукопожатие, авторизация с диалогом, повторное подключение по сохранённому ключу, отключение кабеля при подключённом ADB, перезапуск приложения с сохранением ключа (`07` §6.13, §6.14, §6.17). Отказ в диалоге подтверждён 2026-09-06 (`07` §6.28): `AUTHORIZATION_NOT_CONFIRMED`, интерфейс отпущен через 2,6 мс. Recovery и Sideload пройдены (§6.24, §6.27). Единственное непроверенное — объявление `maxdata` 16 КиБ на хосте ниже API 28: такого устройства на стенде нет, ветка покрыта тестами. Это ограничение стенда, а не долг |
| single physical reader | **готово** | физическое чтение остаётся на одном выделенном reader executor; запись Terminal вынесена на отдельный последовательный writer executor, поэтому Compose/UI thread не выполняет blocking USB I/O. Пока reader занят интерактивной сессией, одноразовые команды отклоняются — настоящий concurrent dispatcher относится к Phase 4. Общее stream-состояние защищено замком, ожидание пакета идёт снаружи замка. Выдержало трёхминутный поток `logcat` (`07` §6.26); threading hardening — `07` §6.31 |
| `AdbStreamRouter` | **готово** | правила маршрутизации перенесены и покрыты тестами: чужой `WRTE`/`CLSE` получает ответное `CLSE`, каждый `WRTE` подтверждается немедленно, `WRTE` до `OKAY` подтверждается но данными не считается, идентификаторы не переиспользуются. Работает на устройстве: девять команд подряд получили потоки 1…9 без повторения идентификаторов (`07` §6.20). Постоянного фонового цикла по-прежнему нет — он нужен только интерактивному shell |
| generic `openService` | **готово** | `AdbServiceCall` вызывает произвольный сервис: дедлайн, потолок на приём, предел объёма, закрытие потока при любом из них. Все сценарии `07` §6.18 пройдены на устройстве 2026-09-05 (`07` §6.20), включая предел вывода и обрыв кабеля посреди команды |
| `shell,v2` | **готово** | `AdbShellProtocol` разбирает рамки, `AdbConnection.shell` выбирает `shell,v2,raw:` при поддержке устройством и откатывается на `shell:` иначе. На экране появились stderr и код возврата. Расширять баннер хоста **не требуется**: в Legacy баннер ровно `host::NekoFlash`, никаких возможностей хост не объявляет, а решение принимается по возможностям **устройства** (`supportsShellV2 = deviceFeatures.contains("shell_v2")`), и на железе это работало. Подтверждено на устройстве 2026-09-05 (`07` §6.23): код возврата 127 у отсутствующей команды, `stdout` и `stderr` раздельно в одной команде. Ветка отката на `shell:` на железе не проверялась — устройства без `shell_v2` под рукой нет |
| legacy fallback | **готово** | откат на `shell:` написан и покрыт тестами: срабатывает и когда устройство не объявило `shell_v2`, и когда объявило, но поток не открылся. Сработал на устройстве 2026-09-06 в Sideload (`07` §6.27): два `service_open` подряд. Выяснилось, что Sideload объявляет `shell_v2`, но сервиса не даёт — объявленная возможность не обещает, что поток откроется, и откат по неудачному открытию оказался не перестраховкой |
| interactive PTY shell | **готово** | `AdbShellFrameBuffer` собирает рамки по мере поступления, `AdbInteractiveShell` держит живую сессию: шаг цикла обычной функцией, ввод рамками `stdin`, `Ctrl+C` байтом, конец ввода отдельной рамкой. На экране подключённого устройства есть терминал: ввод, `Ctrl+C`, закрытие. Читающий цикл живёт на выделенном потоке. Подтверждено на устройстве 2026-09-06 (`07` §6.26): три минуты живого `logcat`, `Ctrl+C` прервал команду не убив оболочку, `exit` закрыл сессию |
| real terminal UI | **готово** | вывод, поле ввода, `Ctrl+C` и закрытие на экране подключённого устройства. Подтверждено на устройстве (`07` §6.26). Управляющие последовательности показываются как есть — эмулятора терминала нет, и на экране об этом сказано |
| concurrent stream tests | **готово** | `AdbConcurrentStreamsTest`: вывод двух потоков не смешивается, подтверждение уходит своему, закрытие одного не задевает соседа, опоздавший пакет закрытого не попадает в живой, чередование сохраняет порядок в каждом, обрыв закрывает все. Отдельно: живая оболочка не замечает чужой поток в том же читателе. Production-кода, открывающего два потока сразу, ещё нет — он появится с Sideload и передачей файлов |

Первым пунктом стоит ввод-вывод, которого не было в исходном списке. Причина не
в удобстве: доказанный на `vayu` inbound framing invariant — это правило про
одну операцию приёма (`03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` §4), и перенести
его некуда, пока операции приёма не существует. Ответ на `CNXN` нечем прочитать
раньше, чем у захваченного интерфейса появятся `receive` и `send`.

Сам инвариант живёт не здесь: `usb:api` сообщает число перенесённых байт и не
судит о рамке. Решение, что короткий приём объявленного payload — это потерянная
рамка, принадлежит `protocol:adb` и приходит вместе с ним.

Что при этом обязан сделать `protocol:adb`, чтобы не потерять уже доказанное
поведение архивов (сверено по Legacy `AdbProtocol.kt` и A2
`adb/transport/AdbUsbTransport.kt`, а не по памяти о протоколе):

- запись хост → устройство дробится на куски по 16 КиБ и повторяется, пока не
  отправлено всё; в обратную сторону дробление объявленного payload запрещено —
  **сделано** в `AdbPacketWriter` и `AdbPacketReader`;
- заголовок в 24 байта читается до полного набора, и прерванный на середине
  заголовок — это потеря рамки, а не таймаут — **сделано**;
- объявленный payload читается одной операцией приёма, короткий результат —
  fail-closed — **сделано**;
- `maxdata` объявляется по уровню API: 16 КиБ до API 28, 1 МиБ начиная с него —
  **сделано**. Значение считает `AdbInboundFraming.advertisedMaxPayload`,
  уровень API приходит из `NekoFlashApplication`, а `AdbConnection` считает его
  **один раз** и отдаёт в два места сразу: в `arg1` пакета `CNXN` и в
  `AdbPacketReader` как предел длины входящего кадра. Это одно число намеренно:
  в Legacy объявленное значение и порог отбраковки жили порознь, и именно
  оттуда началась история inbound framing invariant;
- таймауты A2: 5000 мс на приём и на передачу — **сделано**; 10000 мс на
  ожидание подписи AUTH и 60000 мс на подтверждение диалога — **сделано** в
  `AdbHandshake`;
- одно рукопожатие на один транспорт: повторный `CNXN` в том же соединении
  запрещён — **сделано**. В Legacy записано, что автоматическое закрытие с
  повторным открытием и вторым `CNXN` на ряде Android USB host вызывало цикл
  detach/attach и разрушало последовательность `AUTH`;
- хост не объявляет о себе возможностей: баннер `host::NekoFlash` взят из
  архивов дословно. Расширять его не требуется — сверено 2026-09-05: в Legacy
  тот же баннер, а `shell,v2` включается по возможностям **устройства**, и на
  железе это работало. Первоначальное предположение об обратном было
  ошибочным и здесь исправлено.

Стилевая часть `scripts/ci/check_kotlin_style.sh` теперь разбирает production
Kotlin всех семи модулей: `app`, `core:*`, `usb:*` и `protocol:adb`. Гейты
границ модулей остаются отдельными и дополняют общий style/complexity run.
Исторический узкий scope `app/src,core` больше не считается достаточным: новый
production-код не должен выпадать из общей статической проверки только из-за
того, что появился в более поздней фазе. Для `protocol:adb` принято ровно то
явное исключение, необходимость которого была записана выше в истории фазы:
правило `MagicNumber` не применяется к production source этого wire-протокола,
где числовые значения являются command words, masks, offsets и little-endian
shifts самого формата. Остальные style/complexity rules на `protocol:adb`
работают как обычно; это не исключение модуля из detekt. Расширенный scope
проверяется локально там, где доступен detekt, а authoritative PASS будет
зафиксирован только после GitHub Actions этого changeset'а (`07` §6.31).

Порядок изменён намеренно и записан: правило из `16_AGENT_OPERATING_PROMPT_RU.md`
не запрещает менять порядок, оно запрещает делать это молча.

Это первый главный product milestone: **USB → ADB → настоящий профессиональный Shell**.

**Фаза закрыта 2026-09-06.** Тринадцать пунктов из тринадцати. Аппаратные гейты:
`07` §6.13, §6.14, §6.17, §6.19, §6.20, §6.23, §6.24, §6.26, §6.27, §6.28.

Единственное, что не проверялось на железе, — объявление `maxdata` 16 КиБ на
хосте ниже API 28: такого устройства на стенде нет, ветка покрыта тестами для
уровней 26 и 27. Это ограничение стенда, а не долг.

Не входит в фазу и остаётся для Phase 9: эмулятор терминала. Управляющие
последовательности сейчас показываются как есть, и на экране об этом сказано.

## Phase 4 — ADB professional services

Список заменён таблицей со статусами по образцу Phase 3. Отметка «готово»
ставится только по факту работающего production-пути, подтверждённого на
устройстве.

| Пункт | Статус | Где |
|---|---|---|
| Sync core | **частично / read-only доказан, `SEND` ждёт железа** | `AdbSyncProtocol` и `AdbStreamBuffer` плюс `AdbSyncSession` со `STAT`, `RECV` и `SEND`. Read-only путь подтверждён на железе малым и 2 MiB файлом (`07` §6.29, §6.32). `SEND` написан и покрыт тестами, но на устройстве не проверялся и вызывающего в production ещё не имеет — гейт `07` §6.34 |
| pull / `RECV` | **частично / transport доказан** | `RECV` покрыт тестами и production hardware evidence: 10 байт и 2 MiB получены полностью, SHA-256 большого файла совпал с `sha256sum` (`07` §6.29, §6.32). Блоки стримятся и не накапливаются. Сохранение в пользовательский destination ещё требует artifact sink из Phase 8; именно поэтому весь пользовательский pull пока не COMPLETE |
| push / `SEND` | **частично / протокол написан, железо не проверено** | `AdbSyncSession.send` со `SEND`/`DATA`/`DONE` и вердиктом, 17 тестов. Разбор mutation/cancellation/result semantics по `docs/03` §3 выполнен и записан в KDoc `AdbSyncSendOutcome`: граница мутации — запись запроса `SEND` в USB, состояние назначения сообщается отдельно от исхода операции, автоматический повтор запрещён. Не закрыто: вызывающего в production нет (нужен artifact source из Phase 8), аппаратный прогон не проводился — гейт `07` §6.34. Статус `RECV` к `SEND` не наследуется |
| concurrent service dispatcher | **не начато** | `AdbStreamRouter` умеет маршрутизировать несколько logical streams, но production ownership пока даёт один активный reader consumer. Нужен один постоянный physical reader/dispatcher, который раздаёт пакеты нескольким logical sessions; только после этого Terminal, file transfer и raw services можно честно разрешить параллельно. Это архитектурный долг, а не искусственный product gate |
| install/install-multiple | **нет** | — |
| reboot | **нет** | — |
| raw services | **нет** | `AdbServiceCall` уже вызывает произвольный сервис; здесь нужен доступ к этому из UI |
| forward/reverse | **нет** | по документу — «если current protocol layer готов» |
| file/transfer UI | **частично** | Read-only UI уже умеет `STAT` и `RECV`+SHA-256. Выбор destination, сохранение artifact, progress/cancel для полноценного transfer UI остаются открыты |
| large-file and process-death tests | **частично** | `RECV` 2 MiB подтверждён на железе с совпавшим SHA-256 (`07` §6.29/§6.32); process-death и large mutation/upload paths ещё не проверены |

## Phase 5 — Fastboot generic engine

- bootloader/fastbootd classification;
- one transaction engine;
- INFO/TEXT/OKAY/FAIL;
- raw command;
- getvar explorer;
- DATA OUT;
- flash/boot/erase/format/set_active/reboot;
- OEM/flashing;
- typed UI + raw console используют один engine;
- Lock state как advisory: подтверждённый `LOCKED` даёт предупреждение и typed confirmation `yes` в guided UI для записи образа; команда не отменяется на стороне хоста, raw console выполняет её без prompt, Mi Unlock не затронут.

Перенести доказанные A2 USBFS ideas без старой широкой host authorization policy. Не расширять узкий verified-lock guard на другие команды по аналогии.

## Phase 6 — Fastboot DATA IN + modern partition workflows

- DATA IN abstraction;
- fetch/upload class;
- partial output semantics;
- logical partitions/fastbootd workflows;
- Quick Flash/Plan поверх public API.

## Phase 7 — Recovery + Sideload

- generic Recovery evidence client поверх нового ADB core;
- request-driven Sideload;
- mutation boundary;
- unique progress;
- baseline/correlation/verdict;
- full Recovery workspace.

Переносить A2 contract как correctness evidence, не как ADB capability restriction.

## Phase 8 — Operation/Artifact hardening

Часть operation engine существует раньше, но здесь закрываем полный production contract:
- foreground connected-device ownership;
- persistence/history;
- process death;
- artifact source/sink;
- non-seekable SAF staging;
- hashes;
- exact progress/rates;
- Unknown outcome UX;
- Operations Center.

## Phase 9 — UX completion

- final adaptive navigation;
- Device Workspace;
- command palette;
- terminal tabs/history;
- supporting diagnostics pane;
- refined Welcome;
- brand/theme polish: замена дефолтной Material-палитры на опорную палитру Legacy (`accent #E9782B` и роли из `05_FINAL_UI_UX_AND_BRAND_RU.md` §2), вывод светлой схемы;
- accessibility/localization foundations.

Важно: UI не «ждёт Phase 9». Каждый предыдущий vertical slice имеет рабочий production UI. Phase 9 — completion/polish, а не первая GUI-интеграция.

## Phase 10 — Mi Unlock

Mi Unlock проектируется заранее как first-class feature, но implementation идёт после стабильного core:
- Legacy protocol/network audit;
- vendor:xiaomi client;
- target/device/account state model;
- Fastboot integration через public core;
- Operations/evidence integration;
- polished dedicated UX;
- реальные error/server states;
- hardware/server-safe test plan.

## Phase 11 — hardening/release

- full hardware matrix;
- performance/memory profiling;
- long transfer endurance;
- background/lifecycle stress;
- accessibility;
- diagnostics privacy review;
- release signing/build reproducibility;
- stale/dead code audit;
- documentation audit;
- recovery bundle verification.

## Commit rule

Каждый changeset должен быть понятным, тестируемым и оставлять **один** production path. Не копить огромный «rewrite everything» branch, который невозможно доказать на железе.
