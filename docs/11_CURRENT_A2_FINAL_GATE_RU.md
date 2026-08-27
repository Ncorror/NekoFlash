# NekoFlash-A2 — Historical Final Gate Result

Статус: **COMPLETE / PASS**
Роль документа: historical evidence record. Это больше не current work.

## Build

`a2479b333ee2f25b0bc86a530d948c48a3423a68`

## Target

Stable reference device: `vayu`.

`onyx` / POCO F7 / Redmi Turbo 4 Pro не использовался как единственный reference gate.

## Проверявшийся invariant

ADB inbound USB framing для payload больше старой проблемной границы: payload, объявленный ADB header, читается одной receive operation; positive short read считается incomplete frame и приводит к fail-closed вместо попытки продолжить frame вторым bulk read.

A2 unit model отдельно фиксирует проблемный случай `53 283` bytes против short read `32 768` bytes.

## Result

**PASS. Fix сработал на hardware gate `vayu`.**

Это закрывает Phase 0 и позволяет принять framing policy как переносимый correctness invariant нового NekoFlash.

## Evidence note

В предоставленном frozen A2 archive отсутствует сохранённый финальный diagnostics ZIP этого gate. Поэтому различаем:

- hardware result/status: **PASS**, подтверждён владельцем проекта;
- implementation + unit model: присутствуют в A2 и соответствуют исправлению;
- final diagnostics bundle: отсутствует в frozen snapshot.

Отсутствие bundle не возвращает A2 в active development. Если historical artifact позже будет найден, его следует добавить как новый immutable evidence/reference artifact, не изменяя старый snapshot.

## Freeze

- A2 development CLOSED;
- Legacy/A2 — reference/evidence only;
- новые features реализуются только в clean NekoFlash repository;
- старые A2 stage/capability policies не являются нормой нового проекта.
