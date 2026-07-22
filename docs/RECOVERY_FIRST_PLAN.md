# Recovery-first Quick Flash: план V6.0.0-alpha5

## Статус

Slices A–D реализованы. Baseline Slice E прошёл Android CI run `29855091700`; hardware validation остаётся открытой. Последующие alpha5 hardware-polish/Mi Login изменения требуют нового Android CI, но не меняют Recovery-first architecture.

## Цель milestone

Сделать короткий Quick Flash для одного recovery-related образа без возврата полного ROM flasher: выбрать image, увидеть проверяемый concrete target plan, подтвердить его и выполнить ровно одну контролируемую Fastboot mutation.

## Видимые targets

Основные:

- `recovery`;
- `boot`;
- `init_boot`;
- `vendor_boot`.

Expert Mode:

- `dtbo`;
- `vbmeta`;
- `vendor_kernel_boot`;
- ручной partition name.

`system`, `vendor`, `product`, `super`, radio/bootloader и полный Fastboot ROM workflow не входят в обычный Quick Flash.

## Пользовательский поток

1. Выбор локального image-файла.
2. Показ имени, размера и SHA-256.
3. Image inspector даёт только hint и не авторизует target.
4. Inventory и slot resolver строят список существующих concrete targets.
5. Пользователь явно выбирает один target/slot.
6. Preflight показывает итоговую команду и evidence.
7. Отдельное confirmation разрешает одну mutation-команду.
8. Результат сохраняется в sanitised log; reboot остаётся отдельным ручным действием.

## Safety-инварианты

- filename/magic не выбирают partition;
- unknown topology закрывает mutation;
- A/B suffix используется только при подтверждённой slot metadata;
- A-only device не получает искусственные `_a`/`_b`;
- target присутствует в inventory либо подтверждён bounded point-query;
- manual partition доступен только в Expert Mode и требует повторного ввода;
- `vbmeta` не получает скрытые disable-verification flags;
- один confirmation соответствует одной `flash`-команде;
- detach, timeout или `BROKEN` закрывают operation;
- mutation retry отсутствует.

## Архитектурные slices

### Slice A — plan model (`DONE_CODE`)

`QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan`, deterministic confirmation codec и fail-closed validator. Один план содержит один concrete partition.

### Slice B — topology builder (`DONE_CODE`)

`QuickFlashTopologyCandidateBuilder` объединяет concrete inventory, `FastbootSlotResolver`, bounded point-query и filename hint. Unknown/archive/broken scenarios завершаются fail-closed.

### Slice C — Recovery-first UI (`DONE_CODE`)

Recovery расположен первым; primary/expert targets разделены; Expert Mode выключен по умолчанию. UI показывает только concrete candidates. `BOTH` и legacy multi-flash queue отсутствуют в активном flow. `TOPBAR-001`, `HOMEINFO-001`, `HOMEACTIONS-001` не изменены.

### Slice D — one-shot mutation gate (`DONE_CODE`)

`QuickFlashMutationGate` связывает confirmation с plan fingerprint, session, image identity и current concrete candidate. `DeviceViewModel.runConfirmedQuickFlash` использует существующий staging и выполняет один `FastbootProtocol.flashPartitionDetailed` без retry.

### Slice E — evidence (`DONE_CI`, hardware pending)

Run `29855091700` подтвердил static/safety, pure/policy matrix, `lintDebug`, `assembleDebug`, `assembleRelease` для PR head `8a6dab5f81dd0ff117b3b6e27e6d528a45900e24`.

Это evidence для Recovery-first baseline, а не для последующих smoke-polish/Mi Login commits. Реальная прошивка и новый exact-head CI остаются отдельными gates.

## Acceptance criteria

- plan воспроизводим и сериализуем;
- primary/expert targets разделены;
- отсутствующий/неоднозначный partition закрывается fail-closed;
- A/B и A-only covered pure tests;
- SHA-256 и размер видны до confirmation;
- одна flash-команда на одно confirmation;
- static/documentation/safety и pure/JVM проходят;
- Android lint/debug/release зелёные для exact source head;
- аппаратная прошивка подтверждается отдельным sanitised evidence.

## Оставшийся порядок

1. Новый Android CI для текущего alpha5 source.
2. Fresh Mi Login/Sideload/welcome smoke по [`ALPHA5_HARDWARE_POLISH_PLAN.md`](ALPHA5_HARDWARE_POLISH_PLAN.md).
3. Sanitised Terminal и Sideload hardware validation.
4. Контролируемый Quick Flash на восстанавливаемом устройстве.
