# Релизный процесс NekoFlash

## 1. Source integrity

```bash
python3 scripts/update-checksums.py
```

Не включать `build/`, `.gradle/`, `.idea/`, APK/SO/O, caches и raw device logs в source archive.

## 2. Android build

```bash
./gradlew --no-daemon --warning-mode all lintDebug assembleDebug
```

Ошибка загрузки Gradle/SDK/DNS не считается успешной сборкой; такой commit нужно собрать в окружении с готовым toolchain/cache. Signed release проверяется отдельным `assembleRelease` только при наличии постоянного release key.

## 3. Production behavior

Release не должен возвращать test/mock/self-test/qualification/dry-run слои или host-side mutation gates. Для hardware acceptance используются реальные устройства и реальные команды, а не симуляция.

## 4. Signing

Production release требует постоянный NekoFlash release keystore и проверки certificate continuity. `scripts/build-apk.sh release/all` сверяет SHA-256 сертификата keystore с закреплённым production fingerprint и после сборки проверяет APK через `apksigner`. Новые локальные инструкции используют `NEKOFLASH_RELEASE_STORE_FILE`, `NEKOFLASH_RELEASE_STORE_PASSWORD`, `NEKOFLASH_RELEASE_KEY_ALIAS`, `NEKOFLASH_RELEASE_KEY_PASSWORD`; legacy `NEKOFLASH_KEYSTORE_PATH`/`NEKOFLASH_STORE_PASSWORD`/`NEKOFLASH_KEY_ALIAS`/`NEKOFLASH_KEY_PASSWORD` остаются только fallback. Без полного signing-набора, при другом keystore или несовпадении сертификата release завершается ошибкой; unsigned production APK не выпускается.

## 5. Publication

- обновить `versionCode`/`versionName` при релизном изменении;
- обновить changelog/current project state;
- пересоздать `SHA256SUMS`;
- получить green lint/assemble для exact source commit;
- получить signed `assembleRelease` с постоянным ключом перед публикацией APK;
- проверить реальный ADB/Fastboot path на целевом hardware;
- приложить APK/source archive/checksum;
- не публиковать raw account/device identifiers.
