# NekoFlash — Blueprint чистой пересборки: Legacy/A2 → идеальный код, без ограничений

Дата: 2026-08-29
Источник: `https://github.com/Ncorror/NekoFlash` — канонические доки `docs/00`–`docs/14`, `README.md`, а также реальный разбор архивов `reference/archives/NekoFlash-main-legacy.zip` и `NekoFlash-A2-frozen.zip` (контрольные суммы сверены с `reference/SHA256SUMS`, обе — OK).
Статус: дополняет, но не заменяет канонические доки. **При конфликте с `docs/00`–`docs/14` побеждают они.** Рабочая версия для сессии агента — `docs/16_AGENT_OPERATING_PROMPT_RU.md`.

Этот документ отвечает на прямой запрос: начать по-настоящему с нуля, сделать код идеальным, не забыть, что Legacy и A2 у нас есть и должны быть использованы, и не создавать ограничений для пользователя. Ниже — не мнение, а разбор реального кода обоих архивов с конкретными файлами и цифрами.

## 1. Почему «с нуля» — это не вкусовщина, а доказанная цифрами необходимость

| | Legacy (`ru.forum.adbfastboottool`) | A2 (`io.github.ncorror.nekoflash`) | Новый репозиторий сейчас |
|---|---|---|---|
| Файлов kt/java | 44 | 96 | 8 |
| Строк кода всего | 17 274 | 18 283 | 289 |
| из них тестов | **0** | 4 399 (~24%, 7 файлов) | тест на каждый core-модуль |
| Крупнейший файл | `MainActivity.kt` — 3880 строк | `UsbSessionCoordinator.kt` — 2495 строк | `NekoFlashApp.kt` — 156 строк |
| UI-технология | XML Views | Jetpack Compose | Jetpack Compose |
| ktlint/detekt | нет | нет | **detekt в CI** |
| Version catalog | нет | нет | есть (`gradle/libs.versions.toml`) |

Legacy — 17 274 строки продуктового кода и **ноль** автоматических тестов. A2 уже показал, что дисциплина тестирования реальна и работает (24% объёма — тесты), но так и не решил проблему god-классов: самый большой файл A2 такой же формы, как и самый большой файл Legacy — просто получил Compose и лучший package layout вместо реальной декомпозиции ответственности. Это количественное, а не оценочное обоснование того, что нужен настоящий чистый старт, а не очередной раунд патчинга такого же по форме кода. Phase 0 и Phase 1 нового репозитория (по `docs/00`, `docs/09`, `README.md`) уже прошли CI зелёными — «с нуля» здесь означает **продолжать в этом духе дальше со всей строгостью**, а не переигрывать уже подтверждённый bootstrap.

## 2. Реестр Legacy — что взять, что не тащить

| Файл | Строк | Что смешано внутри | Куда в новой архитектуре |
|---|---|---|---|
| `MainActivity.kt` | 3880 | USB lifecycle, разбор команд ADB **и** Fastboot терминала, Mi Login/Unlock UI, Settings UI (построен императивно вложенными функциями прямо в Activity), логи/отчёты, Quick Flash, permissions, battery optimization, смена языка, рендер прогресса операции, яркость экрана, window state | `feature:device`, `feature:terminal`, `feature:miunlock`, `feature:operations`, `feature:diagnostics`; `:app` — только граф/навигация |
| `AdbProtocol.kt` | 2792 (~114 функций) | ADB auth, USB endpoint I/O, packet dispatch, key store, shell, sync, sideload — и класс **напрямую держит UI-колбэки** `onLog`/`onProgress`; экземпляр одноразовый, непереиспользуемый | `protocol:adb` — без UI-колбэков, только структурные события и `Outcome` |
| `FastbootProtocol.kt` | 2426 (~77 функций) | raw-команды, DATA transfer, `getvar`-парсинг, OEM, flashing — тот же паттерн прямых UI-колбэков | `protocol:fastboot` |
| `DeviceViewModel.kt` | 1782 | состояние сразу нескольких экранов и обоих протоколов в одном ViewModel | По одному ViewModel на `feature:*`, без общего «god ViewModel» |
| `MiUnlockClient.kt` + `MiAccountClient.kt` + `MiAccountSecurityPolicy.kt` + `MiLoginActivity.kt` | ~1490 суммарно | vendor-протокол Xiaomi вперемешку с Activity/UI | `vendor:xiaomi` (протокол) + `feature:miunlock` (UI) |
| `NativeUsbfsBackend.kt` + `native_usbfs.cpp` | 468 + cpp | нативный мост к USBFS | `usb:native` |
| `FastbootPartitionInventory.kt`, `UsbDeviceInspector.kt`, `ConsoleDockController.kt`, `DiagnosticSessionTracker.kt`, `RecoveryInstallVerifier.kt`, `FastbootGetVarAllParser.kt`, `AdbKeyStore.kt`, `ReportSanitizer.kt`, `FlashOperationService.kt`, `AdbPacketDispatcher.kt` и другие (130–473 строк каждый) | по одной ответственности на файл — это уже **правильная** гранулярность | Логику растащить по `protocol:*`/`core:*`/`feature:*`; сам размер файла как ориентир брать именно отсюда, а не с четырёх крупных файлов выше |

