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

### Slice A — модель плана

Добавить pure Kotlin модели `QuickFlashTarget`, `QuickFlashCandidate`, `QuickFlashPlan` и fail-closed validator. Модель не должна зависеть от Android UI.

### Slice B — topology resolver

Объединить `FastbootPartitionInventory`, `FastbootSlotResolver`, bounded point-query и `PartitionNameResolver` в read-only candidate builder.

### Slice C — UI

Сделать Recovery главным target, остальные основные targets отдельными действиями, а expert targets скрыть за явным переключателем. Сохранить `TOPBAR-001`, `HOMEINFO-001` и `HOMEACTIONS-001`.

### Slice D — mutation gate

Передать подтверждённый `QuickFlashPlan` в существующий flash service без обхода `FastbootMutationSafety` и без повторного staging.

### Slice E — evidence

Добавить pure tests, static guards, Android lint/assemble и аппаратный retest на восстанавливаемом устройстве.

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

1. Slice A с pure tests.
2. Slice B с inventory/slot regression tests.
3. Slice C без изменения protected Home components.
4. Slice D и end-to-end policy tests.
5. Android CI.
6. Sanitised hardware validation.
