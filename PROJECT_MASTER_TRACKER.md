# NekoFlash — единый трекер проекта

Последнее обновление: **2026-07-22**  
Текущий milestone: **V6.0.0 — alpha5 Recovery-first Quick Flash**  
Версия: **`6.0.0-alpha5-dev-nekoflash`**  
Version code: **`217`**

## Новый чат

Начать с [`docs/AI_START_HERE.md`](docs/AI_START_HERE.md), затем читать этот файл как единственный источник живого статуса. Не восстанавливать старый Mi Flash scope и не переносить статус из исторических документов.

## Последнее подтверждённое доказательство

- Mi Unlock login race fix: successful `/sts` completion is terminal; late WebView callbacks cannot downgrade it to cancellation.
- Текущая development-версия: `6.0.0-alpha5-dev-nekoflash` (`217`).
- GitHub Actions run: `29855091700` (`Build Android APK #14`).
- Event/head branch: `pull_request` / `feature/recovery-first-quick-flash`.
- PR head SHA: `8a6dab5f81dd0ff117b3b6e27e6d528a45900e24`.
- GitHub Actions checkout/build commit: `3057477010f2ac8f1e39c1390a3c74732baab20c` (synthetic PR merge checkout; не подменяет PR head SHA).
- Результат: `success`; static/safety checks, pure/policy matrix, `lintDebug`, `assembleDebug` и `assembleRelease` зелёные.
- Release APK в run unsigned; Android CI не является аппаратным доказательством прошивки.
- Предыдущий alpha4 green run `29832274659` на commit `90871fb` остаётся основанием для annotated tag `v6.0.0-alpha4`.

## Цель продукта

NekoFlash остаётся компактным Android-инструментом с четырьмя основными сценариями: Terminal, Recovery-first Quick Flash, ADB Sideload и Mi Unlock. Верхняя панель подключения и карточка устройства сохраняются функционально; меняется только косметика.

## Статус задач

| ID | Задача | Статус | Следующее доказательство |
|---|---|---|---|
| SCOPE-001 | Удалить полный Mi Flash из активной ветки | DONE_CODE | Архив V5.9.17 и guard не допускают возврата файлов |
| AUDIT-001 | Удалить хвосты, исторический груз и осиротевший код | DONE_CI | Alpha4 Android CI green |
| ALPHA4-CI | Подтвердить compile hotfix | DONE_CI | Tag `v6.0.0-alpha4` на green commit |
| CONTEXT-001 | Самодостаточный handoff для нового чата | DONE_CODE | `docs/AI_START_HERE.md` и этот tracker входят в source ZIP |
| TERMUX-001 | Раздельные Termux push и CI scripts | DONE_CI | Push-only подтверждён; CI evidence собирается без APK по умолчанию |
| TOPBAR-001 | Сохранить функциональное поведение верхней панели | DONE_CODE | Android UI smoke test |
| HOMEINFO-001 | Сохранить карточку устройства и рабочей папки | DONE_CODE | Android UI smoke test |
| HOMEACTIONS-001 | Четыре главных перехода на Home | DONE_CODE | Android UI smoke test |
| TERMINAL-001 | Проверить ADB/Fastboot terminal на устройстве | OPEN | Команды read-only и экспорт лога |
| POLISH-WELCOME-001 | Упростить welcome permissions/risk gate | DONE_CODE | Android smoke test кликабельных status chips |
| POLISH-SIDELOAD-001 | Упростить ADB Sideload card | DONE_CODE | Android UI: нейтральная note до verify + transfer/cancel retest |
| POLISH-DATA-001 | Свернуть Fastboot DATA diagnostics | DONE_CODE | Без-device log и Fastboot hardware retest |
| UNLOCK-LOGIN-001 | Исправить Mi Account completion и unlockApi `/sts` exchange | FIXED_CODE | Fresh login без перезапуска и без stale blocked-host banner |
| FLASH-001 | Recovery-first Quick Flash | DONE_CI | Sanitised hardware retest Terminal/Sideload/Quick Flash |
| SIDELOAD-001 | Подтвердить ADB Sideload в V6 | RETEST_REQUIRED | ZIP transfer, cancel, recovery result |
| UNLOCK-001 | Провести отдельный аудит Mi Unlock | IN_PROGRESS | Retest официального Mi Account callback и затем разделение unlock flows |
| TEST-001 | Сокращённая релевантная test matrix | DONE_CI | Alpha5: local 23/23 + run 29855091700 Android CI green |
| RELEASE-001 | Signing и аппаратный release gate | OPEN | После стабилизации alpha/beta |

## Что намеренно сохранено

- USB/ADB/Fastboot transport;
- Terminal и read-only self-test;
- Quick Flash draft, partition inventory, slot resolver и минимальный preflight;
- Sideload и recovery result verifier;
- Mi Account компоненты, которые используются Xiaomi unlock flow;
- sanitization и экспорт диагностических отчётов;
- верхняя панель и карточка устройства.

## Локальное evidence alpha5

