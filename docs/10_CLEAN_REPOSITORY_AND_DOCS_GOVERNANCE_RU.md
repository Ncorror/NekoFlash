# NekoFlash — Clean Repository & Documentation Governance

## 1. Один production path

После завершённой миграции не должно существовать:
- `FooOld` + `FooNew`;
- `LegacyFoo` «на всякий случай»;
- `TempTransport`;
- скрытого fallback на старый engine;
- двух Fastboot implementations для разных экранов;
- второго USB stack для Mi Unlock.

Исторический код не распаковывается в active source tree. Frozen snapshots могут храниться только как явно отделённые immutable archives в `reference/archives/` с SHA-256 manifest.

## 2. Никаких хвостов

Запрещены в active repository без конкретной причины:
- `.orig`, `.bak`, `.rej`, editor temp;
- commented-out implementation blocks;
- production stubs;
- TODO/FIXME/HACK без issue/decision;
- abandoned experimental modules;
- duplicate resources;
- checkpoint ZIP внутри active source tree.

Исключение: `reference/archives/` — специальная immutable historical/reference zone, не production source tree.

Executable checks живут в `scripts/ci/` и запускаются GitHub Actions: repository hygiene, localization contract и Kotlin style/complexity/module boundaries (конфигурация последнего — в `config/detekt/`). Они являются реализацией этих правил, но не должны расширяться в неясные regex-gates без конкретного documented invariant.

## 3. Reference archives

`reference/archives/` хранит проверенные frozen snapshots Legacy, A2 и founding canonical package.

Правила:
- архив после commit не заменяется и не редактируется;
- integrity фиксируется в `reference/SHA256SUMS`;
- новый snapshot добавляется новым датированным файлом;
- production code никогда не импортирует эти ZIP как runtime/build dependency;
- active canonical docs живут в `/docs`, а не внутри snapshot ZIP.

## 4. Documentation hierarchy

Активных нормативных документов должно быть мало.

Предлагаемая структура нового repository:

```text
README.md
/docs/
  PRODUCT_CHARTER.md
  ARCHITECTURE.md
  PROTOCOL_INVARIANTS.md
  CAPABILITY_MATRIX.md
  UI_UX_BRAND.md
  ROADMAP.md
  HARDWARE_EVIDENCE.md
  adr/
```

Этот стартовый пакет подробный специально для bootstrap. После переноса в repo его можно консолидировать в указанный небольшой canonical set **без потери решений**.

Любое изменение поведения, architecture boundary, gate/status, CI policy или project workflow обновляет canonical docs **в том же changeset**, что и код/конфигурацию.

## 5. ADR

ADR создаётся для решения, которое:
- меняет core boundary;
- трудно отменить;
- имеет разумные альтернативы;
- влияет на protocol/correctness/capability policy.

Не нужен ADR для каждой кнопки или rename.

## 6. Historical docs

Legacy/A2 docs:
- не копируются в active `/docs` нового repo;
- при необходимости хранятся внешне/frozen repo/evidence archive;
- ссылки на них помечаются `historical/reference`, не `current policy`.

## 7. Recovery / handoff

Автоматический recovery bundle нового проекта должен включать:
- canonical current docs;
- HEAD/branch/status;
- source snapshot;
- CI reports;
- hardware evidence index;
- active known issues/gates;
- manifest SHA256.

Он **не должен** иметь hardcoded старый `DEVELOPMENT_CHECKPOINT_TEMPORARY.md` или другой obsolete prompt.

`99_RECOVERY_HANDOFF_RU.md` этого пакета — seed нового handoff contract.

## 8. Code review policy

Review каждого core PR спрашивает:
- Добавляет ли change artificial capability restriction?
- Не спрятан ли policy inside transport?
- Сохраняется ли real peer response?
- Есть ли ownership/cancel/error semantics?
- Не создаётся ли второй implementation path?
- Есть ли failure tests?
- Нужно ли hardware proof?

## 9. Clean Definition of Done

Работа не считается завершённой, если:
- feature работает только по happy path;
- старый путь всё ещё вызывается;
- документация говорит одно, код — другое;
- diagnostics не позволяют понять failure;
- есть временная заглушка «потом доделаем» в production path;
- ограничение возможностей объясняется только словом «безопасность».
