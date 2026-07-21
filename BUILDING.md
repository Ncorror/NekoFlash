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

Ожидаемый pure/JVM результат текущей baseline: `ALL TESTS PASSED (23 module(s))`.

## Android

```bash
./gradlew --no-daemon lintDebug assembleDebug assembleRelease
```

Локальная ошибка загрузки Gradle, DNS или SDK означает `CI_REQUIRED`, а не успешную Android-сборку.

## Termux

Подготовка чистой установки:

```bash
bash scripts/termux-bootstrap.sh
```

Быстрый commit и push без локальной сборки:

```bash
bash scripts/termux-publish.sh "Описание изменения"
```

Импорт нового source ZIP и push одной командой:

```bash
bash scripts/termux-publish.sh --source-zip SOURCE.zip --sha256 SHA256 "Описание изменения"
```

Запуск CI с автоматическим сбором лёгкого evidence archive без APK выполняется отдельно:

```bash
bash scripts/termux-ci.sh
```

Сбор уже существующего run:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID
```

APK скачиваются только отдельно и только когда нужны для установки или hardware retest:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID --with-apk
```

Контекст для нового чата:

```bash
bash scripts/export-chat-context.sh
```

Подробности: [`docs/TERMUX_WORKFLOW.md`](docs/TERMUX_WORKFLOW.md).

## Артефакты

- Debug APK используется только для разработки и аппаратных проверок.
- Release APK считается релизным только после проверки signing identity, R8/lintVital и hardware gates.
- Termux сохраняет CI evidence без APK в `Download/NekoFlash-CI-<RUN_ID>/` и ZIP рядом.
- При `--with-apk` APK сохраняются отдельно в `Download/NekoFlash-APK-<RUN_ID>/` и отдельном ZIP.
- Generated logs не добавляются в source integrity inventory.
- Raw hardware logs с идентификаторами устройства не коммитятся; в репозитории хранится только sanitised summary.
