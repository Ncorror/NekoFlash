# Сборка NekoFlash

Текущий toolchain: `compileSdk 36`, AGP `8.13.2`, Gradle `8.13`, Kotlin `2.3.21`, JDK 17, `minSdk 26`, `targetSdk 34`.

## Требования

- JDK 17;
- Android SDK с платформой/build-tools проекта;
- доступ к Gradle distribution либо уже заполненный Gradle cache;
- Python 3 для checksum inventory.

## Production build

```bash
python3 scripts/update-checksums.py
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug assembleRelease
```

В production source отсутствуют отдельные test/mock/qualification modules. Build pipeline не запускает unit/instrumentation test matrix.

Если wrapper не может скачать Gradle из-за DNS/сети или Android SDK недоступен, это означает только отсутствие build verdict в текущем окружении.

## Termux

```bash
bash scripts/termux-bootstrap.sh
bash scripts/termux-publish.sh "Описание изменения"
bash scripts/termux-ci.sh
bash scripts/export-chat-context.sh
```

Подробности: [`docs/TERMUX_WORKFLOW.md`](docs/TERMUX_WORKFLOW.md).

## Артефакты

В source archive не включаются `build/`, `.gradle/`, `.idea/`, `*.apk`, `*.so`, `*.o`, caches и raw hardware/account logs. Перед публикацией source checksum пересоздаётся `scripts/update-checksums.py`.
