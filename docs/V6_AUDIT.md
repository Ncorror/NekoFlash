# Аудит очистки NekoFlash V6

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

- diagnostic report schema повышена до `forum-report.v6`;
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
- bounded/sanitised logs и reports menu;
- 19 тестовых модулей, каждый связан с активной функцией или её минимальной safety boundary.

## Не подтверждено локально

Android lint/compile/assemble требуют доступного Gradle/SDK или GitHub Actions. Аппаратные verdicts требуют нового V6 retest. Эти статусы нельзя заменять результатами pure/JVM guards.
## Исправление после первого Android CI

Первый CI run `29829689137` подтвердил static checks и матрицу 19/19, но остановился на `:app:compileDebugKotlin`: после cleanup отсутствовали две private transient-модели, которые используются проверкой Mi Unlock и ADB Sideload. В alpha4 восстановлены только `PendingUnlockVerification` и `PendingSideloadVerification`; удалённый Mi Flash и legacy-подсистемы не возвращались. В `check_project.py` добавлен постоянный regression guard для обеих моделей и их полей.