Дополнительно из Legacy: `values-ru` уже существовал — двуязычность продолжается, а не изобретается заново; `applicationId "ru.forum.adbfastboottool"` — это исходная идентичность, замена на `io.github.ncorror.nekoflash` уже зафиксирована текущим ADR (`docs/adr/0001_PROJECT_IDENTITY_AND_BUILD_BASELINE_RU.md`).

## 3. Реестр A2 — что взять, что не тащить

| Файл | Строк | Проблема / ценность | Куда в новой архитектуре |
|---|---|---|---|
| `UsbSessionCoordinator.kt` | 2495 | god-координатор: attach/detach, permission+timeout, ручной выбор кандидата, старт/стоп ADB-transport, старт/стоп Fastboot-transport, оркестрация Quick Flash и Sideload, mode-switch watch, экспорт diagnostics, публикация inventory — всё в одном классе | Разложить: `usb:android` (только attach/detach/permission/generation ownership) отдельно от протокольных session-адаптеров в `protocol:adb`/`protocol:fastboot`, отдельно от оркестрации в `core:operation`/`feature:operations`, отдельно от `core:diagnostics` |
| `FastbootUsbTransport.kt` | 1781 | доказанный на железе native USBFS DATA OUT / точный byte accounting — это **evidence поведения**, не архитектура | `usb:native` + `protocol:fastboot`-адаптер; переносится логика/поведение, не класс целиком |
| `AdbUsbTransport.kt` | 1092 | доказанный ADB inbound USB framing fix (hardware gate PASS на `vayu` — уже принят как invariant для нового core, см. `docs/00`) | `protocol:adb`-адаптер |
| `HomeScreen.kt` | 1020 | Compose уже используется, но один экран на 1000+ строк — тот же паттерн раздувания, только в UI-слое | Разбить Composable по под-состояниям и секциям экрана |
| `FastbootDataTransfer.kt` | 623 | | `protocol:fastboot` |
| `SideloadStreamSession.kt` + `RecoveryInstallVerifier.kt` | 481 + 233 | доказанная mutation boundary для Sideload и Recovery-корреляция | `protocol:recovery` / sideload-адаптер в `protocol:adb` |
| `MainActivity.kt` (A2) | 478 | вчетверо тоньше Legacy благодаря Compose — подтверждает, что тонкая Activity достижима на практике | Ориентир: Activity — только host для Compose-дерева |
| `OperationCoordinator.kt` | 365 | один `Stage`-enum (`STARTING…CANCELLING`) применяется сразу к 3 разным `Kind` (`FASTBOOT_DIAGNOSTIC_REFRESH`/`FASTBOOT_FLASH`/`ADB_SIDELOAD`); координатор — единственный на всё приложение, поэтому `VERIFICATION_PENDING` у Sideload может держать занятым слот, нужный несвязанному Fastboot-действию | Своя state machine на каждый `Kind`, ownership на уровне `TargetId`/`SessionGeneration`, а не singleton на всё приложение |
| 7 тестовых файлов (4399 строк) | | реальная, ценная тестовая практика: policy-тесты (`UsbPermissionPolicyTest`, `UsbSessionLifecyclePolicyTest`, `OperationCoordinatorTest`, `UsbManualScanPolicyTest`, `SideloadStreamSessionTest`, `UsbInterfaceSelectorTest`, `FastbootPartitionInventoryBuilderTest`) — детерминированные, без реального USB | Перенести **по духу и стилю именования** (`<Subject>Test`) в тесты новых модулей, не копировать код напрямую |

