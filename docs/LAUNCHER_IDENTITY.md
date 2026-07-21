# Launcher identity NekoFlash

Системное имя приложения — **NekoFlash**. Launcher resources и artwork остаются отдельным asset lock и не связаны с функциональным Scope Reset.

## Канонические assets

- [`artwork/nekoflash_launcher_v5.9.13.png`](artwork/nekoflash_launcher_v5.9.13.png) — канонический raster source, имя сохраняет версию происхождения;
- [`artwork/nekoflash_launcher_safe_zone_preview_v5.9.13.png`](artwork/nekoflash_launcher_safe_zone_preview_v5.9.13.png) — safe-zone preview;
- [`artwork/nekoflash_launcher_monochrome_v5.9.13.svg`](artwork/nekoflash_launcher_monochrome_v5.9.13.svg) — monochrome source.

`LAUNCHER_ARTWORK_SHA256.txt` блокирует канонический raster source. Android resources должны сохранять legacy transparent insets, adaptive foreground, round icon и monochrome reference.

Косметическая переработка launcher identity выполняется отдельной задачей с обновлением asset lock и Android lint.
