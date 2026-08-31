# NekoFlash — Testing, CI & Hardware Evidence

## 1. Тесты строятся по слоям

### Pure unit
- codecs/parsers;
- state machines;
- byte accounting;
- advisory classification;
- artifact logic;
- correlation logic.

### Protocol simulation
Fake peer/transport должен уметь моделировать:
- short reads/writes;
- packet fragmentation;
- delayed responses;
- duplicate/out-of-order relevant events;
- stream concurrency;
- malformed frames;
- `INFO/TEXT/FAIL` sequences;
- DATA length mismatch;
- disconnect at every interesting byte/state boundary.

### Integration
- Android USB permission/session ownership;
- foreground operation service;
- persistence/process recreation;
- SAF seekable/non-seekable providers where testable.

### Hardware
Нужен matrix реальных устройств/режимов, потому что USB host stacks и Recoveries имеют vendor quirks.

## 2. Hardware evidence policy

Hardware-sensitive change не считается proven только по unit tests.

Evidence bundle должен содержать:
- app commit/build identity;
- host device identity;
- target identity;
- mode;
- timestamps;
- relevant structured logs/transcript;
- exact byte counts;
- screenshots только как дополнительное подтверждение, не вместо logs;
- PASS criteria.

## 3. Reference devices

### `vayu`
Стабильный reference target для A2 evidence. Доказаны Native USBFS Fastboot DATA transfer/flash, cancel/drain semantics и финальный ADB inbound framing gate — **PASS**.

Frozen A2 archive не содержит финальный diagnostics bundle этого framing gate; статус PASS зафиксирован как project evidence по подтверждению владельца и должен учитываться отдельно от наличия самого bundle.

### `onyx` / POCO F7 / Redmi Turbo 4 Pro
USB-unstable stress patient. Не использовать как единственный reference gate. Quirk должен стать explicit observed capability/advisory, а не глобальным hard-block устройства.

## 4. Regression matrix нового проекта

Минимум постепенно покрыть:
- ADB Android mode;
- ADB Recovery mode;
- concurrent ADB streams;
- shell_v2 и legacy fallback;
- push/pull крупного файла;
- install;
- bootloader Fastboot;
- fastbootd;
- DATA OUT крупного image;
- DATA IN/fetch;
- physical detach during read-only operation;
- detach before and after mutation boundary;
- Sideload normal flow;
- Sideload duplicate requests;
- Sideload verifier reconnect/correlation;
- Mi Unlock staging на безопасных этапах и отдельно destructive/server-sensitive gate.

## 5. Localization quality gate

English и Russian являются first-class UI locales. Для каждого changeset, меняющего пользовательский интерфейс:
- default English resources и `values-ru` должны оставаться согласованными;
- Android Lint `MissingTranslation`/resource format failures являются блокирующими;
- user-facing Compose strings не должны возвращаться в hardcoded literals;
- protocol/raw/evidence data не переводятся на уровне core;
- при существенном UI изменении проверяется, что русский текст не ломает adaptive layout.

## 6. CI minimum

**Authoritative build/test environment нового NekoFlash — GitHub Actions.** Termux используется как Git/worktree environment и не обязан быть полноценной Android/Gradle build machine.

Каждый merge в main:
- compile;
- unit/protocol tests;
- static analysis/lint;
- formatting;
- repository hygiene scan;
- Kotlin style/complexity/module-boundary scan;
- no generated binary drift where relevant;
- test report artifact;
- build identity embedded/exportable in diagnostics;
- canonical documentation updated in the same changeset when behavior, architecture, gates or project status changes.

## 6.1. Phase 1 bootstrap CI evidence — 2026-08-28

Первый успешный bootstrap run подтвердил:
- `:core:model`: 3 tests, 0 failures/errors;
- `:core:diagnostics`: 1 test, 0 failures/errors;
- `:core:operation`: 3 tests, 0 failures/errors;
- Android Lint: 0 errors;
- `assembleDebug`: PASS, debug APK собран;
- Gradle Wrapper `9.5.0` сгенерирован authoritative CI и принят для commit только после сверки wrapper JAR SHA-256 с официальным Gradle checksum.

Наличие warning не превращается в failure автоматически: intentional compatibility/tooling warnings документируются, а actionable warnings устраняются отдельными проверяемыми changesets.

## 6.2. Phase 1 closure evidence — 2026-08-28

