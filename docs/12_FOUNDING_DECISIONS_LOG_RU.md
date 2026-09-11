# NekoFlash — Founding Decisions Log

Короткий журнал решений, которые нельзя потерять между чатами/итерациями.

## D001 — Clean repository
Новая кодовая база строится отдельно. Legacy/A2 — reference/evidence, не фундамент целиком.

## D002 — Product identity
Продукт остаётся **NekoFlash**. Сохраняем бренд, Legacy Welcome identity и иконку как visual reference.

## D003 — Professional capabilities
Нет искусственного урезания ADB/Fastboot/Recovery/Terminal capabilities. Risk warnings не являются authorization системой.

## D004 — Correctness is strict
Transport/protocol invariants, ownership, exact byte accounting, stale generation protection и честный Unknown не ослабляются ради «полной свободы».

## D005 — One generic core
GUI, raw terminal, Quick Flash и Mi Unlock используют общие ADB/Fastboot/USB engines.

## D006 — ADB is multi-stream
Новый ADB foundation сразу проектируется с настоящим stream router/demux. Не допускается single-stream architecture как permanent base.

## D007 — Fastboot is one transaction engine
Raw и typed commands проходят через один engine. Host preflight — advisory, если wire operation валидна.

## D008 — Mi Unlock is first-class
Mi Unlock не забывается в «потом vendor tools». Это значимая часть финального NekoFlash, реализуемая после стабильного generic core.

## D009 — Welcome remains
Welcome не удаляется. Убирается только старая модель «accept risks / permissions = authorization to use app».

## D010 — Device-centered UI
Главный UX объект — target device/session. Target identity видна постоянно во время работы.

## D011 — Terminal is real
Нужен настоящий terminal emulator и полноценный shell/raw console, а не TextField-симуляция.

## D012 — Operations outlive screens
Flash/Sideload/transfers принадлежат application-scoped operation layer/foreground service, не Activity/ViewModel.

## D013 — Large files are streaming
64-bit sizes, ArtifactSource/Sink, никакого whole-file buffering. SAF seekability не предполагается.

## D014 — Clean tree
Никаких `.orig`, parallel old/new paths, production stubs и obsolete normative docs.

## D015 — Hardware evidence
Hardware-sensitive core changes закрываются реальным evidence, а не только unit tests.

## D016 — A2 final gate is closed
`vayu` inbound framing gate завершён: **PASS**. A2 development CLOSED; Legacy/A2 — frozen reference/evidence only.

## D017 — No novice/expert permission profiles
В продукте нет профиля «Новичок / Эксперт», unlock Expert Mode, hidden capability tier или onboarding-gate для профессиональных функций. Progressive disclosure допустим только как UX, не как authorization.

## D018 — Documentation changes with the project
Canonical docs обновляются в том же changeset, где меняются behavior, architecture, gates, CI/process rules или project status. Документация — часть Definition of Done.

## D019 — GitHub Actions is authoritative CI
Полноценные build/test/lint/static checks и APK artifacts выполняются в GitHub Actions. Green CI — обязательный integration signal.

## D020 — Termux is the working Git environment
Termux используется для worktree, editing, Git commit/push и repository operations. Полноценная Android/Gradle сборка в Termux не является требованием проекта.

## D021 — Reference archives are immutable
Legacy, A2 и founding canonical snapshots хранятся отдельно в `reference/archives/`, проверяются SHA-256 и после commit не заменяются. Active code/docs не строятся из этих ZIP.

## D022 — Current repository identity
`https://github.com/Ncorror/NekoFlash` остаётся официальным URL нового clean NekoFlash repository. Legacy source сохраняется как `reference/archives/NekoFlash-main-legacy.zip` и не должен идентифицироваться по текущему URL нового repository.

## D023 — Application identity is fixed
Новый production `applicationId` и Android `namespace`: `io.github.ncorror.nekoflash`. Текущий repository остаётся `https://github.com/Ncorror/NekoFlash`.