- Slice A реализован в `QuickFlashPlan.kt`: `QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan`, детерминированный confirmation codec и fail-closed validator.
- Pure module `quick-flash-plan` покрывает primary/expert targets, A-only/A/B concrete candidates, неоднозначность, отсутствие evidence, ручное подтверждение, hash/size и ровно одну mutation-команду.
- Slice B реализован в `QuickFlashTopologyCandidateBuilder.kt`: builder объединяет inventory, slot resolver, bounded point-query и filename classification только как hint.
- Pure module `quick-flash-topology` покрывает legacy A-only, A/B, unknown topology, point-query evidence, expert/manual gates, archive redirect и broken-session fail-closed.
- Python cache исключён через `.gitignore`; Termux publisher остаётся push-only и не запускает локальную сборку либо CI.
- Slice A/B имеют `DONE_CODE` и локальное pure evidence; Android lint/assemble и аппаратная прошивка этим не подтверждены.
- Slice C реализован в `QuickFlashUiPolicy.kt`, `activity_main.xml` и `MainActivity`: Recovery расположен первым, primary/expert targets разделены, Expert Mode выключен по умолчанию.
- Новый UI сначала выбирает image, вычисляет SHA-256, затем показывает только concrete candidates из `QuickFlashTopologyCandidateBuilder.buildFromInventory`; слот `BOTH` не предлагается.
- Legacy multi-flash queue сохранён только для совместимости внутренних ID/state, но скрыт из активного Recovery-first UI.
- Inventory evidence, candidate selector и confirmation связаны одним transport session ID; смена USB/Fastboot-сессии блокирует план fail-closed.
- Slice D реализован в `QuickFlashMutationGate.kt` и `DeviceViewModel.runConfirmedQuickFlash`: confirmation ticket одноразовый, а session, image URI/size/SHA-256 и concrete topology candidate перепроверяются внутри operation.
- Recovery-first UI больше не вызывает legacy `runFlash`; gate выдаёт ровно одну команду `flash <partition>`, запрещает retry и передаёт её в единственный вызов `FastbootProtocol.flashPartitionDetailed`.
- Существующие `FastbootFlashPreparationPolicy`, private staging lifecycle и протокольный `FastbootMutationSafety` сохранены; detach/BROKEN после staging блокируют flash до отправки mutation-команды.
- Полная локальная pure/JVM matrix после Slice D: `23/23`.
- Slice E подтверждён GitHub Actions run `29855091700`: static/safety, pure/policy matrix, `lintDebug`, `assembleDebug` и `assembleRelease` завершились `success` для PR head `8a6dab5f81dd0ff117b3b6e27e6d528a45900e24`.
- CI collector сохраняет metadata/logs/reports без APK по умолчанию; APK доступны только через `scripts/termux-ci.sh --with-apk` и отдельный архив.
- Android CI не подтверждает реальную прошивку; hardware gate остаётся открытым.
- Первый Android smoke test зафиксировал Recovery-first UI как неизменяемый эталон.
- Welcome gate уплотнён: status chips ведут в свои settings, отдельная battery button удалена, risk row кликабельна.
- Sideload memo card удалена; Import/Verify выровнены, checksum note нейтральна до фактической проверки и не показывает ложную зелёную галочку.
- Fastboot DATA card показывает один основной self-test; specialized diagnostics скрыты в отдельном dialog, а no-device taps журналируются.
- Android smoke build `0747c4ec72e3.29866798716` подтвердил вход в Mi Account, но token exchange блокировался policy на `https://unlock.update.miui.com/sts` после успешного получения user ID.
- Android build `8d9923ec0878.29870485300` подтвердил успешный login/token exchange и получение `serviceToken`/`unlockApi_*` cookies. При первом проходе UI иногда показывал stale blocked-host banner: поздний `onPageFinished` повторно обрабатывал уже поглощённый `/sts` callback; после restart сохранённые cookies обходили гонку.
- Mi Login WebView принимает только точный completion callback `https://unlock.update.miui.com/sts`; background clientSign exchange допускает только exact `/sts` на пяти известных региональных unlock hosts и не передаёт account `passToken` на `miui.com`. Successful completion теперь terminal: поздние WebView callbacks не могут заменить результат на cancellation.

## Текущий следующий шаг

План smoke-polish находится в [`docs/ALPHA5_HARDWARE_POLISH_PLAN.md`](docs/ALPHA5_HARDWARE_POLISH_PLAN.md). Recovery-first Quick Flash остаётся защищённым и не меняется.
Основной Recovery-first scope и acceptance criteria остаются в [`docs/RECOVERY_FIRST_PLAN.md`](docs/RECOVERY_FIRST_PLAN.md).
Safety-инварианты остаются в [`docs/SAFETY_MODEL.md`](docs/SAFETY_MODEL.md), canonical guard запускается через `scripts/check-documentation.py`, архивная база — `archive/full-miflash-v5.9.17`.

1. Опубликовать focused Mi Login first-pass race fix в `feature/recovery-first-quick-flash` через push-only Termux workflow.
2. Получить новый зелёный GitHub Actions evidence для static/pure/lint/debug/release.
3. На Android очистить Mi Login cookies через кнопку выхода либо выполнить fresh install, затем пройти login один раз без перезапуска приложения: blocked-host banner не должен появляться, а `serviceToken`/`unlockApi_*` cookies должны быть получены в том же проходе.
4. Проверить Sideload: до выбора/verify ZIP не должно быть зелёной success-индикации.
5. После fresh-login/Sideload retest вернуться к облегчению welcome panel и затем к Terminal/Sideload/Quick Flash hardware gate.

Реальная прошивка всегда требует подключённого Fastboot-устройства, существующего раздела, корректного slot, проверенного файла и явного подтверждения. Автоматического повторения mutation-команд нет.
