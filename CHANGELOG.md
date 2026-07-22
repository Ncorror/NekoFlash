# История изменений NekoFlash

## V6.0.0-alpha5 development baseline — `6.0.0-alpha5-dev-nekoflash` (`217`)

### Recovery-first Quick Flash

- Реализованы pure plan/candidate models, fail-closed topology builder, Recovery-first UI и одноразовый mutation gate.
- Filename используется только как hint; target разрешается по concrete inventory/slot evidence.
- Primary и Expert targets разделены, Expert Mode выключен по умолчанию, legacy multi-flash queue скрыт.
- Один confirmation соответствует одному concrete partition и одному вызову `flashPartitionDetailed`; mutation retry отсутствует.
- Session, URI, размер, SHA-256 и topology evidence повторно проверяются перед execution.
- Baseline Slice E подтверждён GitHub Actions run `29855091700`: static/safety, pure/policy, `lintDebug`, `assembleDebug`, `assembleRelease` — success.

### Android smoke polish

- Welcome permission chips стали действиями, отдельная battery button удалена, risk row кликабельна. Финальный визуальный проход прозрачности/положения панели остаётся открытым.
- Sideload card упрощена; Import/Verify выровнены, жёлтая памятка удалена, pre-verify note больше не показывает ложную зелёную галочку.
- Fastboot DATA card сведена к одному основному self-test; специализированные проверки перенесены в дополнительный dialog, no-device taps журналируются.
- Recovery-first card зафиксирована как защищённый эталон и в smoke-polish не меняется.

### Mi Account / Mi Unlock

- Интерактивный login принимает только точный официальный completion callback `https://unlock.update.miui.com/sts`.
- Background clientSign exchange допускает только exact `/sts` на фиксированных региональных unlock hosts.
- Account `passToken` не отправляется на unlock hosts; сохраняются только ожидаемые service-cookie names.
- Исправлена гонка первого входа: поздний `onPageFinished` больше не может заменить успешную авторизацию stale blocked-host banner.
- Добавлены pure policy/race regression tests. Fresh Android login без перезапуска и новый Android CI для текущего fix остаются обязательными.

### Workflow и документация

- `termux-publish.sh` выполняет только безопасный import/commit/push feature-ветки без локальной сборки и CI.
- `termux-ci.sh` по умолчанию создаёт лёгкий evidence archive без APK; APK скачиваются отдельно по `--with-apk`.
- Python cache исключён из source tree и checksum inventory.
- Каноническая документация пересобрана: tracker сокращён до живого статуса, hardware evidence отделён от планов, stale/противоречивые утверждения удалены.

## V6.0.0-alpha4 — `6.0.0-alpha4-nekoflash` (`216`)

- Восстановлены private transient-модели `PendingUnlockVerification` и `PendingSideloadVerification` после scope cleanup.
- Исправлены Android compilation errors; static guard защищает обе модели и их поля.
- Maintainer-confirmed green GitHub Actions run: `29832274659`, commit `90871fb`.

## V6.0.0-alpha3 — `6.0.0-alpha3-nekoflash` (`215`)

- Проведён полный V6 source audit после удаления Mi Flash.
- Удалены скрытая Service page, `DeviceProfileManager`, `PartitionInventoryHistory`, raw hardware logs и исторические V5 документы из активного дерева.
- Diagnostic report обновлён до schema `forum-report.v6`.
- `TOPBAR-001`, `HOMEINFO-001`, `HOMEACTIONS-001` сохранены.

## V6.0.0-alpha2 — `6.0.0-alpha2-nekoflash` (`214`)

- Закреплена карточка устройства и рабочей папки.
- Добавлены действия «Открыть папку» и «Копировать путь».
- На Home добавлены Terminal, Quick Flash, ADB Sideload и Mi Unlock.

## V6.0.0-alpha1 — `6.0.0-alpha1-nekoflash` (`213`)

- Полный Mi Flash удалён из активной ветки.
- ADB/Fastboot transports, Terminal, Quick Flash, Sideload, Mi Unlock и sanitised logs сохранены.
- Предыдущее состояние архивировано в `archive/full-miflash-v5.9.17` и `v5.9.17-full-miflash`.
