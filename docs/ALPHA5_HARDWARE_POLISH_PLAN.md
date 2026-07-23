# Alpha5 hardware smoke polish plan

## Цель

Закрыть UX и Mi Account defects, найденные на первых Android smoke tests, не меняя принятую Recovery-first Quick Flash card и её safety flow.

## Защищённая область

`cardQuickFlashRecoveryFirst`, `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001` не меняются в этом этапе. Recovery-first layout считается эталонным.

## Статус задач

| ID | Состояние | Что уже сделано | Что ещё требуется |
|---|---|---|---|
| POLISH-WELCOME-001 | FIXED_CODE | Fullscreen artwork без ScrollView; outer gate прозрачный/контурный и закреплён снизу | Android visual smoke на целевых размерах экрана |
| POLISH-SIDELOAD-001 | FIXED_CODE | Жёлтая памятка удалена, Import/Verify выровнены, pre-verify icon нейтрализован | Android smoke, transfer/cancel/recovery-result retest |
| POLISH-DATA-001 | DONE_CODE | Один основной self-test, advanced dialog, no-device taps в compact log | Fastboot hardware retest |
| UNLOCK-LOGIN-001 | DONE_DEVICE | Exact `/sts` allowlist, bounded service exchange и first-pass race guard подтверждены device smoke | Новый Android CI и regression после log-sanitisation |
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
3. Новый GitHub Actions run для текущего exact head SHA.
4. Welcome visual smoke после fullscreen overlay-gate patch: один экран, без scroll/oversized crop.
5. Sideload pre-verify UI smoke.
6. Mi Account regression после удаления raw account ID из compact log.
7. Только затем Terminal/Sideload/Quick Flash hardware validation.