Authoritative CI подтвердил Phase 1 closure:
- checked-in Gradle Wrapper остаётся build entrypoint;
- AGP `9.3.2` собирает проект на JDK 17 / Gradle `9.5.0`;
- launcher/adaptive icon wired из NekoFlash brand reference;
- monochrome themed icon присутствует, `MonochromeLauncherIcon` устранён;
- explicit `dataExtractionRules`/backup policy устраняет actionable backup warning;
- `scripts/ci/check_repository_hygiene.sh` PASS;
- `scripts/ci/check_localization.py` PASS;
- 7 core unit tests PASS;
- Android Lint: **0 errors, 2 warnings**;
- `assembleDebug`: PASS.

Оставшиеся warnings осознанные и не подавляются ради нулевого счётчика: `OldTargetApi` для `targetSdk 36` при `compileSdk 37` и наличие более новой Gradle версии. Их изменение требует отдельного Android behavior/toolchain review.

## 6.3. usb:api — 2026-08-30

Первый модуль Phase 2. Чистый JVM-контракт без Android: дескрипторы USB, классификация интерфейсов и выведение идентичности target.

Классификация перенесена из двух независимых источников, сошедшихся на одном и том же — Legacy `UsbDeviceInspector.kt` и A2 `usb/discovery/UsbInterfaceSelector.kt`: класс `0xFF`, подкласс `0x42`, протокол `0x01` для ADB и `0x03` для Fastboot, четыре уровня уверенности с подавлением низших, обязательная пара bulk-эндпоинтов.

Выведение идентичности спроектировано заново: в A2 его не было. `TargetId` строится по серийному номеру, когда он доступен, и по имени USB-подключения, когда нет; источник хранится рядом с идентичностью, потому что второй вариант не переживает re-enumeration и не должен выдавать себя за первый.

Владение сессиями и `SessionGeneration` спроектированы заново по `02_CORE_ARCHITECTURE_RU.md`: generation монотонна в пределах процесса, каждое подключение получает новую, завершённая сессия необратима. Обращение по устаревшей generation отклоняется с указанием причины завершения, а не generic-ошибкой: реестр помнит ограниченное число недавно закрытых сессий именно ради честного ответа «устройство отключено».

Политика разрешений перенесена из A2 (`UsbPermissionPolicy`), но опирается на сессии, а не на отдельный реестр ожидающих запросов: два источника истины о том же самом рано или поздно расходятся. Сохранены проверенные детали — приоритет точного совпадения идентификатора подключения над именем, повторная привязка выбранного интерфейса к свежему дескриптору, таймаут 30 секунд и отказ подключаться неявно по разрешению, о котором система не сообщила.

57 unit-тестов модуля закрепляют перенесённые инварианты. Проверка границ модулей расширена на `usb/api`: Android-импорты в него запрещены машинно.

## 6.4. usb:android и декларации манифеста — 2026-08-30

Первый Android-модуль. Содержит только перекладку дескрипторов `android.hardware.usb.*` в модель `usb:api`: разбор сырых значений направления и типа передачи вынесен в `usb:api` и покрыт тестами, поэтому в непроверяемом на телефоне коде не осталось ни одного решения.

Attach-фильтр перенесён из Legacy как есть — один wildcard `class="255"` вместо списка производителей. Список конкретных vendorId был бы ровно тем запретом, который запрещает продуктовый устав: устройство отсеивалось бы за отсутствие в нашем списке, а не за собственный отказ. Wildcard покрывает канонические ADB и Fastboot, OEM Fastboot с нестандартными subclass/protocol и новые vendorId без правки списка.

`uses-feature android.hardware.usb.host` объявлен обязательным так же, как в Legacy и A2. На установку это не влияет: `uses-feature` используется магазином приложений, а не установщиком, а NekoFlash распространяется вне магазина.

Проверка границ модулей расширена на `usb/android`: зависимости на protocol, feature, vendor, UI и Compose запрещены машинно.

Владение USB на стороне Android добавлено следом: перечисление устройств, приёмники подключения и отключения, запрос разрешения. Хост не принимает решений — он сообщает факты и выполняет запрошенное, а классификация, идентичность, владение сессиями и разбор ответа остаются в `usb:api`.