## D024 — Build baseline is explicit
Phase 1 baseline: `minSdk 26`, `targetSdk 36`, `compileSdk 37`, AGP `9.3.2` (patch update from initial `9.3.0`), Gradle `9.5.0`, Kotlin `2.4.10`, Compose BOM `2026.08.00`, Material 3 Adaptive `1.3.0`, JDK 17 в CI. Повышение `targetSdk` и смена Gradle line выполняются отдельными проверяемыми changesets.

## D025 — Modules require real ownership
Первый bootstrap создаёт только `:app`, `:core:model`, `:core:diagnostics`, `:core:operation`. Пустые USB/ADB/Fastboot/Recovery/vendor modules заранее не создаются.

## D026 — Gradle Wrapper is tracked and verified
Официальный Gradle Wrapper хранится в repository и является единственным build entrypoint для CI/developer environments. Bootstrap wrapper `9.5.0` получен из authoritative GitHub Actions run; wrapper JAR принимается только после сверки SHA-256 с официальным Gradle checksum, а distribution ZIP checksum фиксируется в `gradle-wrapper.properties`. CI больше не генерирует wrapper заново на каждом run.

## D027 — English and Russian are first-class UI languages
NekoFlash поддерживает English и Русский как равноправные языки интерфейса. English — default Android resource locale, Russian — `values-ru`. Пользовательские строки не хардкодятся в Compose/Kotlin. Protocol commands, partition names, raw peer responses, wire tokens и stable diagnostic codes остаются точными locale-neutral данными; перевод применяется только к presentation layer. Android 13+ использует generated per-app locale configuration.

## D028 — App-managed state is not automatically backed up or migrated
NekoFlash использует fail-closed policy для Android Auto Backup и device-to-device migration: все app-managed backup domains исключены и для legacy `fullBackupContent`, и для Android 12+ `dataExtractionRules`. Причина — будущие ADB host keys, vendor auth state, diagnostics и operation metadata нельзя молча переносить на другой host. User-owned SAF artifacts остаются вне app backup model. Любое будущее разрешение backup требует отдельного security review/decision.

## D029 — Repository and localization hygiene are executable CI contracts
Канонические правила clean repository и bilingual UI проверяются executable scripts в `scripts/ci/`, а не остаются только текстом: CI запрещает backup/reject leftovers и production stubs, проверяет text hygiene, EN/RU resource parity и очевидный hardcoded Compose UI text. Проверки должны оставаться узкими и объяснимыми, чтобы не превращаться в ложный policy gate.

## D030 — Verified locked blocks only ordinary partition flash (SUPERSEDED by D031)
**Статус: отменено D031.** Текст ниже сохранён как исторический контекст и **не является действующим правилом**.

> Verified Bootloader Lock Protection — единственное специально принятое product-level исключение к правилу NO ARTIFICIAL CAPABILITY RESTRICTIONS. Если устройство в текущей Fastboot `SessionGeneration` однозначно подтверждает `LOCKED`, NekoFlash не выполняет обычный `flash:<partition>` образа; это одинаково действует для typed Flash, Quick Flash и raw Fastboot `flash`. `UNKNOWN`/unsupported/contradictory/старое состояние не считается `LOCKED`. Guard не распространяется автоматически на `erase`, `format`, `boot`, `set_active`, OEM/flashing/raw commands или другие mutating operations. Mi Unlock/unlock workflow при locked остаётся доступным, а после reboot/re-enumeration обычный flash разрешается только после fresh verification `UNLOCKED` в новой session. Расширение этого guard требует отдельного canonical decision.

## D031 — Confirmed locked bootloader is an advisory, not a block
Прежний Verified Bootloader Lock Protection (D030) отменён. Он был единственным product-level hard guard и единственным пунктом устава, не проходившим собственный тест четырёх классов из `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`: отказ при `LOCKED` принадлежит классу **Device authority** — устройство отвечает `FAIL` само, — а guard упреждал этот ответ решением хоста, что правило класса B прямо запрещает.

