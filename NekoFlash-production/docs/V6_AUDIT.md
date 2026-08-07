# Аудит очистки NekoFlash V6


## System audit and performance hardening — 2026-08-04

### База и границы

Работа начата с recovery-архива `NekoFlash-feature-recovery-first-quick-flash(4).zip`, SHA-256
`08535d92ae30c737948f9c8a513e99cf985f45bee237e23c4c7114213801be4b`.
В ZIP отсутствует `.git`, поэтому соответствие конкретному remote commit не объявляется доказанным только по содержимому архива. Из последнего вывода Termux зафиксированы reviewed source SHA
`45b741f657d1137123617d740d8abcb956d0ad03`, successful source CI run `30853649548` и recovery tag
`archive/recovery-first-quick-flash-final-2026-08-04-45b741f`; merged `origin/main` должен быть повторно проверен в Termux перед публикацией нового ZIP.

Публичный Kotlin/JNI API, 14-полевая схема native transfer result, Fastboot mutation policy и diagnostic-only статус Native USBFS не изменяются.

### Приоритеты

1. **C++/JNI:** exception boundary, ownership, URB drain, errno/stage truth, bounds.
2. **Python:** portability, dead imports/functions, source/recovery tooling.
3. **Kotlin:** callback serialization, coroutine/lifecycle ownership, logging allocations/IO, dormant legacy subsystems.

### Batch 1 — Native USBFS/JNI hardening — DONE_CI / DEVICE_REQUIRED

Изменено:

- JNI exports больше не выпускают C++ exceptions в ART; allocation failures возвращаются как structured failure.
- Неожиданный exceptional escape fail-closed блокирует дальнейший native backend до полного restart, а не завершает процесс через `std::terminate`.
- Ошибка `read()` теперь сохраняет `STAGE_READ`, а не маскируется как `STAGE_SUBMIT`.
- `usbdevfs_urb.actual_length` проверяется на диапазон `0..requested` до изменения confirmed byte counters.
- Kotlin progress poller имеет completion latch; final callback выполняется только после подтверждённой остановки polling callback, поэтому два callback больше не могут исполняться конкурентно.
- Critical native calls ловят ожидаемые `Exception`/`LinkageError`, но не проглатывают `OutOfMemoryError` и другие fatal VM errors.
- В USB connectivity guard добавлены постоянные проверки этих границ.

Доказательства:

- Clang C++17 `-Wall -Wextra -Werror -fsyntax-only`: PASS.
- Python compileall и static/documentation/safety guards: PASS.
- Pure/JVM matrix: `ALL TESTS PASSED (27 module(s))`.
- Exact main SHA `3f416744347ec5a44cc7d64668760b0778bf473e`, GitHub Actions run `30949495954`: `conclusion=success`.
- Termux evidence ZIP: `NekoFlash-CI-30949495954.zip`.
- Реальный USBFS hardware transfer/cancel/drain после изменений: **DEVICE_REQUIRED**; CI не заменяет device evidence.

### Batch 2 — Native USBFS RAII ownership — DONE_LOCAL / CI_REQUIRED

Изменено без изменения JNI signatures, 14-полевой схемы результата и diagnostic-only статуса backend:

- payload и duplicated USB descriptors переведены на move-only `UniqueFd`;
- `GetStringUTFChars`/`ReleaseStringUTFChars` связаны scope guard;
- `usbdevfs_urb` и bulk buffers владеются `std::unique_ptr`;
- регистрация активной передачи снимается `TransferRegistrationGuard` даже при раннем return или C++ exception;
- `PendingUrbOwnershipGuard` обнаруживает exceptional unwind при pending URB, намеренно отдаёт ownership через `release()` и poison-ит backend до process restart;
- drain-failure сначала отказывается от владения potentially kernel-referenced memory и только затем выполняет дальнейшие no-throw cleanup operations;
- обычный drain-success путь очищает slots и закрывает fd до формирования JNI result;
- удалены ручные `delete[]`, `delete`, `free_slots()` и разрозненные `close()` paths.

