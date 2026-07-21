# Релизный процесс NekoFlash V6

## 1. Source integrity

```bash
python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
```

Рабочее дерево не должно содержать build-каталоги, cache, raw hardware logs или временные файлы.

## 2. Pure/JVM gates

```bash
python3 scripts/check-ab-safety.py
python3 scripts/check-usb-connectivity.py
python3 scripts/check-flash-safety.py
python3 scripts/check-diagnostic-logging.py
bash scripts/run-tests.sh
```

Все модули manifest должны завершиться PASS.

## 3. Android gates

- `lintDebug`;
- `assembleDebug`;
- `assembleRelease`;
- smoke instrumentation для верхней панели, Home info и четырёх переходов;
- отсутствие compile warnings/errors, связанных с удалёнными V6 components.

Недоступный Gradle/SDK — `CI_REQUIRED`, не PASS.

## 4. Hardware gates

Перед beta: Terminal и Sideload. Перед release: контролируемый Quick Flash и Mi Unlock audit. Sanitised evidence хранится как release artifact или внешний отчёт, а в Git остаётся краткое summary.

## 5. Signing

Release требует production keystore, проверки certificate fingerprint, R8/lintVital и установки подписанного APK поверх предыдущего релиза, когда это предусмотрено signing continuity.

## 6. Публикация

- обновить versionName/versionCode;
- обновить tracker/changelog;
- пересоздать `SHA256SUMS`;
- получить CI PASS;
- приложить APK, source ZIP и checksum;
- не публиковать raw device logs.
