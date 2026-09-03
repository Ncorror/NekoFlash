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

## Phase 3 — настоящий ADB foundation + Terminal

Список пунктов заменён таблицей с колонкой статуса — той же формы, что в Phase 2.
Голый список эту форму уже однажды не удержал: отметки укрупнялись, список
расходился с действительностью, и пропущенный пункт был замечен со стороны.

Отметка «готово» ставится только по факту работающего production-пути: контракт,
который никто не вызывает, готовым пунктом не считается (capability completeness
rule, `04_CAPABILITY_MATRIX_RU.md`). Колонка «Где» заполняется и у незакрытых
пунктов — она показывает, что уже есть и чего не хватает.

| Пункт | Статус | Где |
|---|---|---|
| Ввод-вывод на захваченном интерфейсе | **нет** | контракт `UsbTransportHandle.receive`/`send`, `UsbTransferResult`, `UsbTransferArguments` в `usb:api` и реализация через `bulkTransfer` в `usb:android` есть; production-кода, который их вызывает, ещё нет — до CNXN вызывать нечему |
| Рамка пакета ADB | **нет** | `protocol:adb`: `AdbPacketHeader` (24 байта, `magic`, диапазон длины по объявленному нами `maxdata`), `AdbChecksum`, `AdbInboundFraming` с доказанным на `vayu` инвариантом, `AdbPacketReader` и `AdbPacketWriter` поверх `UsbTransportHandle`. Модуль ни от кого не вызывается: транспорт, который его заведёт, — следующий пункт |
| Автоподключение и режим peer'а | **частично** | распознанное устройство подключается само, как в Legacy: `UsbAutoConnectPolicy` разрешает это каноническим и совместимым интерфейсам и запрещает generic vendor. Попытка одна на поколение сессии. Режим ADB виден из баннера: система, Recovery, Sideload. Различение `fastboot` и `fastbootd` здесь невозможно в принципе — в Legacy оно бралось из `getvar is-userspace`, то есть после протокольного обмена, и придёт вместе с движком Fastboot |
| CNXN/AUTH | **частично** | рукопожатие, авторизация с диалогом и повторное подключение по сохранённому ключу подтверждены на `onyx` 2026-09-03 (`07` §6.13). Не проверены: отказ в диалоге, перезапуск приложения, отключение кабеля при подключённом ADB, Recovery и хост ниже API 28. Отметка «готово» появится после них |
| single physical reader | **нет** | — |
| `AdbStreamRouter` | **нет** | — |
| generic `openService` | **нет** | — |
| `shell,v2` | **нет** | — |
| legacy fallback | **нет** | — |
| interactive PTY shell | **нет** | — |
| real terminal UI | **нет** | — |
| concurrent stream tests | **нет** | — |

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
  **сделано** в `AdbInboundFraming`; сам `CNXN` его ещё никому не объявляет;
- таймауты A2: 5000 мс на приём и на передачу — **сделано**; 10000 мс на
  ожидание подписи AUTH и 60000 мс на подтверждение диалога — **сделано** в
  `AdbHandshake`;
- одно рукопожатие на один транспорт: повторный `CNXN` в том же соединении
  запрещён — **сделано**. В Legacy записано, что автоматическое закрытие с
  повторным открытием и вторым `CNXN` на ряде Android USB host вызывало цикл
  detach/attach и разрушало последовательность `AUTH`;
- хост пока не объявляет о себе возможностей: баннер `host::NekoFlash` взят из
  архивов дословно. Строку придётся расширить ради `shell,v2`, и сделать это
  надо будет вместе с самим `shell,v2`, а не заранее.

Стилевая часть гейта `scripts/ci/check_kotlin_style.sh` по-прежнему разбирает
только `app/src` и `core`, как это было и при появлении модулей `usb:*`. Гейт
границ модулей на `protocol/adb` заведён. Новый модуль прогнан против полной
стилевой конфигурации вручную: он даёт только замечания `MagicNumber` на
смещения полей проводного формата и битовые сдвиги в чтении little-endian.
Расширять стилевое покрытие на `usb` и `protocol` имеет смысл, но это отдельное
решение: оно требует либо исключения для кодеков, либо именования сдвигов, и
принимать его молча внутри changeset'а про рамку пакета неправильно.

Порядок изменён намеренно и записан: правило из `16_AGENT_OPERATING_PROMPT_RU.md`
не запрещает менять порядок, оно запрещает делать это молча.

Это первый главный product milestone: **USB → ADB → настоящий профессиональный Shell**.

## Phase 4 — ADB professional services

- Sync core;
- push/pull;
- install/install-multiple strategy;
- reboot;
- raw services;
- forward/reverse, если current protocol layer готов;
- file/transfer UI;
- large-file and process-death tests.

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
