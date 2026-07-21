# Сборка NekoFlash

## Требования

- JDK 17;
- Android SDK с платформой и build-tools проекта;
- доступ к Gradle distribution или заранее заполненный Gradle cache;
- Python 3 и Kotlin compiler для локальной pure/JVM matrix.

## Быстрые проверки

```bash
python3 scripts/update-checksums.py
python3 scripts/check-documentation.py
python3 scripts/check_project.py
python3 scripts/test-checksum-inventory.py
python3 scripts/check-ab-safety.py
python3 scripts/check-usb-connectivity.py
python3 scripts/check-flash-safety.py
python3 scripts/check-diagnostic-logging.py
bash scripts/run-tests.sh
```

Ожидаемый pure/JVM результат alpha3: `ALL TESTS PASSED (19 module(s))`.

## Android

```bash
./gradlew --no-daemon lintDebug assembleDebug assembleRelease
```

Локальная ошибка загрузки Gradle, DNS или SDK означает `CI_REQUIRED`, а не успешную Android-сборку.

## Артефакты

- Debug APK используется только для разработки и аппаратных проверок.
- Release APK считается релизным только после проверки signing identity, R8/lintVital и hardware gates.
- Generated logs не добавляются в source integrity inventory.
- Raw hardware logs с идентификаторами устройства не коммитятся; в репозитории хранится только sanitised summary.
