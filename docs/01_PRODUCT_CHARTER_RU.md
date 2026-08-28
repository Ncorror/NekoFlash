# NekoFlash — Product Charter

## 1. Что мы строим

**NekoFlash — полноценный профессиональный Android host toolkit на Android-устройстве, работающий напрямую с другим Android-устройством по USB Host.**

Продукт объединяет:
- ADB;
- Fastboot bootloader mode;
- fastbootd/userspace Fastboot;
- Recovery и ADB Sideload;
- полноценный Terminal/Console;
- операции с образами/файлами;
- диагностику и evidence;
- Mi Unlock и будущие vendor workflows.

Это не «мастер безопасной прошивки» и не оболочка вокруг нескольких разрешённых кнопок.

## 2. Для кого

Основная аудитория — технически грамотные пользователи, сервисные инженеры, разработчики, энтузиасты и специалисты, которым нужен прямой профессиональный доступ к протоколам.

Typed GUI должен помогать выполнять типовые задачи быстро и понятно, но **не уменьшать underlying capability surface**.

## 3. Главный принцип: NO ARTIFICIAL CAPABILITY RESTRICTIONS

NekoFlash не решает за пользователя, что ему «не нужен» generic ADB shell, raw service, Fastboot OEM command, erase, raw transaction или другой валидный protocol operation.

В продукте **нет профилей «Новичок / Эксперт»**, unlock-режима «Expert Mode», скрытых capability tiers или onboarding-gate, который выдаёт пользователю право на профессиональные функции. Typed GUI и raw tools различаются представлением и удобством, а не уровнем разрешений.

Вместо этого NekoFlash обязан:
- правильно показать target и mode;
- дать содержательный preflight;
- предупредить о значимых рисках;
- получить осознанное подтверждение для destructive guided actions;
- выполнить валидную операцию через единое корректное ядро;
- сохранить реальный peer/device response;
- не заявлять успех, если доказательств недостаточно.

## 4. Системная защита и ограничения устройства остаются реальностью

Полная capability surface не означает фиктивный обход Android security model.

NekoFlash должен честно соблюдать/отображать:
- Android USB permission;
- ADB RSA authorization;
- locked bootloader и его ответ;
- AVB/Verified Boot/OEM/device restrictions;
- отсутствие конкретного service/command на peer;
- различия bootloader Fastboot и fastbootd;
- реальные server/vendor restrictions в Mi Unlock;
- физический disconnect/re-enumeration.

По умолчанию приложение не должно превращать device state или ожидаемый отказ peer в собственную authorization систему. Единственное явно зафиксированное исключение — **Verified Bootloader Lock Protection**: если в текущей Fastboot `SessionGeneration` устройство однозначно подтверждает состояние `LOCKED`, NekoFlash не выполняет обычный `flash:<partition>` образа. Это узкая защита от записи образа при подтверждённо заблокированном bootloader, а не общий режим ограничений.

`UNKNOWN`, unsupported query, противоречивые данные или старое состояние до reconnect **не считаются `LOCKED`**. Mi Unlock/unlock workflow при `LOCKED` остаётся доступным. Это исключение нельзя автоматически расширять на `erase`, `format`, `boot`, `set_active`, OEM/raw commands или другие операции: там действуют обычные protocol/device responses, если отдельный correctness invariant не требует остановки.

## 5. Что значит «профессиональное»

Профессиональность = не максимальное количество опасных кнопок, а сочетание:

- максимальной доступной capability surface;
- точного target/session ownership;
- сильных typed workflows;
- полноценного raw access;
- прозрачного protocol output;
- корректных state machines;
- честных `Success / Failed / Cancelled / Unknown`;
- детальной диагностики;
- воспроизводимого evidence;
- хорошей работы с огромными artifacts;
- отказоустойчивости при USB detach/process death;
- чистой архитектуры без второго скрытого engine.

## 6. Языки продукта

NekoFlash поддерживает **два first-class языка интерфейса: English и Русский**.

Правила:
- default/unqualified Android resources — English;
- `values-ru` — полный русский UI без урезания capability surface;
- новый пользовательский UI не принимается с hardcoded display strings в Kotlin, если строка не является точным protocol/device datum;
- названия protocol commands, partition names, raw peer responses, wire tokens и stable diagnostic event codes не переводятся и не переписываются ядром;
- локализация меняет представление, но не operation semantics, protocol behavior или evidence.

На Android 13+ приложение должно объявляться системе как bilingual app для per-app language preferences. На более старых Android корректная системная locale fallback остаётся обязательной; собственный language picker, когда появится Settings UI, должен использовать platform/AndroidX locale APIs, а не кастомную подмену `Locale`.

## 7. First-class product areas

### Device Workspace
Физический target, identity, mode, capabilities, slots, connection/session и быстрые действия.

### ADB
Generic services, shell/shell_v2, interactive terminal, exec, sync, push/pull, install, reboot, forwarding и raw services.

### Fastboot
Raw transaction, getvar, flash, boot, erase, format, set_active, logical partitions, DATA OUT, DATA IN/fetch/upload и OEM/flashing commands.

### Recovery
Detection, logs/evidence, mode transitions, Sideload transport и отдельный install verdict.

### Terminal
Настоящий terminal emulator и raw consoles, не «опасный режим» с урезанными командами.

### Operations
Долгие mutating и data-transfer операции с persisted state/evidence.

### Diagnostics
USB/protocol/session/transcript/evidence без необходимости debug build.

### Mi Unlock
Самостоятельный важный workflow с хорошим UX, но реализованный поверх общих core APIs и отдельного vendor/network слоя.

## 8. Не-цели

- не изображать bypass реальных security controls устройства;
- не строить exploit framework как основу продукта;
- не добавлять «магические» auto-retry после ambiguous mutation;
- не скрывать protocol failures ради красивого UX;
- не делать две разные системы прав: «GUI» и «Expert».

## 9. Критерий любого будущего ограничения

Любой новый hard-block должен отвечать на вопрос:

> Какой конкретный protocol/transport/device invariant нарушится, если мы позволим отправить эту валидную команду?

Единственное product-level исключение, уже отдельно принятое в canonical decisions, — Verified Bootloader Lock Protection для обычного `flash:<partition>` при подтверждённом `LOCKED`. Расширять это исключение «по аналогии» нельзя: новое ограничение требует отдельного явного founding decision.

Если ответ сводится к «это опасно», «обычному пользователю не нужно» или «так спокойнее» — это не основание для core restriction.
