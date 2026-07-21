# Архитектура NekoFlash V6

## UI

`MainActivity` содержит существующую верхнюю панель, Home-карточку устройства и рабочие окна Terminal, Quick Flash, ADB/Sideload и Unlock. `DeviceViewModel` остаётся единым lifecycle owner подключения и операций. Новые дублирующие device-state stores запрещены.

## Transport

- `AdbProtocol` — ADB authentication, packet dispatcher, shell/sync/sideload.
- `FastbootProtocol` — Fastboot getvar, DATA transport и отдельные partition operations.
- USB attach/detach и session generation принадлежат существующему connection layer.

## Quick Flash

- `QuickFlashTarget`, `QuickFlashCandidate` и `QuickFlashPlan` образуют pure confirmation-ready model; `QuickFlashPlanValidator` принимает только один concrete partition с read-only evidence.
- `QuickFlashTopologyCandidateBuilder` объединяет `FastbootPartitionInventory`, bounded probe planner, `FastbootSlotResolver` и filename hint в read-only candidate result; он не выбирает target и не выполняет mutation.
- `QuickFlashUiPolicy` фиксирует Recovery-first порядок и hidden-by-default Expert Mode. `MainActivity` использует `buildFromInventory`, показывает только concrete candidates, создаёт confirmation-ready `QuickFlashPlan` и одноразовый `QuickFlashMutationGate.ConfirmationTicket`.
- `QuickFlashMutationGate` остаётся pure boundary перед mutation: повторно связывает confirmation с plan fingerprint, transport session, image identity и concrete candidate. `DeviceViewModel.runConfirmedQuickFlash` поглощает ticket один раз, использует существующий private staging и делает ровно один вызов `FastbootProtocol.flashPartitionDetailed`.
- Legacy multi-flash queue не входит в активный Recovery-first UI и скрыт; один UI plan соответствует одному concrete partition.
- `FastbootPartitionInventory` принимает только concrete bootloader evidence, а probe planner формирует ограниченные read-only запросы для недостающих данных.
- `FastbootSlotResolver` проверяет точное соответствие concrete partition и A/B policy; unknown topology не выдаёт candidate.
- `FastbootFlashPreparationPolicy` и `PreflightValidator` выполняют минимальный preflight.
- `FlashOperationDraft` хранит только восстановимый UI draft; выполнение требует свежей проверки и подтверждения.

## Sideload

`AdbProtocol` передаёт ZIP, `PackageIntegrityVerifier` проверяет локальный пакет, `RecoveryInstallVerifier` анализирует результат recovery.

## Unlock

`MiUnlockClient` и связанные Mi Account компоненты изолированы от Quick Flash. До аппаратного аудита они не считаются полностью подтверждённым flow.

## Logs

Compact/trace storage ограничен по размеру. `ReportSanitizer` удаляет serial, пути и длинные идентификаторы. `ForumReportManager` создаёт schema `forum-report.v6` без профилей и inventory history.

## Запрещённые зависимости

Mi Flash production classes, скрытая Service page, device profiles и inventory history не должны возвращаться в V6. Это проверяет `check_project.py`.
