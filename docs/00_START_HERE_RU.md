# NekoFlash — START HERE

Дата: **2026-08-27**
Статус: **SOURCE OF TRUTH для нового clean repository**.

## 1. Что произошло

Legacy NekoFlash имеет широкий профессиональный функционал, но старая архитектура и накопленные компромиссы не должны быть фундаментом нового проекта. NekoFlash-A2 дал важные transport/USB/Sideload решения и hardware evidence, но в процессе разработки в него проникли искусственные capability restrictions, stage-era архитектура и противоречивая документация.

Решение: **новая чистая кодовая база NekoFlash**. Не переписывание «по памяти» и не продолжение A2, а новая архитектура, использующая Legacy и A2 как источники проверенного опыта.

## 2. Иерархия решений

При конфликте информации приоритет такой:

1. `01_PRODUCT_CHARTER_RU.md` и `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`.
2. Актуальные ADR/Founding Decisions нового repository.
3. Доказанные protocol/transport invariants и hardware evidence.
4. Актуальный roadmap нового repository.
5. Legacy/A2 source, tests, logs и исторические документы — reference/evidence.

Старый checkpoint, временный prompt, stage-policy или комментарий в Legacy/A2 **не может** незаметно изменить продуктовую политику нового NekoFlash.

## 3. Неизменяемая продуктовая установка

**NekoFlash — профессиональный Android host toolkit. Он реализует максимально полный доступ к возможностям, которые реально предоставляют ADB, Fastboot/fastbootd, Recovery и поддерживаемые vendor protocols. NekoFlash не создаёт собственную систему запретов поверх этих возможностей только потому, что действие рискованное или продвинутое.**

Hard-block допустим только если:
- операция технически непредставима/некорректна;
- нарушен доказанный protocol/transport invariant;
- потерян ownership нужной physical session;
- peer/device сам отверг операцию;
- продолжение создало бы ложь о результате или недоказанную mutation.

Риск сам по себе = **warning/advisory**, а не удаление capability.

## 4. Что обязательно сохранить как смысл продукта

- имя **NekoFlash**;
- реальную Legacy Welcome identity и иконку как основу бренда;
- профессиональный характер продукта;
- Mi Unlock как важный first-class workflow, а не забытый поздний plugin;
- полный Expert Terminal и raw access;
- GUI как typed representation тех же core capabilities, а не отдельный урезанный уровень прав;
- сильные diagnostics/evidence;
- аппаратно доказанные correctness protections A2.

## 5. Что запрещено переносить как «наследство»

- broad allowlist/denylist ADB/Fastboot команд;
- host-side `unlocked/max-download-size/partition-size` как собственную authorization систему;
- entry gate, который делает профессиональные возможности «разрешаемыми» приложением;
- global god-class USB+ADB+Fastboot+UI;
- один логический ADB stream;
- global operation stage, одинаковый для всех операций;
- stale docs/recovery prompt как активную норму;
- `.orig`, `.bak`, `Old/New/Temp`, параллельные production paths, production stubs.

## 6. Правило миграции

Ничего не копировать целиком «потому что работает». Для каждого переносимого механизма:

1. определить capability/invariant;
2. описать новый API;
3. понять, какой код можно переиспользовать и какой надо переписать;
4. перенести тесты/evidence;
5. провести fault tests;
6. при hardware-sensitive изменении получить hardware proof;
7. оставить в новом repository только один production path.

## 7. Текущая точка

**Phase 0 завершена: PASS.** Read-only hardware gate на `vayu` для ADB inbound framing fix из A2 завершён успешно. Fix принят как доказанный transport/correctness invariant для нового core.

Legacy и A2 считаются **FROZEN / REFERENCE ONLY**. Новые product features в них не строятся.

В frozen A2 archive нет сохранённого финального diagnostics bundle этого gate; статус PASS зафиксирован по подтверждению владельца проекта и согласуется с кодом/тестовой моделью A2.

**Phase 1 завершена: COMPLETE / PASS.** Authoritative GitHub Actions подтвердил clean bootstrap, tracked Gradle Wrapper, AGP `9.3.2`, launcher/adaptive/monochrome icon, explicit no-backup/D2D policy, executable repository/localization hygiene, 7 core unit tests, Android Lint `0 errors` и debug APK assembly.

**Phase 2 завершена: COMPLETE / PASS.** USB + Target/Session vertical slice построен и подтверждён на реальном устройстве: descriptor discovery, permission lifecycle с выдачей и отказом, `TargetId` с уточнением серийным номером, `SessionGeneration`, application-scoped ownership, detach/re-enumeration, захват и освобождение интерфейса, выгрузка evidence приложением. Полная таблица проверок и критерий PASS — `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` §6.10.

Текущая работа: **Phase 3 — настоящий ADB foundation + Terminal**. Первым переносится доказанный на `vayu` inbound framing invariant.

Статус каждого пункта текущей фазы смотреть в чеклисте `09_IMPLEMENTATION_ROADMAP_RU.md`: он обновляется тем же changeset, что и код, и является единственным источником истины о готовности.
