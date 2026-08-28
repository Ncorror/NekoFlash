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

## D030 — Verified locked blocks only ordinary partition flash
Verified Bootloader Lock Protection — единственное специально принятое product-level исключение к правилу NO ARTIFICIAL CAPABILITY RESTRICTIONS. Если устройство в текущей Fastboot `SessionGeneration` однозначно подтверждает `LOCKED`, NekoFlash не выполняет обычный `flash:<partition>` образа; это одинаково действует для typed Flash, Quick Flash и raw Fastboot `flash`. `UNKNOWN`/unsupported/contradictory/старое состояние не считается `LOCKED`. Guard не распространяется автоматически на `erase`, `format`, `boot`, `set_active`, OEM/flashing/raw commands или другие mutating operations. Mi Unlock/unlock workflow при locked остаётся доступным, а после reboot/re-enumeration обычный flash разрешается только после fresh verification `UNLOCKED` в новой session. Расширение этого guard требует отдельного canonical decision.
