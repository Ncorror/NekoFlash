# Recovery-first Quick Flash: план V6.0.0-alpha5

## Цель milestone

Сделать короткий и понятный Quick Flash для отдельных recovery-related образов без возврата полного ROM flasher. Основной сценарий — выбрать образ, увидеть проверяемый target plan, подтвердить его и выполнить ровно одну контролируемую Fastboot mutation.

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

`system`, `vendor`, `product`, `super`, radio/bootloader и полный Fastboot ROM workflow не добавляются на основной экран.

## Пользовательский поток

1. Пользователь выбирает локальный image-файл.
2. Приложение показывает имя, размер и SHA-256.
3. Image inspector выдаёт только подсказку о типе, но не авторизует target автоматически.
4. Partition inventory и slot resolver формируют список существующих совместимых targets.
5. Пользователь явно выбирает target и slot policy.
6. Preflight показывает итоговую команду, устройство, partition, slot, размер и hash.
7. Отдельное confirmation разрешает ровно одну mutation-команду.
8. Результат сохраняется в sanitised log; reboot остаётся отдельным ручным действием.

## Safety-инварианты

- filename или magic image не могут единолично выбрать partition;
- неизвестная topology закрывает mutation, а не синтезирует target;
- A/B suffix используется только при подтверждённой slot metadata;
- legacy A-only device никогда не получает искусственные `_a`/`_b` targets;
- target обязан присутствовать в inventory или пройти bounded point-query;
- ручной partition доступен только в Expert Mode и требует повторного ввода имени;
- `vbmeta` не получает скрытые disable-verification flags;
- нет автоматического retry mutation-команды;
- detach, timeout или transport `BROKEN` закрывают текущий operation;
- один confirmation соответствует одной команде `flash`.

## Архитектурные slices

### Slice A — модель плана (`DONE_CODE`)

Добавлены pure Kotlin модели `QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan`, детерминированный confirmation codec и fail-closed validator. Модель не зависит от Android UI. Один план содержит ровно один concrete partition и аргументы одной команды `flash`; A/B `both` не кодируется как скрытая multi-mutation операция.

### Slice B — topology resolver (`DONE_CODE`)

Добавлен pure `QuickFlashTopologyCandidateBuilder`, который строит candidates только из concrete inventory evidence, проверяет slot mapping через `FastbootSlotResolver` и выдаёт bounded read-only point-query plan для недостающих данных. `PartitionNameResolver` задаёт лишь порядок подсказок и никогда не выбирает target. Unknown topology, archive input и broken session закрываются fail-closed; legacy A-only не получает синтетические `_a`/`_b`.

### Slice C — UI (`DONE_CODE`)

Recovery стал главным target, остальные primary targets представлены отдельными действиями, а `dtbo`, `vbmeta`, `vendor_kernel_boot` и ручное имя скрыты за выключенным по умолчанию Expert Mode. UI выбирает image первым, вычисляет SHA-256 и показывает только concrete candidates из `QuickFlashTopologyCandidateBuilder`; filename остаётся hint. Inventory evidence, candidate selector и confirmation привязаны к одному transport session ID, поэтому detach или смена устройства блокируют план. Legacy multi-flash queue скрыт, выбор `BOTH` отсутствует. `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001` не изменены.

### Slice D — mutation gate (`DONE_CODE`)

Добавлен pure `QuickFlashMutationGate` с одноразовым confirmation ticket. Перед execution повторно проверяются structural plan, transport session, Expert/read-only gates, image URI/size/SHA-256 и exact concrete candidate из текущего inventory. `DeviceViewModel.runConfirmedQuickFlash` использует существующий verified staging lifecycle и вызывает `FastbootProtocol.flashPartitionDetailed` ровно один раз; retry и mutation loop отсутствуют, а протокольный `FastbootMutationSafety` остаётся обязательным.

### Slice E — evidence

Добавить pure tests, static guards, Android lint/assemble и аппаратный retest на восстанавливаемом устройстве.

## Evidence Slice E

GitHub Actions run `29855091700` завершился `success` для PR head `8a6dab5f81dd0ff117b3b6e27e6d528a45900e24`: static/safety checks, pure/policy matrix, `lintDebug`, `assembleDebug` и `assembleRelease` зелёные. APK не входят в обычный CI evidence archive и скачиваются отдельно только для установки или hardware retest.

## Acceptance criteria alpha5

- target plan воспроизводим и сериализуем для confirmation;
- основные и expert targets разделены;
- несуществующий или неоднозначный partition завершается fail-closed;
- A/B и A-only tests покрывают основные targets;
- SHA-256 и размер видны до confirmation;
- ровно одна flash-команда на одно подтверждение;
- static/documentation/safety guards и pure/JVM matrix проходят;
- Android `lintDebug`, `assembleDebug`, `assembleRelease` зелёные;
- реальная прошивка остаётся отдельным hardware gate, а не условием source merge.

## Порядок реализации

1. Slice A с pure tests — `DONE_CODE`.
2. Slice B с inventory/slot regression tests — `DONE_CODE`.
3. Slice C без изменения protected Home components — `DONE_CODE`.
4. Slice D и end-to-end policy tests — `DONE_CODE`.
5. Slice E: Android CI — следующий шаг.
6. Sanitised hardware validation.
