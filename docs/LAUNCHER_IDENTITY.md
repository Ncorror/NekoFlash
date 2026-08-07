# Launcher identity NekoFlash

Системное имя приложения — **NekoFlash**. Launcher resources и artwork остаются отдельным asset lock и не связаны с функциональным Scope Reset.

## Канонические assets

- [`artwork/nekoflash_launcher_circle_cat_v6.0.0.png`](artwork/nekoflash_launcher_circle_cat_v6.0.0.png) — канонический raster source: только круглый badge с котом без нижнего wordmark;
- [`artwork/nekoflash_launcher_monochrome_v5.9.13.svg`](artwork/nekoflash_launcher_monochrome_v5.9.13.svg) — monochrome source.

`LAUNCHER_ARTWORK_SHA256.txt` блокирует канонический raster source. Android resources должны сохранять безопасные прозрачные inset-ы, adaptive foreground, round icon и monochrome reference.

В alpha5 launcher очищен: из репозитория удалены старый raster `v5.9.12` и safe-zone preview, чтобы не раздувать source ZIP. Новая каноническая иконка использует только круг с котом, как подтверждено maintainer на устройстве.
