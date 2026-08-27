# NekoFlash — Operations, Storage & Lifecycle

## 1. Длительная операция не принадлежит экрану

Activity/ViewModel не должны быть владельцами физического Flash/Sideload/Pull lifetime.

Длительные операции выполняются application-scoped engine/service. UI подписывается на state.

Для длительного взаимодействия с USB target проект должен использовать подходящий Android foreground service type для connected device interaction и соблюдать актуальные platform requirements.

## 2. Operation record

Минимальная модель:

```text
OperationId
CreatedAt
Intent
TargetId
StartedSessionGeneration
StateMachineState
MutationBoundary
Progress
Artifacts
Outcome
PeerResponses
EvidenceRefs
```

Persistence нужна для history/evidence и честного восстановления после process death, а не для магического «resume flash с середины».

## 3. Process death

После смерти процесса:
- UI восстанавливает record;
- определяет текущий physical target/session;
- не заявляет, что old transaction продолжился;
- если операция была после mutation boundary и final state неизвестен — показывает `Unknown/Needs verification`;
- возобновление допускается только как явно определённый протоколом recovery workflow.

## 4. Cancellation

Cancellation policy operation-specific.

`AdbPull` может безопасно остановить локальное чтение.
`Fastboot DATA OUT` после ambiguous transport failure может иметь Unknown semantics.
`Sideload` после mutation boundary не должен притворяться безопасно отменённым.

Никакого общего `if stage == WRITING then cannotCancel` для всех типов операций.

## 5. Concurrency

Нужно явное resource ownership:
- один Fastboot transaction на transport;
- несколько ADB logical streams допустимы;
- mutating operation может иметь exclusive target lock;
- diagnostics/read-only streams допускаются только если protocol/operation policy это позволяет;
- Mi Unlock orchestrator не обходит locks напрямую.

Locks описывают техническое владение ресурсом, а не «права пользователя».

## 6. ArtifactSource

Требования:
- `Long` size;
- sequential streaming;
- optional random access;
- capability `seekable/non-seekable/unknown`;
- source identity/version where possible;
- stability checks для mutating transfer.

SAF `content://` не считается обычным seekable file автоматически. Если Sideload/другой protocol требует random access, non-seekable source stage-ится в app-managed temporary file с progress/hash/space validation.

## 7. ArtifactSink

Для pull/fetch/log export:
- streaming writes;
- `.partial`/temporary destination где возможно;
- exact expected/actual byte count;
- fsync/close errors учитываются;
- final commit/rename только после доказанного success;
- при SAF provider без atomic rename semantics UI/evidence должен честно отражать ограничения.

## 8. Hashing

SHA-256 вычисляется streaming. Hash не требует загрузки файла в память.

Hash используется для:
- artifact identity;
- preflight/evidence;
- проверки staged source;
- диагностики.

Нельзя утверждать remote verification только потому, что local SHA известен.

## 9. Большие файлы

Запрещены в core:
- `readBytes()` для ROM image;
- `ByteArray(fileSize)`;
- 32-bit length arithmetic;
- ZIP fully in memory;
- progress на основе `Int` bytes.

Все byte counters/offsets/sizes — 64-bit там, где wire protocol не задаёт более узкий explicit field.

## 10. Notifications

Foreground operation notification показывает минимум:
- NekoFlash;
- target identity;
- operation;
- progress/state;
- безопасное действие Cancel только если current operation реально cancellable.

Notification не должна предлагать «Cancel», если engine уже пересёк irreversible boundary и не может честно обещать cancellation semantics.