Новое правило: намерение записать образ при подтверждённом `LOCKED` относится к классу **User intent**. В guided UI оно требует typed confirmation `yes` — той же формы, что `erase userdata` и `format`. После подтверждения команда отправляется устройству без изменений, и показывается реальный ответ peer. Предупреждение обязано честно называть ожидаемый исход: устройство почти наверняка ответит `FAIL` и раздел не изменится, но на части загрузчиков сначала передаётся весь DATA-объём и только затем приходит отказ.

`UNKNOWN`, unsupported query, противоречивый ответ и состояние из предыдущей generation не равны `LOCKED`: для них действует обычный advisory без typed confirmation, иначе `UNKNOWN` де-факто снова приравнивается к `LOCKED`. Raw Fastboot console выполняет команду без prompt. Typed confirmation принадлежит use-case/presentation слою; protocol engine не проверяет lock state как условие выполнения, иначе engine снова становится host-side gate. Mi Unlock не затронут. После unlock состояние определяется заново в новой Fastboot session.

Следствие: product-level hard guards в NekoFlash отсутствуют полностью. Остановка возможна только по классам A–D. Любое новое host-side ограничение требует отдельного founding decision и обязано пройти тест четырёх классов.

## D032 — Public module API uses api(), not implementation()
Модуль, чей публичный API раскрывает типы другого модуля, объявляет эту зависимость как `api(...)`. `core:operation` и `core:diagnostics` раскрывают `TargetId`/`SessionGeneration` в публичных data-классах, но объявляли `:core:model` как `implementation`. Это компилировалось только потому, что `:app` объявляет `:core:model` напрямую; модуль, зависящий лишь от `core:operation`, не смог бы обратиться к `OperationContext.targetId`. Для получения семантики `api` в Kotlin JVM модулях применяется плагин `java-library`.

## D033 — Phase 2 technical baseline is recorded before code
Конкурентная модель, подход к DI и статический анализ фиксируются решением до написания USB/протокольного кода, а не постфактум: `kotlinx.coroutines` + `Flow` со structured concurrency и scope, привязанным к `SessionGeneration`; ручной DI без библиотеки, пока число модулей это позволяет; detekt как обязательный CI-гейт, включая машинную проверку границ модулей. Детали и обоснование — `docs/adr/0003_PHASE2_TECHNICAL_BASELINE_RU.md`.

## D034 — Читающий цикл принадлежит соединению, а не потребителю (ACCEPTED)
Инвариант «один физический читатель на транспорт» сохраняется, но перестаёт держаться соглашением между четырьмя классами: постоянный цикл принадлежит `AdbConnection`, а логические потоки получают ограниченные почтовые ящики. Это снимает ограничение «один потребитель за раз», из-за которого `AdbLinkController.busy()` гасит кнопки при живой оболочке. Ограничение было архитектурным долгом, а не продуктовым запретом, и `04_CAPABILITY_MATRIX_RU.md` требует concurrent multi-stream router как обязательную capability.

Статус **ACCEPTED** с 2026-09-09: решение переписывало механизм, через который проходит каждый аппаратно доказанный путь Phase 3, и потому подтверждалось прогоном на устройстве, а не сборкой. Четыре прогона его подтвердили — `07` §6.40–§6.43. Замысел и порядок переноса потребителей — `docs/adr/0004_CONCURRENT_ADB_DISPATCHER_RU.md`.

## D035 — Один порядок чтения и один источник статуса
Порядок чтения документов живёт **только** в `/CLAUDE.md`; `16` §5 и `99` на него ссылаются и своих списков не держат. Раньше списков было три, и они разошлись: сессия, шедшая по `CLAUDE.md`, не открывала `99`, а сессия по `99` — `README` и чеклист фазы.

Статус готовности пункта живёт **только** в чеклисте фазы `09`. Остальные документы называют номер текущей фазы — его сверяет гейт — и ссылаются на чеклист. Пересказ «что сделано» прозой в `00` §7 и `99` убран: он дублировался в четырёх файлах, устаревал раньше чеклиста и требовал ручной правки в четырёх местах за changeset.

