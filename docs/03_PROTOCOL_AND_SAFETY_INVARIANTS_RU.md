# NekoFlash — Protocol & Safety Invariants

## 1. Главная граница

**Correctness protection не является capability restriction.**

NekoFlash защищает целостность transport/protocol state и честность результата. Он не защищает профессионального пользователя от доступа к валидным protocol capabilities.

## 2. Четыре класса решений

### A. Hard invariant
Примеры:
- malformed/truncated protocol frame;
- stale USB generation;
- потерян stream/transaction ownership;
- peer DATA length не соответствует договорённому payload;
- ambiguous mutating write;
- source изменился так, что exact byte promise больше нельзя доказать.

Действие: stop/fail/unknown. Override пользователем здесь недопустим, потому что нарушается техническая достоверность.

### B. Device authority
Примеры:
- bootloader/device security state, включая подтверждённый `LOCKED`;
- AVB/OEM restriction;
- ADB authorization отсутствует;
- Fastboot `FAIL`;
- Recovery не поддерживает service;
- vendor server отклоняет Mi Unlock.

Действие: показать реальный ответ/ограничение устройства. Не подменять его собственной authorization системой.

### C. Advisory
Примеры:
- lock state `UNKNOWN`/unsupported/contradictory;
- неизвестный `max-download-size`;
- неизвестный partition size;
- unusual/critical partition;
- низкая батарея;
- нестабильный device quirk;
- неизвестная slot topology.

Действие: warning/preflight/override. Не hidden deny.

### D. User intent
Примеры:
- erase userdata;
- format;
- flash critical partition;
- `flash:<partition>` при подтверждённом `LOCKED`;
- raw OEM command;
- destructive shell command.

Действие: в guided UI — один точный confirmation. Для наиболее разрушительных намерений (`erase userdata`, `format`, `flash:<partition>` при подтверждённом `LOCKED`) confirmation является **typed**: пользователь вводит `yes`. Typed confirmation — это affordance guided UI, а не проверка внутри protocol engine.

В raw console команда остаётся доступной и выполняется без prompt: сырая консоль передаёт команду устройству, и пользователь видит настоящий ответ peer.

## 3. USB invariants

- Один physical transport owner на claimed interface/generation.
- Detach/re-enumeration инвалидирует generation.
- Stale endpoint/handle не переиспользуется.
- Shutdown/cancel должен иметь доказуемые semantics.
- Native async I/O после cancel должен drain/reap или явно poison backend.
- Silent backend switch посреди mutating operation запрещён.
- Retry после ambiguous write запрещён, пока нельзя доказать, что previous attempt не мутировал peer.

## 4. ADB invariants

- Один physical IN reader допустим и предпочтителен; logical streams multiplex/demultiplex отдельно.
- ADB packet header/payload framing проверяется строго.
- Packet чужого stream не считается автоматически stale.
- Per-stream IDs и lifecycle сохраняются корректно.
- Backpressure ограничен и наблюдаем.
- `shell,v2` channels/exit status не смешиваются.
- Feature negotiation влияет на выбранный typed client, но отсутствие feature не превращается в общий ban: используется корректный fallback, если он существует.

### Доказанный A2 inbound framing invariant
Inbound USB framing policy из A2 принята после PASS hardware gate на `vayu`: payload, объявленный ADB header, должен завершиться в рамках одной receive operation; positive short read для такого frame считается incomplete и fail-closed, а не продолжается как обычный stream fragment.

Этот механизм переносится в новый ADB transport как **correctness invariant**, а не как A2 architecture или capability policy.

## 5. Fastboot invariants

- Transaction ownership один на конкретный Fastboot transport.
- Реальные peer statuses `INFO/TEXT/OKAY/FAIL/DATA` сохраняются без semantic подмены.
- DATA byte accounting точный.
- Для DATA OUT source не меняется во время передачи.
- Для DATA IN sink использует partial/commit semantics, когда возможно.
- Mid-DATA transport ambiguity после возможной mutation не ретраится автоматически.
- `max-download-size` и `partition-size` — diagnostics/advisories, а не host authorization, кроме случая, когда конкретный wire request физически невозможно корректно представить. Bootloader lock state — тоже diagnostics/advisory: он влияет на предупреждение и форму подтверждения, но не отменяет команду на стороне хоста.

