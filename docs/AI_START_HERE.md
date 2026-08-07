# AI / maintainer handoff

Перед изменением кода прочитать:

1. `docs/PROJECT_STATE.md`
2. `docs/ARCHITECTURE.md`
3. `docs/USB_PROTOCOL.md`
4. `docs/CODE_GUIDE.md`
5. `BUILDING.md`

## Главный инвариант

NekoFlash — production-only USB tool. Не возвращать dry-run, simulation, fake transports, self-tests, qualification matrices, test-only DI/abstractions или host-side mutation authorization. Реальные ADB/Fastboot команды должны доходить до protocol transport; допустимость операции определяет физический peer и сам протокол.

## Что можно блокировать

Только ошибки, без устранения которых операция технически некорректна: отсутствующий USB endpoint/session, concurrent owner одного transport, unreadable/empty local payload, некорректный Fastboot DATA length/wire syntax, cancellation и неподтверждённый native URB drain. Последний случай нельзя «разблокировать» без риска использовать память, которой ещё может владеть kernel.

## Порядок ревью

1. C++/JNI/USBFS ownership и hot path.
2. Python/release utilities.
3. Kotlin transport и operation lifecycle.
4. UI — только как инициатор реального transport action.
5. `python3 scripts/update-checksums.py` и Gradle lint/assemble.

Исторические alpha/recovery/safety документы не являются текущей спецификацией.
