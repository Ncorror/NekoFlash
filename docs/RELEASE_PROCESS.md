# Релизный процесс NekoFlash

## 1. Source integrity

```bash
python3 scripts/update-checksums.py
```

Не включать `build/`, `.gradle/`, `.idea/`, APK/SO/O, caches и raw device logs в source archive.

## 2. Android build

```bash
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug assembleRelease
```

Ошибка загрузки Gradle/SDK/DNS не считается успешной сборкой; такой commit нужно собрать в окружении с готовым toolchain/cache.

## 3. Production behavior

Release не должен возвращать test/mock/self-test/qualification/dry-run слои или host-side mutation gates. Для hardware acceptance используются реальные устройства и реальные команды, а не симуляция.

## 4. Signing

Production release требует release keystore и проверки certificate continuity. Без `NEKOFLASH_KEYSTORE_PATH`, `NEKOFLASH_STORE_PASSWORD`, `NEKOFLASH_KEY_ALIAS`, `NEKOFLASH_KEY_PASSWORD` Gradle создаёт unsigned release artifact.

## 5. Publication

- обновить `versionCode`/`versionName` при релизном изменении;
- обновить changelog/current project state;
- пересоздать `SHA256SUMS`;
- получить green lint/assemble для exact source commit;
- проверить реальный ADB/Fastboot path на целевом hardware;
- приложить APK/source archive/checksum;
- не публиковать raw account/device identifiers.