Локальные доказательства до публикации:

- host C++17 `-Wall -Wextra -Werror -fsyntax-only`: PASS;
- Clang Static Analyzer: PASS без diagnostic;
- USB connectivity guard фиксирует RAII и pending-URB fail-closed invariants;
- Python compileall, documentation/project/checksum/A-B/USB/flash/logging guards: PASS;
- Pure/JVM matrix: `ALL TESTS PASSED (27 module(s))`.

Android Lint, Android NDK compile и Debug/Release APK для Batch 2 имеют статус **CI_REQUIRED**. Hardware USBFS остаётся отдельным diagnostic-only test после CI.

### Python cleanup — DONE_LOCAL

- Явно задан `encoding="utf-8"` в A/B и USB guards.
- Удалён неиспользуемый `time` из timeout runner.
- Удалены неиспользуемые `WARNINGS`/`warn()` и локальные дубли `re` из project guard.
- Kotlin char-literal scan переведён с `os.walk + open()` на `Path.rglob + read_text()`.

### Recovery continuity — DONE_LOCAL

- `scripts/export-recovery-bundle.sh` создаёт один ZIP с publishable source tree, компактным chat context, manifest и восстановительными командами.
- Bundle исключает `.git`, local SDK paths, signing material, APK/AAB/SO/O/build outputs.
- exporter создаёт recovery ZIP для нового чата и отдельный bootstrap-compatible `reviewed-source` ZIP для первой публикации через старый publisher на текущем `main`; после merge обновлённый `scripts/termux-publish.sh` также умеет читать вложенный recovery bundle без импорта соседних chat/evidence файлов.

### Следующие batches

- **Batch 3:** асинхронный bounded logging writer; убрать disk IO из `logLock`/UI thread.
- **Batch 4:** доказательная карта dormant legacy Flash Queue и решение удалить либо изолировать.
- **Batch 5:** разнести `MainActivity`, `DeviceViewModel`, `FastbootProtocol`, `AdbProtocol` только по ответственности, без искусственного лимита строк.

Каждый batch публикуется отдельным Pull Request и требует exact-head Android CI. Hardware USBFS и mutation evidence не объединять с refactoring evidence.

## Полный source audit alpha6 — 2026-07-29

Историческая база аудита: на тот момент загруженная ветка `feature/recovery-first-quick-flash`, версия `6.0.0-alpha6-dev-nekoflash` (`218`). Изменения выполнены без пересмотра product scope, transport protocol semantics, Quick Flash mutation gate, fullscreen Welcome и Recovery-first card.

### Найдено

- `MainActivity` содержала legacy UI/diagnostic flows, которые больше не имели достижимой навигации; часть соответствующих ViewModel APIs и ресурсов также оставалась осиротевшей.
- Console resize/collapse/persistence была смешана с экранной orchestration.
- USB `Parcelable` extraction повторяла Android-version branches.
- В lifecycle/file helpers встречались пустые или слишком широкие exception paths; main-thread callbacks создавали новые `Handler` вместо одного lifecycle-owned instance.
- Lint debt включал затенённый `result`, неиспользуемую `highRiskAllowed` и устаревшие WebView cleanup calls.
- Архитектурная документация не описывала effective API, threading/lifecycle contracts и правила расширения.

### Удалено

- 29 доказуемо неиспользуемых методов из UI/ViewModel/policy/inspector layers.
- Полностью осиротевший `ImageInspector.kt`.
- Legacy Fastboot diagnostic matrix/content/shared-storage UI, старый firmware analyzer, legacy flash target/confirmation, старые help/log dialogs и языковые override hooks.
- 9 неиспользуемых drawable/color resource files и 39 obsolete string keys; parity двух локалей сохранён на 374 keys.
- Неиспользуемые imports, variables и private helpers.

`MainActivity.kt` уменьшен с 5518 до 4950 строк, `DeviceViewModel.kt` — с 3918 до 3723 строк (включая новый KDoc).

