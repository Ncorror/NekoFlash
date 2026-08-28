# NekoFlash — Final UI/UX & Brand Vision

## 1. NekoFlash должен сохранить лицо

Новый кодовый фундамент **не означает новый безликий продукт**.

В `brand-reference/` сохранены реальные Legacy references:
- `welcome-background-reference.jpg` — дождливая неоновая улица, девушка с телефоном, кот и Android по USB;
- `welcome-legacy-reference.png` — реальный старый Welcome screen;
- `nekoflash-icon-reference.png` — кошачья cyber/USB identity.

Это визуальная основа бренда, которую можно качественно адаптировать, но не забывать.

## 2. Визуальный язык

Характер:
- dark graphite/near-black surfaces;
- холодный cyan/blue как technology/system accent;
- фирменный orange как action/highlight accent;
- умеренный neon/glow только там, где он подчёркивает identity/state;
- высокая контрастность технических данных;
- плотная, но не захламлённая информационная композиция;
- не «hacker cosplay», не generic corporate Material utility.

Material 3/Compose используется как platform toolkit, но визуальная система NekoFlash остаётся собственной.

### Phase 1 launcher identity

На этапе bootstrap launcher icon подключается из сохранённого `brand-reference/nekoflash-icon-reference.png` как provenance-preserving alpha asset. Это устраняет generic/default launcher identity уже в foundation, но не означает pixel-freeze: финальные adaptive/monochrome derivatives могут быть подготовлены позже в brand/UI slice при сохранении узнаваемой NekoFlash cat/USB/lightning identity.

## 3. Welcome / first run

Welcome **остаётся частью продукта**.

Цель Welcome:
- представить NekoFlash;
- сохранить атмосферу бренда;
- коротко объяснить Android USB Host модель;
- привести пользователя к Device Workspace.

Что убрать из старой философии:
- mandatory «I accept flashing risks» как разрешение пользоваться продуктом;
- battery optimization/file/notification checklist как продуктовый capability gate;
- повторные scary gates перед Expert tools.

Permissions запрашиваются контекстно, когда они реально нужны Android platform. Welcome не выдаёт пользователю «право быть профессионалом».

В NekoFlash нет профилей «Новичок / Эксперт», hidden advanced mode или отдельной разблокировки профессиональных функций. UI может использовать progressive disclosure для плотности информации, но не для урезания capability surface.

Пример содержания:

```text
NekoFlash
Professional Android Host Toolkit
ADB · Fastboot · Recovery · Mi Unlock

[ Continue ]
```

Фоновый арт может сохраняться как hero, но финальная композиция должна быть responsive и не зависеть от одного aspect ratio.

## 4. Информационная архитектура

### Phone portrait — основные destinations

Нижняя/адаптивная навигация:
- Home
- Device
- Tools
- Operations

`Tools` содержит:
- ADB
- Fastboot
- Recovery
- Terminal
- Mi Unlock
- Diagnostics

Mi Unlock при Xiaomi target дополнительно продвигается на Home/Device как first-class action. Он не прячется как «неважный vendor plugin».

### Large screen / landscape

Navigation rail/drawer может показывать напрямую:
- Overview/Home
- Device
- ADB
- Fastboot
- Recovery
- Terminal
- Mi Unlock
- Operations
- Diagnostics
- Settings

Центр — workspace, справа при достаточной ширине — live details/transcript/supporting pane.

## 5. Persistent Target Bar

На всех рабочих экранах видим:

```text
POCO F3 · vayu · Serial 8A... · Recovery · ADB/USB · Connected
```

При необходимости рядом:
- SessionGeneration;
- slot;
- bootloader/fastbootd badge;
- warning state.

Если physical session меняется, UI обязан явно показать новый state. Нельзя незаметно продолжать работу на другом target/generation.

## 6. Home

### Нет устройства

Hero с NekoFlash identity и спокойным prompt:

```text
Connect an Android device over USB
ADB · Recovery · Fastboot · Fastbootd

[ USB diagnostics ]   [ Help ]
```

### Устройство подключено

Главная карточка target + быстрые shortcuts по реальным capabilities:
- Open Terminal;
- Sideload;
- Fastboot workspace;
- Pull file;
- Reboot menu;
- Mi Unlock для релевантного Xiaomi target.

Home не дублирует весь Tools экран.

## 7. Device Workspace

Показывает объект, с которым работает оператор:
- model/product/device;
- serial(s);
- mode;
- ADB/Fastboot identity;
- slot/topology;
- bootloader state если доступно;
- battery если доступно;
- capabilities/features;
- USB/session details;
- current warnings/quirks;
- active/last operation.

Сырые endpoint/descriptor поля находятся в Diagnostics, не перегружают основное представление.

## 8. ADB Workspace

Typed tools:
- Interactive Shell;
- Run command;
- Push/Pull;
- Install APK/APKs;
- Reboot;
- Sync/file tools;
- Forward/Reverse;
- Raw service.

Ни одна из этих функций не является «разблокировкой Expert Mode». Они — нормальные профессиональные capabilities.

