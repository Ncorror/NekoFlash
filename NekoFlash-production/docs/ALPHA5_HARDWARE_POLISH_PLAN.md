# Alpha5 hardware smoke polish plan

## Цель

Закрыть UX и Mi Account defects, найденные на первых Android smoke tests, не меняя принятую Recovery-first Quick Flash card и её safety flow.

## Защищённая область

`cardQuickFlashRecoveryFirst`, `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001` не меняются в этом этапе. Recovery-first layout считается эталонным.

## Статус задач

| ID | Состояние | Что уже сделано | Что ещё требуется |
|---|---|---|---|
| POLISH-WELCOME-001 | DONE_DEVICE | Fullscreen artwork без ScrollView; outer gate прозрачный/контурный и закреплён снизу | Защищать эталонный экран от регрессии |
| POLISH-SIDELOAD-001 | DONE_DEVICE_UI | Жёлтая памятка удалена, Import/Verify выровнены, pre-verify icon нейтрализован | Реальный transfer/cancel/recovery-result retest |
| POLISH-DATA-001 | IN_PROGRESS | На `vayu` DATA matrix и 128 MiB ASYNC/SYNC qualification прошли; найден ложный cleanup `failed/cancelled` после diagnostic-only PASS | CI + device regression staging/evidence lifecycle |
| UNLOCK-LOGIN-001 | DONE_DEVICE | Exact `/sts` allowlist, bounded service exchange, first-pass race guard и log-sanitisation подтверждены exact-head smoke | Полный Fastboot/server unlock остаётся отдельным gate |
| LOG-UI-001 | DONE_DEVICE | No-device Fastboot DATA taps различимы и безопасно отклоняются | Сохранить поведение при hardware retest |

## POLISH-WELCOME-001

- сохранить три status chips и их переходы в собственные system settings;
- не возвращать отдельную большую кнопку battery settings;
- сохранить обязательное подтверждение рисков;
- нижняя панель использует почти прозрачный outer surface и отдельный контур;
- artwork занимает весь content viewport без вертикальной прокрутки и без min-height zoom regression; панель закреплена снизу как прозрачный overlay;
- логика permissions и допуска к приложению не меняется.

## POLISH-SIDELOAD-001

- до выбора и фактического verify ZIP использовать только нейтральную подсказку;
- зелёный success-status разрешён только после реального integrity result;
- transfer, cancel и recovery-result logic не менять;
- импортированный ZIP не считать recovery/OTA пакетом только по расширению.

## POLISH-DATA-001

- основной экран показывает один безопасный Fastboot DATA self-test;
- staging/qualification/matrix/content probes остаются в «Дополнительных тестах»;
- без Fastboot session действие завершается до operation и пишет точную sanitised причину;
- successful diagnostic-only PASS не создаёт mutation authorization и не удаляет уже qualified ASYNC/SYNC staging для того же SHA-256/current USB generation;
- standalone diagnostic-only staging удаляется с нейтральной причиной, а failure/cancel по-прежнему инвалидирует evidence fail-closed;
- download-only safety invariants сохраняются.

## UNLOCK-LOGIN-001

- top-level interactive login ограничен `account.xiaomi.com`;
- completion распознаётся только для точного HTTPS callback `unlock.update.miui.com/sts`;
- background clientSign exchange допускает только exact `/sts` на фиксированных regional unlock hosts;
- account `passToken` не передаётся на unlock hosts;
- успешное completion — terminal state: поздние WebView callbacks не могут заменить его cancellation/error;
- в logs не сохраняются account ID, tokens или cookie values;
- full Mi Unlock flow остаётся отдельным audit и не считается подтверждённым одним login.

## Acceptance sequence

1. Canonical documentation/static/safety guards.
2. Pure/JVM matrix `23/23`.
3. Exact-head `b220d48b796d09b13974d8dc39d090efbc2afb55` Android CI — PASS.
4. Welcome visual smoke — PASS и защищён как эталон.
5. Sideload neutral pre-verify UI — PASS.
6. Mi Account first-pass и удаление raw account ID — PASS.
7. `vayu` DATA hardware session — transport PASS, staging lifecycle defect найден и исправлен локально.
8. Следующий этап: exact-head CI, A-only inventory/staging regression, затем Terminal/Sideload/Quick Flash hardware validation и отдельный безопасный Mi Unlock preflight.