Уникальность имени действия для ответа системы вынесена в `usb:api` и покрыта тестами: идентичность отложенного намерения не учитывает дополнительные данные, поэтому активации приходится различать самим именем действия, а токен процесса не даёт ответу, созданному до гибели процесса, совпасть с приёмником в новом.

Наблюдение на стороне Android построено на слушателе: приёмники платформы работают по колбэкам, и оборачивать их в `Flow` следует уровнем выше, а не внутри владельца USB.

`kotlinx.coroutines` введён отдельным изменением вместе с первым настоящим использованием: `UsbSessionRegistry` публикует незавершённые сессии как `StateFlow`. Прежняя функция-снимок удалена — два способа получить одно и то же расходятся. Зависимость проверена сборкой в том же изменении, которым введена.

Связывание добавлено следом. Платформенная часть вынесена за интерфейс `UsbHost` в `usb:api`, поэтому `UsbSessionCoordinator` остался чистым и покрыт тестами на подставном хосте: полный цикл подключения, выдача и отказ в разрешении, таймаут, отключение, уточнение идентичности серийным номером после разрешения.

Границы координатора намеренно узкие. В A2 ровно эта склейка разрослась до 2495 строк, потому что туда же попали запуск и остановка транспортов, оркестрация прошивки и Sideload, экспорт диагностики. Здесь координатор умеет только превращать события USB в состояние сессий.

Планирование времени координатору не принадлежит: стартовое сканирование, повторные попытки и таймаут ожидания разрешения запускает платформенный слой, вызывая его методы. Планирование нельзя выполнить детерминированно в тесте, а всё остальное — можно.

Экран добавлен последним. Владение USB живёт в `NekoFlashApplication`, а не на экране: подключённое устройство не должно теряться при повороте. Таймаут ожидания разрешения планируется там же, в области с `Dispatchers.Default` — главный диспетчер потребовал бы отдельного артефакта корутин, а реестр сессий и наблюдаемое состояние потокобезопасны, доставку на экран берёт на себя Compose.

Экран показывает только то, что действительно известно: идентичность target вместе с её источником, транспортный вид интерфейса, уверенность классификации, состояние сессии и generation. Протокольный режим не показывается вовсе, и вместо него выводится прямое объяснение, что до handshake он неизвестен: дескриптор отличает интерфейс класса ADB от класса Fastboot, но не Android от Recovery, а bootloader Fastboot от fastbootd. Подставлять правдоподобное вместо неизвестного значило бы обманывать оператора.

Ресурсы прежнего статичного макета, ставшие ненужными, удалены вместе с ним, а не оставлены про запас.

Сборщик evidence-пакета добавлен в `core:diagnostics` отдельно от платформы, потому что `java.util.zip` доступен и без Android — а значит формат архива проверяется тестами, а не наблюдением. Архив детерминирован: одинаковый набор разделов и одна отметка экспорта дают побайтово одинаковый файл, поэтому два прогона можно сравнить напрямую и расхождение будет означать расхождение данных, а не времени файловой системы. Время записей в архиве обнуляется намеренно.

Записывается только то, что передал вызывающий: каталог целиком не упаковывается, чтобы посторонний файл рядом не попал в evidence. Поток остаётся за вызывающим и не закрывается сборщиком.

Событие превращается в одну строку, пригодную и для чтения человеком, и для `grep`; переносы внутри значений заменяются пробелом, чтобы одно событие не распалось на несколько строк.

Запись событий подключена следом. `UsbSessionCoordinator` сообщает в приёмник каждый значимый переход: открытие сессии с параметрами подключения и выбранного интерфейса, запрос и исход разрешения, уточнение идентичности после его выдачи, таймаут с принятым решением, закрытие при отключении. Реестр по-прежнему ничего не пишет сам — он возвращает достаточно подробные результаты, а решает, что из этого событие, координатор.

Приёмник событий ограничен по объёму: владелец USB живёт всё время работы приложения, и неограниченный список рос бы часами. Число вытесненных событий сохраняется и попадает в evidence — молча потерять начало журнала значит выдать неполный отчёт за полный.

Выгрузка завершает диагностику. Архив содержит три раздела помимо описи: `host.txt` с идентичностью сборки и устройства-хоста, `sessions.txt` со снимком сессий на момент выгрузки, `events.txt` с журналом. Файл создаёт пользователь через системный диалог сохранения — приложение не заводит собственного хранилища отчётов.