A2 также оставил `docs/` на 22 файла — большинство названо по стадиям (`STAGE6A1`…`STAGE6D2`) и это ровно та структура, которую новый репозиторий сознательно не повторяет. Но среди них есть предметно ценные: `ADB_INBOUND_USB_FRAMING.md`, `USB_BEHAVIOR_CONTRACT.md`, `KNOWN_USB_DEVICE_QUIRKS.md`, `LEGACY_BEHAVIOR_BASELINE.md`, группа `SIDELOAD_*` — их стоит открывать точечно при реализации соответствующего нового модуля, а не переносить как структуру документации.

Ни в Legacy, ни в A2 не было ktlint/detekt и (в Legacy) не было version catalog — значит это не забытая кем-то мелочь, а действительно пустое место, которое можно и нужно закрыть в новом репозитории без оглядки на прошлые решения.

## 4. Стандарт «идеального кода» для новой кодовой базы

### 4.1 Красный флаг — не длина функции, а смешение ответственностей

`AdbProtocol.kt` содержит около 114 функций, и большинство из них по отдельности выглядят опрятно и коротко. Проблема не в качестве отдельной функции, а в том, что в одном классе одновременно живут: USB-эндпоинты, разбор ADB-пакетов, хранилище ключей, sync-протокол и UI-колбэки. Практический тест: если ответственность класса нельзя описать одним предложением без союза «и» — класс делает больше одной вещи.

Числовой ориентир (сигнал для ревью, не жёсткий закон компилятора): файл/класс за ~300–400 строк или ~15–20 публичных членов — обязательная пауза «точно ли это одна ответственность»; за ~800 строк без ADR-обоснования — блокирующее замечание в code review.

### 4.2 Направление зависимостей между модулями

| Модуль | Может зависеть от | Не может зависеть от |
|---|---|---|
| `core:model`, `core:operation`, `core:diagnostics`, `core:artifact` | друг друга внутри `core:*`; Android-специфичных API — только где без этого нельзя | `usb:*`, `protocol:*`, `feature:*`, `vendor:*`, `:app` |
| `usb:api` | `core:*` | `usb:android`, `usb:native`, `protocol:*`, `feature:*` |
| `usb:android`, `usb:native` | `usb:api`, `core:*` | `protocol:*`, `feature:*`, `vendor:*` |
| `protocol:adb` / `fastboot` / `recovery` | `usb:api`, `core:*` | друг друга напрямую, `feature:*`, `vendor:*` |
| `vendor:xiaomi` | `protocol:*`, `core:*` | `feature:*` (vendor не рисует UI) |
| `feature:*` | свой `protocol:*`/`vendor:*` + `core:*` | другой `feature:*` напрямую — только через `core:operation`/навигацию |
| `:app` | всё | не содержит протокольной/бизнес-логики — только граф зависимостей, DI, навигация, тема |

Это операционализация модульной карты `docs/02_CORE_ARCHITECTURE_RU.md`, а не альтернатива ей: именно это правило не даёт `feature:fastboot` завести собственную копию USB-transport в обход `protocol:fastboot`, как по факту случилось в A2, где `UsbSessionCoordinator` сам решал, когда стартовать/стопать протокольный transport.

### 4.3 Конкурентность и ошибки

Ни Legacy (`Handler`/callback-цепочки: `scheduleUsbPermissionTimeout`, `scheduleStartupUsbDiscovery`), ни A2, ни текущий bootstrap не закрепили конкурентную модель как явное решение. Для Phase 2 (USB/permission lifecycle, generations) предлагается зафиксировать (через ADR): `kotlinx.coroutines` + `Flow`, structured concurrency, `CoroutineScope` привязан к жизни `SessionGeneration`; никаких голых `Thread`/`Handler.postDelayed` в новом коде. `Outcome`/`Result` — sealed-классы для ожидаемых протокольных исходов (`Success/Failed/Cancelled/Unknown` уже заложены в `core:operation`), исключения — только для программистских ошибок и невозможных состояний.