## 9. Fastboot Workspace

Секции:
- Target / mode / slot;
- Getvar Explorer;
- Flash;
- Boot;
- Erase / Format;
- Set Active;
- Logical Partitions / fastbootd;
- Fetch / DATA IN;
- OEM / Flashing;
- Raw Fastboot;
- Quick Flash / Plan.

Preflight показывает конкретные advisories. `max-download-size`, `partition-size` и неизвестный lock state не становятся скрытым authorization gate. Единственное исключение — подтверждённый `LOCKED`: обычный partition `flash` блокируется общим Verified Bootloader Lock Protection; остальные Fastboot capabilities этим автоматически не запрещаются.

## 10. Recovery Workspace

- Recovery identity;
- logs/evidence;
- Sideload;
- transfer progress;
- install verification state отдельно от transport state;
- reboot/mode transitions;
- advanced/raw recovery capabilities при наличии.

Пользователь видит разницу между `Transfer complete` и `Install verified`.

## 11. Terminal — одна из визитных карточек

Настоящий terminal emulator:
- ANSI/VT;
- PTY resize;
- Ctrl/Ctrl+C/Ctrl+D;
- arrows/tab/escape/function-like keys при необходимости;
- copy/paste;
- selection;
- stdout/stderr/exit status;
- font sizing;
- scrollback;
- history;
- multiple tabs/sessions в финальном UX.

Контексты:
- ADB Shell;
- Raw ADB Service;
- Fastboot Console.

Возможно представление:

```text
shell 1 | logcat | fastboot | +
```

При disconnect tab не исчезает: помечается disconnected, transcript сохраняется.

## 12. Operations

Всегда доступна компактная active-operation bar:

```text
FLASH system_a   78%   2.14 / 2.73 GiB
```

Развёрнутая карточка:
- target/serial/session;
- operation intent;
- artifact + size/hash;
- stage;
- exact byte progress/rate;
- mutation boundary status;
- transcript/evidence;
- outcome.

Operations — не permission gate, а ownership/history/evidence center.

## 13. Mi Unlock

Mi Unlock имеет собственный polished workflow:
- Xiaomi target detection;
- device/bootloader state;
- account/device identifiers, если protocol требует;
- server/network phase;
- Fastboot/device phase;
- wait/retry semantics только если они протокольно обоснованы;
- настоящий error response;
- transcript/evidence;
- reconnect/reboot state.

`VERIFIED_LOCKED` не скрывает и не запрещает Mi Unlock: unlock workflow является разрешённым путём изменения lock state. После reboot/re-enumeration UI не считает unlock завершённым по старому флагу — новая Fastboot session должна заново подтвердить `UNLOCKED`, прежде чем обычный Flash станет доступен.

Никакой vendor магии внутри generic USB/Fastboot core.

## 14. Diagnostics

Progressive disclosure: обычный экран не завален endpoint числами, но инженер может открыть:
- USB descriptors/interfaces/endpoints;
- selected backend;
- SessionGeneration;
- ADB banner/features/streams/counters;
- Fastboot transcript/DATA accounting;
- Recovery/Sideload correlation evidence;
- operation event timeline;
- export diagnostics bundle.

## 15. Command Palette

Финальная версия должна иметь глобальный быстрый поиск действий:

```text
Search commands...
flash boot
reboot recovery
pull
mi unlock
getvar
```

Palette показывает действие с target/mode requirements. Оно помогает масштабировать огромную capability surface без сотен кнопок на одном экране.

## 16. Destructive guided UX

Один точный confirmation вместо цепочки scare-screens:

```text
Target: POCO F3 / serial ...
Mode: fastbootd
Partition: system_a
Artifact: system.img
Size: ...
SHA-256: ...
Advisories: ...

[ Cancel ] [ Execute ]
```

Raw Terminal не получает denylist только потому, что команда destructive.

## 17. Adaptive UI

UI с самого начала должен жить на:
- обычном телефоне portrait;
- landscape;
- tablet/foldable/large window.

Одна information architecture, разные pane/navigation presentations. Никакого отдельного «tablet app».

## 18. Localization / bilingual UI

NekoFlash UI поддерживается на **English и Русском** с одинаковой capability surface. English — default resource locale, Русский — first-class `values-ru` translation.

Обязательные правила:
- никаких user-facing hardcoded strings в Compose/Kotlin;
- навигация, confirmations, operation states, warnings, accessibility labels и Settings локализуются;
- ADB/Fastboot command text, partition names, serials, paths, hashes, protocol tokens и raw device/server responses отображаются точно и не переводятся;
- structured diagnostic event code остаётся locale-neutral, а человекочитаемый label может локализоваться;
- длина русского текста учитывается в adaptive layout: перевод не должен обрезать критичную информацию или менять доступность action.

Android 13+ использует системные per-app language preferences через generated locale config. Когда появится собственный Language control в Settings, он должен синхронизироваться с platform/AndroidX application locales.

## 19. UX principle

**Typed GUI и raw professional access — два представления одного и того же ядра, а не два уровня разрешений.**
