# NekoFlash — Mandatory Capability Matrix

Статус в этой таблице — **целевой контракт нового продукта**, а не обещание первого milestone.

| Область | Обязательная capability | Источник опыта | Новый контракт |
|---|---|---|---|
| USB | descriptor-based discovery/classification | A2 | Да |
| USB | permission, claim/release, generation ownership | A2 | Да |
| USB | Java USB fallback + native high-performance backend | A2 | Да, после пересмотра API |
| ADB | CNXN/AUTH/RSA | Legacy + A2 | Да |
| ADB | generic `openService(destination)` | Legacy/AOSP | **Обязательно** |
| ADB | concurrent multi-stream router | новый фундамент | **Обязательно** |
| ADB | `shell,v2` stdout/stderr/exit | Legacy/AOSP | **Обязательно** |
| ADB | legacy shell fallback | Legacy | Да |
| ADB | interactive PTY shell | Legacy | **Обязательно** |
| ADB | stdin / EOF / Ctrl+C / resize | Legacy + new UI | **Обязательно** |
| ADB | `exec`/raw services | Legacy/AOSP | Да |
| ADB | Sync STAT/LIST/SEND/RECV | Legacy | **Обязательно** |
| ADB | push/pull | Legacy | **Обязательно** |
| ADB | install / install-multiple strategy | Legacy/AOSP | **Обязательно** |
| ADB | reboot targets | Legacy | Да |
| ADB | forward/reverse / advanced services | AOSP | Планируется как normal professional capability |
| Recovery | detect/identify Recovery | A2 | Да |
| Recovery | bounded logs/evidence | A2 | Да, поверх generic ADB |
| Recovery | ADB Sideload request-driven transfer | A2 | **Обязательно** |
| Recovery | baseline/correlation/verdict | A2 hardware work | **Обязательно** |
| Fastboot | raw command | Legacy/AOSP | **Обязательно** |
| Fastboot | INFO/TEXT/OKAY/FAIL transcript | Legacy + A2 | **Обязательно** |
| Fastboot | getvar / getvar:all | Legacy + A2 | Да |
| Fastboot | DATA OUT / download | Legacy + A2 | **Обязательно** |
| Fastboot | flash | Legacy + A2 | **Обязательно** |
| Fastboot | boot | Legacy | **Обязательно** |
| Fastboot | erase | Legacy | **Обязательно** |
| Fastboot | format | Legacy | **Обязательно** |
| Fastboot | set_active / slot operations | Legacy | **Обязательно** |
| Fastboot | reboot variants | Legacy | Да |
| Fastboot | OEM / flashing commands | Legacy | **Обязательно** |
| Fastboot | logical partitions / fastbootd operations | AOSP/new | **Обязательно для полноценного modern Fastboot** |
| Fastboot | DATA IN / fetch/upload class | AOSP | **Обязательно** |
| Terminal | true terminal emulator | Legacy idea, new implementation | **Обязательно** |
| Terminal | multiple sessions/tabs/history | new UI | Целевой финал |
| Terminal | raw ADB service console | new core | Да |
| Terminal | raw Fastboot console | Legacy/new core | Да |
| Operations | persisted operation history/evidence | A2 idea, new model | **Обязательно** |
| Diagnostics | USB/protocol/session structured diagnostics | A2 | **Обязательно** |
| Diagnostics | export deterministic bundle | A2 | **Обязательно** |
| Mi Unlock | dedicated first-class workflow | Legacy | **Обязательно** |
| Mi Unlock | uses generic core, not second USB stack | new architecture | **Hard architecture rule** |
| UI | Welcome + NekoFlash brand identity | Legacy | **Сохраняем и развиваем** |
| UI | adaptive phone/landscape/tablet layouts | new | **Обязательно** |
| UI | persistent target/session identity | new | **Обязательно** |
| UI | command palette/search | new | Целевой финал |

## Capability completeness rule

Фича не считается «восстановленной», пока:
1. есть один production API;
2. transport/error model не скрывает peer response;
3. определены ownership/cancel semantics;
4. есть tests;
5. hardware-sensitive часть проверена на реальном устройстве;
6. UI и raw access используют один core;
7. в новом repository нет второго старого implementation path.