Состав отчёта ничем не урезан. Он собирается оператором о собственном устройстве, и вырезать из него сведения «на всякий случай» означало бы ровно тот host-side запрет, который запрещает `01_PRODUCT_CHARTER_RU.md`. Поле, которого платформа не даёт, записывается с причиной отказа, а не пропускается: серийный номер хоста требует `READ_PRIVILEGED_PHONE_STATE`, разрешения системного уровня, которое обычному приложению не выдаётся ни при каких действиях пользователя. Вызов не делается вовсе — он не может завершиться успешно, — а в отчёт идёт причина. Это отказ платформы, который по классу Device authority показывается как есть.

Протокольный режим в отчёт не подставляется и записан как `unknown_until_handshake`. Пустое место и правдоподобная догадка одинаково портят evidence.

## 6.6. Первый аппаратный прогон — 2026-08-31

Хост: POCO `25053PC47G` (`onyx`), Android 16, SDK 36, патч 2026-07-01. Сборка `0.1.0-dev`, versionCode 1.

Цель: `serial:eff4927c`, vendorId `0x18D1`.

**Подтверждено на устройстве:**

- пять generation подряд, 1→5, монотонно, ни одна не переиспользована;
- уточнение идентичности `USB_ATTACHMENT → SERIAL` сработало при каждой выдаче разрешения;
- **идентичность пережила смену режима**: один и тот же серийный номер при `productId` `0x4EE7` (ADB), `0xD00D` (Fastboot) и `0x4E11` (ADB в другой конфигурации USB). Это прямое подтверждение решения не включать `vendorId`/`productId` в ключ при наличии серийного номера;
- Fastboot-интерфейс классифицирован как `FASTBOOT` с уровнем `CANONICAL`;
- отказ в разрешении закрыл сессию, повторное подключение открыло новую;
- таймаут, пришедший после ответа, дал `IGNORE` и ничего не изменил;
- потерь событий нет: `droppedEvents=0`.

**Найдено и исправлено по итогам прогона:**

- события сообщали состояние **до** перехода: `permission_requested` писал `DISCOVERED`, `permission_granted` — `PERMISSION_PENDING`. Отчёт называл прошлое настоящим. Снимок теперь берётся из результата самого перехода;
- снимок сессий оказался пустым, потому что устройство отключают перед выгрузкой. В отчёт добавлены недавно завершённые сессии — реестр их и так помнит;
- `MainActivity` не имела `launchMode="singleTop"`, и подтверждение attach-намерения пересоздавало экран. Атрибут есть и в Legacy, и в A2 — он был пропущен при переносе манифеста. Владение USB при этом не пострадало: оно живёт в `Application`, и в журнале нет ни дублирующих сессий, ни лишних generation.

**Остаётся незакрытым:** после отказа в разрешении повторить запрос можно только физическим переподключением. Приложение не предлагает повторить попытку — это ограничение, созданное нами, а не устройством.

## 6.5. Kotlin style gate — 2026-08-30

`scripts/ci/check_kotlin_style.sh` добавлен после закрытия Phase 1 и в evidence-записи 6.1/6.2 не входит: на 2026-08-28 этой проверки не существовало.

Гейт подтверждён authoritative CI в том же changeset, которым введён: detekt `1.23.8` разбирает Kotlin `2.4.10`, текущее дерево проходит оба прогона (стиль/сложность и границы модулей), SHA-256 дистрибутива сверяется перед запуском.

## 7. Hygiene CI

CI должен запрещать production tree leftovers:
- `*.orig`;
- `*.bak`;
- `*.rej`;
- obvious `Old*`, `New*`, `Temp*` duplicate implementations;
- TODO/FIXME/HACK в production без явно принятой policy/issue reference;
- `NotImplemented`/throw-stub production paths;
- accidental secrets/private keys beyond intentionally generated local ADB keys handled by secure app storage.

Не превращать это в тупой regex, который ломает legit test fixtures: правила должны быть узкими и объяснимыми.

## 8. Definition of Done для core capability

1. Один production path.
2. Public/internal API определён.
3. Unit/protocol tests PASS.
4. Fault-injection cases покрыты.
5. Peer response не теряется.
6. Cancellation/Unknown semantics определены.
7. Hardware-sensitive path доказан.
8. Diagnostics позволяют разобрать failure без догадок.
9. Документация обновлена одновременно с кодом.
