# NekoFlash — Core Architecture

## 1. Архитектурная модель

```text
Presentation / Device Workspace / Terminal
                 |
          Application Use Cases
                 |
          Operation Engine
          /      |       \
        ADB   Fastboot   Recovery
         |        |         |
         +---- Session / Target ----+
                       |
                  USB Transport

Side systems:
Artifact I/O | Diagnostics/Evidence | Vendor workflows | Persistence
```

Ключевое правило: **protocol engines моделируют протоколы, а не набор экранов приложения**.

## 2. Target и Physical Session — разные сущности

Нужны как минимум:

- `TargetId` — логическая identity устройства по доступным serial/USB/ADB/Fastboot признакам;
- `SessionGeneration` — конкретное физическое подключение/claim;
- `TargetMode` — Android/ADB, Recovery, Sideload, Bootloader Fastboot, fastbootd, Unknown;
- `TransportHandle` — ресурс текущей generation.

Каждый detach/attach/re-enumeration создаёт новую generation. Старый handle после detach считается terminal/poisoned и не «оживает».

Нельзя строить архитектуру вокруг одного mutable `currentDevice/currentUsbConnection`.

## 3. USB subsystem

Ответственность USB слоя:
- discovery;
- interface/endpoint classification по descriptors;
- Android USB permission;
- claim/release;
- bulk/request/native I/O;
- timeout/cancel/shutdown semantics;
- generation ownership;
- transport metrics/diagnostics.

USB слой не должен знать, что `flash:boot` «опасен», что Mi Unlock — Xiaomi feature, или какую кнопку нажал пользователь.

Native backend допускается для performance/correctness-sensitive I/O, но JNI API должен оставаться transport-oriented.

## 4. ADB architecture

ADB — асинхронный многоканальный protocol. Базовая модель:

```text
AdbUsbTransport
  - CNXN/AUTH
  - packet framing
  - one physical IN reader
  - serialized writer
        |
        v
AdbStreamRouter
  - localId registry
  - remoteId binding
  - per-stream bounded mailbox
  - OPEN/OKAY/WRTE/CLSE routing
  - lifecycle/backpressure
        |
        +--> Generic AdbServiceChannel
        |      +--> Shell v2 / legacy shell
        |      +--> Exec
        |      +--> Sync
        |      +--> Package install
        |      +--> Reboot/services
        |      +--> Raw service
        |
        +--> RecoveryEvidenceClient
        +--> specialized Sideload transport
```

`openService(destination)` — фундаментальная capability. Typed clients строятся поверх неё.

Нельзя снова делать одну общую packet queue, где session закрывает пакет только потому, что он принадлежит другому stream.

Нужно учитывать negotiated ADB features/max payload и не привязывать весь future core к единственному старому flow-control предположению.

## 5. Shell/Terminal architecture

Shell API разделяет:
- non-interactive command;
- interactive PTY shell;
- `shell,v2` stdout/stderr/exit;
- legacy fallback;
- stdin/EOF/signals/control keys;
- terminal resize/window size.

Terminal emulator — отдельный UI/component concern. Он не заменяет ADB shell protocol layer.

## 6. Fastboot architecture

Fastboot — host-driven synchronous transaction model.

Один engine должен моделировать:
- command write;
- `INFO`;
- `TEXT`;
- `OKAY`;
- `FAIL`;
- `DATA OUT`;
- `DATA IN`;
- timeout/transport loss/ambiguous mutation.

Все потребители используют **один и тот же engine**:
- typed Fastboot UI;
- Quick Flash/plan;
- raw console;
- Mi Unlock/vendor workflows;
- diagnostics.

Никакой product whitelist/denylist внутри transaction engine.

Bootloader Fastboot и fastbootd должны быть различимы в TargetMode/capabilities.

## 7. Recovery / Sideload

Recovery layer отвечает за:
- mode identification;
- bounded logs/evidence access через generic ADB services;
- Sideload state machine;
- pre-Sideload baseline;
- transfer;
- physical reconnect requirement при нужной recovery semantics;
- post-reconnect correlation;
- install verdict.

Transfer completion не равен install success.

Sideload может иметь specialised request-driven data service, но это **специализированный protocol client**, а не повод урезать общий ADB core.

## 8. Operation Engine

Операция содержит:

```text
OperationId
TargetId
SessionGeneration
Intent
OperationSpecificState
MutationBoundary
Progress
Outcome
EvidenceRefs
```

У `FastbootFlash`, `AdbPull`, `Sideload`, `InstallApk`, `MiUnlock` — собственные state machines.

Не должно быть одного глобального Stage enum, который случайно блокирует все остальные операции.

## 9. Artifact I/O

Core abstractions:

```text
ArtifactSource
  size: Long?
  seekability
  persistence
  openSequential()
  openRandomAccess()?

ArtifactSink
  streaming write
  partial/atomic commit semantics
```

Никакого whole-ROM `ByteArray`. Размеры — 64-bit. SAF URI не считается seekable автоматически.

## 10. Diagnostics/Evidence

Каждый слой публикует structured events:
- target/session;
- USB topology;
- ADB handshake/features/streams;
- Fastboot transcript;
- operation stages/byte accounting;
- Recovery correlation;
- vendor flow state.

Diagnostics не должны зависеть от UI string parsing.

## 11. Vendor architecture / Mi Unlock

Vendor workflow — orchestrator поверх:
- public Device/Target API;
- generic ADB/Fastboot API;
- generic Artifact/Operation system;
- отдельного vendor/network client;
- persistence/evidence.

Запрещено создавать второй `XiaomiUsbManager`, второй Fastboot engine или специальные USB hacks внутри generic transport без доказанной quirk-абстракции.

## 12. Предлагаемая модульная структура

Точные Gradle module names можно скорректировать после bootstrap, но границы должны быть примерно такими:

```text
:app
:core:model
:core:operation
:core:diagnostics
:core:artifact
:usb:api
:usb:android
:usb:native
:protocol:adb
:protocol:fastboot
:protocol:recovery
:feature:device
:feature:adb
:feature:fastboot
:feature:recovery
:feature:terminal
:feature:operations
:feature:diagnostics
:feature:miunlock
:vendor:xiaomi
```

Не дробить ради дробления. Модуль создаётся, когда у него есть реальная ownership boundary и независимый контракт.
