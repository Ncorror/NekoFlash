# Alpha5 hardware smoke polish plan

## Основание

Первый запуск alpha5 на реальном Android-устройстве подтвердил, что Recovery-first Quick Flash визуально устраивает maintainer и не должен меняться. До аппаратной прошивки обнаружены четыре UX/diagnostic задачи и одна regression в Mi Account login.

## Защищённая область

`cardQuickFlashRecoveryFirst`, `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001` не меняются в этом этапе. Recovery-first layout считается эталонным.

## Рабочие задачи

### POLISH-WELCOME-001 — компактный вход

- три status chip остаются видимыми и становятся действиями;
- Files открывает настройки доступа к файлам;
- Notifications открывает permission flow или системные настройки уведомлений;
- Battery открывает настройки battery optimization;
- отдельная большая кнопка батареи удаляется;
- risk acknowledgement переключается нажатием по всей строке;
- панель становится ниже, а hero-композиция получает больше места.

### POLISH-SIDELOAD-001 — компактный Sideload

- жёлтая памятка с лампочкой удаляется;
- Import и Verify имеют одинаковую геометрию и визуальный вес;
- checksum note становится короткой нейтральной строкой;
- transfer/cancel/recovery-result logic не меняется.

### POLISH-DATA-001 — свёрнутая диагностика

- на основном экране остаётся один безопасный Fastboot DATA self-test;
- четыре специализированных инструмента доступны через «Дополнительные тесты»;
- попытка запуска без Fastboot-устройства пишет точную причину в compact log;
- download-only safety invariants сохраняются.

### UNLOCK-LOGIN-001 — официальный completion callback

- login navigation остаётся ограниченной `account.xiaomi.com`;
- разрешается только точный HTTPS completion callback `unlock.update.miui.com/sts`;
- callback не загружается как произвольная web-страница, а завершает cookie extraction;
- ошибки больше не сворачиваются в безымянное «вход отменён»;
- экран показывает причину и даёт повторить вход;
- callback/path/host confusion покрывается pure policy tests.

### LOG-UI-001 — baseline без устройства

Загруженный session summary показал 0 операций, 0 warnings и 0 errors; нажатия diagnostic buttons без transport session не были различимы. После исправления Fastboot DATA actions должны оставлять безопасное событие и понятный отказ без старта operation.

## Проверки

1. XML/static/documentation guards.
2. `mi-account-security` pure test.
3. Полная pure/JVM matrix.
4. GitHub Actions: static, lintDebug, assembleDebug, assembleRelease.
5. Повторный Android smoke test без устройства.
6. Mi Account login retest.
7. Только затем Terminal/Sideload/Quick Flash hardware validation.