Журнал `07` §6.x получает указатель, собранный из заголовков разделов; `scripts/ci/check_docs_consistency.py` сверяет указатель с разделами и падает при расхождении. Навигация по журналу перестаёт зависеть от того, помнит ли сессия его структуру.

## D036 — Граница мутации sync SEND доказана, а не предположена
Оборванный `SEND` уничтожает файл, лежавший по пути назначения. Измерено на `vayu` 2026-09-08 (`07` §6.38): существующий файл в 4 КиБ с подтверждённым отпечатком после оборванной перезаписи исчез — `STAT` вернул нулевой режим, `sha256sum` и `ls` ответили `No such file or directory`.

Следствие: ответ `AdbSyncDestination.UNKNOWN` после пересечения границы — единственно верное утверждение, а не перестраховка. Граница остаётся на записи запроса `SEND`. Момент уничтожения (при `SEND` или при первом блоке `DATA`) прогон не различает и различить выдёргиванием кабеля не может: между ними микросекунды. На поведение это не влияет — реальная передача всегда шлёт `DATA` сразу за `SEND`.

Ослабление границы до «после успешного `DONE`» запрещено: оно вернуло бы ложное «ничего не изменилось» для случая, который теперь наблюдён на железе.

## D037 — Проброс портов делается половинами, и вторая опирается на наблюдение (ACCEPTED)
`forward` и `reverse` называют парой, но общего у них только слово. `forward` устройству ничего не сообщает: `tcp:5555` — обычный сервис ADB, а реестр пробросов живёт на хосте, поэтому он строится на существующем диспетчере целиком. `reverse` слушает устройство, и соединение приходит входящим `OPEN`, который маршрутизатор сегодня не принимает вовсе. Поэтому пункт делится: `forward` до аппаратного прогона, потом входящий `OPEN`, потом `reverse`.

Формат запроса `reverse` в архивах отсутствует — оба дерева проверены по именам файлов и по содержимому, совпадений по существу нет. Восстанавливать его по памяти запрещено тем же правилом `16` §3, на котором проект уже спотыкался (`07` §6.46). Формат выясняется наблюдением через поле произвольного сервиса и записывается в evidence раньше, чем появится опирающийся на него код.

`INTERNET` добавляется в манифест осознанно: любой `AF_INET`-сокет на Android, включая loopback, без него не создать, а без слушателя, к которому оператор подключится браузером, возможность вырождается в декларацию. Потолок одновременных соединений относится к нашим потокам, а не к тому, что оператору разрешено просить: ни адрес, ни порт не проверяются и списков нет (`01` §3).

Замысел, порядок работы и список того, что обязан доказать прогон, — `docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md`. Первая половина принята 2026-09-11: гейт `07` §6.51 закрыт прогоном §6.59 по всем семи шагам, включая четыре одновременных потока на постороннем потребителе. Вторая половина принята в тот же день: формат выяснен наблюдением (§6.61, §6.62 — там же нашлось требование разбирать сырой вывод, иначе непустой список объявлялся бы непонятым), а гейт §6.63 закрыт прогонами §6.65 и §6.66 — шесть байт прошли полный круг через оба механизма сразу. Деление на половины себя оправдало дважды: `forward` дошёл до железа, не дожидаясь `reverse`, а `reverse` писался по наблюдённому формату, а не по памяти.

## D038 — Fastboot: одна полоса и роль из трёх состояний (PROPOSED)
У Fastboot нет мультиплексирования, поэтому полоса обмена одна и синхронная — это hard invariant протокола, а не наше упрощение, и тем он отличается от ADB (D034). Роль устройства имеет три состояния, и `UNKNOWN` не сворачивается в «загрузчик»: вывод из отсутствия ответа наблюдением не является, а операции с динамическими разделами в ролях различаются. Команда, которую провод не несёт (не ASCII или длиннее 64 байт), называется, а не подменяется молча — запрета на набор при этом нет. Полное решение — `docs/adr/0006_FASTBOOT_SINGLE_LANE_AND_THREE_STATE_ROLE_RU.md`; до аппаратного прогона `07` §6.69 остаётся `PROPOSED`.