### 5.1 Verified Bootloader Lock — advisory и typed confirmation

Ранее здесь был единственный product-level hard guard, блокировавший `flash:<partition>` при подтверждённом `LOCKED`. Он **отменён** решением D031.

Причина: guard не проходил собственный тест этого документа. Отказ при `LOCKED` принадлежит классу **B (Device authority)** — устройство само отвечает `FAIL`, — а правило класса B требует показать реальный ответ устройства и не подменять его собственной authorization системой. Guard делал именно подмену: упреждал ответ устройства решением хоста. Намерение пользователя записать образ при заблокированном bootloader — это класс **D (User intent)**.

Действующее правило:

- Lock state в текущей Fastboot `SessionGeneration` определяет **форму предупреждения**, а не разрешение на команду.
- При подтверждённом `LOCKED` guided UI показывает предупреждение и требует typed confirmation `yes`. После подтверждения команда отправляется устройству без изменений.
- `UNKNOWN`, unsupported query, противоречивый ответ и состояние из предыдущей generation **не равны** `LOCKED`: для них действует обычный advisory без typed confirmation. Иначе `UNKNOWN` фактически снова приравнивался бы к `LOCKED`.
- Raw Fastboot console выполняет `flash:<partition>` без prompt.
- Предупреждение обязано честно называть ожидаемый исход: устройство почти наверняка отклонит запись ответом `FAIL` и раздел не будет изменён; на части загрузчиков сначала передаётся весь DATA-объём и только затем приходит отказ.
- Реальный ответ устройства (`FAIL`, `OKAY`, `INFO`/`TEXT`) показывается без semantic подмены. Локальное предупреждение не заменяет и не имитирует ответ peer.
- Mi Unlock и другой явный unlock workflow при `LOCKED` доступны как обычно: их цель — изменить lock state.
- Успех unlock-команды не переводит локальное состояние в `UNLOCKED`. После reboot/re-enumeration старая generation инвалидируется; состояние определяется заново в новой Fastboot session.
- Typed confirmation принадлежит use-case/presentation слою. Protocol engine не содержит проверки lock state как условия выполнения команды: иначе engine снова становится host-side gate, что запрещено `02_CORE_ARCHITECTURE_RU.md`.
- Product-level hard guards в NekoFlash отсутствуют. Остановка возможна только по классам A–D этого документа.

## 6. Sideload invariants, доказанные A2

Сохранить как correctness contract:

1. `DONEDONE` / terminal transfer signal **не равен Recovery install success**.
2. После передачи outcome может оставаться `VERIFICATION_PENDING/UNKNOWN` до Recovery evidence.
3. Mutation boundary — первая попытка отправить payload block.
4. Обычный Cancel разрешён только до irreversible boundary.
5. После mutation transport close не превращается искусственно в `ABORTED`; результат может быть неизвестен.
6. Duplicate block requests не увеличивают unique progress.
7. Cumulative served traffic и unique coverage логируются раздельно.
8. Terminal/cancelled Sideload generation quarantined; требуется реальный detach/attach/new generation, когда это необходимо для корректной correlation.
9. Recovery evidence persistent across reconnect/process restart.
10. Correlation строится относительно pre-Sideload baseline и нового session evidence.
11. Local package SHA-256 доказывает выбранные local bytes, но не является автоматически Recovery-log correlation.

## 7. Cancellation model

`Cancel` не означает «отменить физически всё уже случившееся».

Outcome vocabulary:
- `Success` — завершение доказано;
- `Failed` — peer/device/protocol доказал failure;
- `Cancelled` — операция прекращена до boundary, где можно доказать отсутствие требуемой mutation;
- `Unknown` — mutation могла произойти, а final state доказать нельзя.

Unknown — профессионально честный результат, а не UX failure.

## 8. Что core никогда не должен делать

- `if (dangerous) reject` без protocol invariant;
- скрывать `FAIL` и возвращать generic local error без peer text;
- считать transport completion business success;
- возобновлять operation на новой generation без специально определённого recovery protocol;
- автоматически переповторять mutating request после неопределённого write;
- отдавать UI право «владеть» USB transaction lifetime.
