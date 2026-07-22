# NekoFlash — единый трекер проекта

Последнее обновление: **2026-07-22**  
Текущий milestone: **V6.0.0 — alpha5 Recovery-first Quick Flash**  
Версия: **`6.0.0-alpha5-dev-nekoflash`**  
Version code: **`217`**

## Новый чат

Начать с [`docs/AI_START_HERE.md`](docs/AI_START_HERE.md), затем читать этот файл как единственный источник живого статуса. Исторические детали находятся в changelog и feature-планах; их нельзя переносить обратно в текущий статус.

## Текущее состояние source tree

- Recovery-first Quick Flash slices A–D реализованы; baseline Slice E подтверждён Android CI.
- Welcome/Sideload/Fastboot DATA hardware-polish внесён. Welcome hero теперь заполняет свободную высоту экрана, а нижний gate использует прозрачный контур; требуется повторный visual smoke test.
- Mi Account `/sts` exchange исправлен. Дополнительно устранена гонка первого входа: поздний WebView callback не должен заменять успешный результат сообщением о недоверенном адресе.
- Mi Login first-pass fix подтверждён на устройстве build `5f119c469430.29913150722`: fresh login завершился в одном проходе без stale blocked-host banner. Для текущего exact head всё ещё нужен новый Android CI.

## Подтверждённые доказательства

### Последний подтверждённый Android CI

- GitHub Actions run: `29855091700` (`Build Android APK #14`).
- Event/head branch: `pull_request` / `feature/recovery-first-quick-flash`.
- PR head SHA: `8a6dab5f81dd0ff117b3b6e27e6d528a45900e24`.
- Checkout/build SHA: `3057477010f2ac8f1e39c1390a3c74732baab20c` — synthetic PR merge commit.
- Результат: static/safety, pure/policy matrix, `lintDebug`, `assembleDebug`, `assembleRelease` — `success`.
- Этот run относится к baseline до последующих hardware-polish/Mi Login исправлений.

### Последнее Android smoke evidence

- Build `8d9923ec0878.29870485300` подтвердил успешный Xiaomi login и получение unlockApi service session, но выявил stale blocked-host banner первого прохода.
- Build `5f119c469430.29913150722` подтвердил исправление: fresh login завершился в том же запуске без перезапуска и без blocked-host error.
- Следующий Mi Unlock action корректно остановлен из-за отсутствия Fastboot-устройства.
- Текущий source также убирает raw account ID из compact log; токены, cookie values, serial и raw USB topology в репозитории не сохраняются.

Подробный sanitised summary: [`docs/HARDWARE_VALIDATION.md`](docs/HARDWARE_VALIDATION.md).

## Статус задач

| ID | Задача | Статус | Следующее доказательство |
|---|---|---|---|
| SCOPE-001 | Не возвращать полный Mi Flash в активную ветку | DONE_CODE | `check_project.py` и архив V5.9.17 |
| AUDIT-001 | Очистить V6 source/doc tree | DONE_CI | Alpha4 compile hotfix и documentation guards |
| CONTEXT-001 | Самодостаточный handoff | DONE_CODE | `docs/AI_START_HERE.md` + export script |
| TERMUX-001 | Разделить push, CI evidence и APK | DONE_CI | Push-only publisher; APK только по `--with-apk` |
| TOPBAR-001 | Сохранить функциональное поведение верхней панели | DONE_CODE | Android UI smoke test с устройством |
| HOMEINFO-001 | Сохранить карточку устройства/рабочей папки | DONE_CODE | Android UI smoke test с устройством |
| HOMEACTIONS-001 | Сохранить четыре главных перехода Home | DONE_CODE | Android UI smoke test |
| FLASH-001 | Recovery-first Quick Flash | DONE_CI | Контролируемый hardware retest |
| POLISH-WELCOME-001 | Облегчить welcome permissions/risk gate | FIXED_CODE | Повторный visual smoke test на целевых размерах экрана |
| POLISH-SIDELOAD-001 | Упростить Sideload card и нейтрализовать pre-verify status | FIXED_CODE | Android smoke + transfer/cancel/recovery result |
| POLISH-DATA-001 | Свернуть Fastboot DATA diagnostics | DONE_CODE | Fastboot hardware retest |
| UNLOCK-LOGIN-001 | Исправить Mi Account `/sts` и first-pass callback race | DONE_DEVICE | Новый Android CI для exact head; сохранить no-banner поведение |
| TERMINAL-001 | Проверить Terminal на устройстве | OPEN | Read-only ADB/Fastboot и sanitised log |
| SIDELOAD-001 | Подтвердить ADB Sideload V6 | RETEST_REQUIRED | ZIP transfer, cancel, recovery result |
| UNLOCK-001 | Отдельный аудит Mi Unlock | IN_PROGRESS | Login retest, затем разделение standard/Xiaomi flows |
| TEST-001 | Релевантная test matrix | DONE_CI | Текущий source: local 23/23; новый Android CI pending |
| RELEASE-001 | Signing и hardware release gate | OPEN | После стабилизации alpha/beta |

## Защищённые границы

- `cardQuickFlashRecoveryFirst` принят как эталонный UI и не меняется в hardware-polish без отдельного решения.
- `TOPBAR-001`, `HOMEINFO-001`, `HOMEACTIONS-001` сохраняются функционально.
- Полный Mi Flash не возвращается; архивная база — `archive/full-miflash-v5.9.17`.
- Не допускаются force push, скрытый выбор partition, автоматический mutation retry или утверждение PASS без evidence.

Основные safety-инварианты: [`docs/SAFETY_MODEL.md`](docs/SAFETY_MODEL.md). Архитектура Quick Flash: [`docs/RECOVERY_FIRST_PLAN.md`](docs/RECOVERY_FIRST_PLAN.md).

## Текущий следующий шаг

1. Опубликовать текущий audited source ZIP в `feature/recovery-first-quick-flash` через push-only Termux workflow.
2. Запустить новый Android CI отдельно через `scripts/termux-ci.sh`; подтвердить static/pure/lint/debug/release для точного head SHA.
3. Проверить welcome visual smoke: panel должна оставаться у нижней границы, hero — заполнять свободную высоту, outer gate — быть контурным/прозрачным.
4. Проверить, что Sideload до фактического verify не показывает зелёную success-индикацию.
5. Повторить Mi Account login как regression smoke и убедиться, что compact log не содержит raw account ID.
6. Затем провести sanitised Terminal/Sideload/Quick Flash hardware validation.

Перед передачей следующему чату выполнить:

```bash
python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
bash scripts/run-tests.sh
bash scripts/export-chat-context.sh
```

Реальная прошивка требует Fastboot-устройства, существующего concrete partition, корректного slot, проверенного файла и явного confirmation. Автоматического повторения mutation-команд нет.