### Выделено и исправлено

- `ConsoleDockController` теперь владеет Console gesture/height/persistence logic; `MainActivity` только делегирует.
- `IntentCompat.parcelableExtra` централизует Android 13/legacy USB intent compatibility.
- `DeviceViewModel` переиспользует один main-thread `Handler` и удаляет callbacks в `onCleared`.
- Activity cleanup выполняется до `super.onDestroy()`: отменяются UI jobs, dismiss dialog, очищаются callbacks и безопасно снимается receiver.
- File metadata queries закрывают `Cursor` через `use`; необязательные platform failures больше не проглатываются молча.
- Mi Login очищает sensitive state до async cookie cleanup, игнорирует stale lifecycle callbacks и отключает JavaScript перед WebView destruction.
- Добавлен source-audit regression guard, запрещающий возврат удалённых symbols/resources и direct legacy `getParcelableExtra` branches.
- Добавлены KDoc lifecycle/contracts для Android entry points, ADB/Fastboot transports и ZIP verifier; создан `docs/CODE_GUIDE.md`.

### Ограничение доказательств

Canonical static/safety guards и pure/JVM matrix `27/27` подтверждают local source consistency, но не заменяют Android lint/assemble exact-head CI и hardware retest. Этот audit не объявляет новую прошивку, Sideload transfer или Unlock hardware PASS.

Дата: **2026-07-21**  
База: `V6.0.0-alpha2`  
Результат очистки: `V6.0.0-alpha3`  
Compilation hotfix: `V6.0.0-alpha4`

## Метод

Проверены production Kotlin/XML, ресурсные ссылки, тестовый manifest, CI/build scripts, canonical docs, checksum inventory и связи `MainActivity` ↔ `DeviceViewModel`. Удаление выполнялось только для недоступного, осиротевшего или уже архивированного кода.

## Удалено

- скрытая страница `pageDiagnostics` («Сервис»), на которую больше не было навигации;
- дублирующие старые bindings и неиспользуемые strings этой страницы;
- `DeviceProfileManager`;
- `PartitionInventoryHistory`;
- profile/history attachments из diagnostic ZIP;
- автоматический build journal;
- raw `validation/logs`;
- документы V5 из активной ветки.

## Исправлено

- на alpha3 diagnostic report schema была повышена до `forum-report.v6`;
- в alpha5 полный форумный ZIP-exporter удалён как вне-scope; сохранены compact/trace logs, session summary и санитизированный self-test TXT/JSON;
- documentation/checksum guards больше не требуют удалённые raw logs;
- project guard запрещает возврат Service page, profiles/history и Mi Flash;
- текущий roadmap, safety model, release process и hardware summary переписаны под V6.

## Сохранено после проверки ссылок

- top bar и Home device info;
- ADB/Fastboot transports;
- Terminal;
- Quick Flash inventory/slot/preflight/draft;
- Sideload и recovery verifier;
- Mi Unlock и необходимые account/session classes;
- bounded/sanitised compact/trace logs, session tracker и reports menu с локальным self-test TXT/JSON;
- 19 тестовых модулей, каждый связан с активной функцией или её минимальной safety boundary.

## Подтверждение Android compile hotfix

Alpha4 получила maintainer-confirmed green GitHub Actions run `29832274659` для commit `90871fb`. Это подтверждает Android lint/debug/release для hotfix, но не заменяет аппаратные verdicts. Hardware scenarios по-прежнему требуют нового V6 retest.
## Исправление после первого Android CI

Первый CI run `29829689137` подтвердил static checks и матрицу 19/19, но остановился на `:app:compileDebugKotlin`: после cleanup отсутствовали две private transient-модели, которые используются проверкой Mi Unlock и ADB Sideload. В alpha4 восстановлены только `PendingUnlockVerification` и `PendingSideloadVerification`; удалённый Mi Flash и legacy-подсистемы не возвращались. В `check_project.py` добавлен постоянный regression guard для обеих моделей и их полей.