### 4.4 Иммутабельность и состояние UI

`val`/`data class`/`sealed class` по умолчанию. Никаких классов уровня Activity с десятками `private var`, которые читаются и пишутся из полусотни методов — именно так была устроена `MainActivity.kt` в Legacy. Compose-экран — функция представления состояния, а не место для бизнес-логики; ViewModel не разрастается в «god ViewModel» (`DeviceViewModel.kt` — 1782 строки состояния сразу нескольких экранов) — состояние декомпозируется по фиче.

### 4.5 Инструменты, которых не было никогда — стоит завести сейчас

Version catalog в новом репозитории уже есть — это сделано правильно. Статический анализ стиля и сложности не появлялся ни в Legacy, ни в A2, и отсутствие именно такого гейта — одна из причин, по которой файлы на 2000–4000 строк выросли никем не остановленными. В новом репозитории этот пробел закрыт до появления протокольного кода: `scripts/ci/check_kotlin_style.sh` запускает detekt как обязательный шаг CI, включая машинную проверку границ между модулями. Решение и обоснование — ADR-0003.

### 4.6 Тесты — расширяем то, что уже доказано, а не изобретаем заново

A2 подтвердил, что policy-тесты работают: 4399 строк тестов (~24% объёма), детерминированные, без реального USB — `UsbPermissionPolicyTest`, `UsbSessionLifecyclePolicyTest`, `OperationCoordinatorTest` и другие. Legacy — количественное доказательство противоположного: 17 274 строки и **ноль** тестов. Правило для новой кодовой базы: тесты пишутся в том же changeset, что и код (уже так и происходит — у каждого текущего core-модуля есть тест), стиль именования `<Subject>Test` наследуется от A2.

## 5. Закон об отсутствии ограничений (сжатая версия)

NekoFlash даёт технически грамотному пользователю полный доступ к тому, что реально предоставляют ADB, Fastboot/fastbootd, Recovery и поддерживаемые vendor-протоколы. Рефакторинг не имеет права тихо отобрать, спрятать или «погейтить» ни одну ранее валидную команду только потому, что она рискованная или «не нужна обычному пользователю».

Запрещено: профили «Новичок/Эксперт», скрытые capability tiers между GUI и raw/Terminal, allowlist/denylist команд, превращение diagnostics-полей устройства в свою систему авторизации, entry-gate «прими риски».

Тест для любого нового ограничения: *какой конкретный protocol/transport/device-инвариант нарушится, если разрешить эту валидную команду?* «Опасно» / «обычному пользователю не нужно» / «так спокойнее» — не основание.

Четыре допустимых класса `stop/fail`: **Hard invariant** (битая рамка протокола, потерян ownership, доказуемо неоднозначная мутация), **Device authority** (реальный `FAIL`/отказ устройства — показать честно), **Advisory** (неизвестное состояние — предупредить, не блокировать), **User intent** (деструктивное действие — одно подтверждение в guided UI, в raw-консоли доступно всегда).

Product-level исключений нет ни одного (D031). Подтверждённый `LOCKED` — класс D: guided UI показывает предупреждение и требует ввести `yes`, после чего команда уходит на устройство без изменений; `UNKNOWN`/устаревшее/неподдерживаемое — обычный advisory без typed confirmation; raw console выполняет без prompt; отказ приходит от устройства как `FAIL`. Проверка lock state не живёт в protocol engine. Полная версия правила и таблица классов — в `docs/01_PRODUCT_CHARTER_RU.md`, `docs/03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md` и `docs/16_AGENT_OPERATING_PROMPT_RU.md`.

## 6. Как использовать этот документ

Этот Blueprint — справочник для планирования и код-ревью, дополняющий канонические документы; при расхождении побеждают они. Четыре решения, которые на момент написания Blueprint существовали неявно, с тех пор зафиксированы в ADR-0003: конкурентная модель (`kotlinx.coroutines` + `Flow`), подход к DI (ручной, пока масштаб позволяет), статический анализ как обязательный CI-гейт и числовые пороги размера из раздела 4.1 — из них машинным стал `LargeClass 600`.

Оперативная версия для работы в рамках сессии агента — `docs/16_AGENT_OPERATING_PROMPT_RU.md`.
