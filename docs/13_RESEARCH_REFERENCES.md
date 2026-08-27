# NekoFlash — Protocol / Platform Research References

Проверено: **2026-08-27**.

Эти ссылки — research references, а не источник продуктовой политики. Product Charter имеет собственную силу; AOSP/Android references определяют protocol/platform reality.

## ADB

- AOSP ADB services (`shell`, `shell,v2`, `exec`, `abb`, raw/local/tcp services):
  `https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/SERVICES.TXT`
- AOSP ADB source tree / protocol implementation:
  `https://android.googlesource.com/platform/packages/modules/adb/`

Ключевой вывод для архитектуры: ADB предоставляет service-oriented, multi-channel model; `shell,v2` отделяет stdin/stdout/stderr/exit semantics.

## Fastboot

- Current AOSP Fastboot README/protocol:
  `https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/README.md`
- AOSP Fastboot protocol implementation:
  `https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/protocol.cpp`
- Android fastbootd documentation:
  `https://source.android.com/docs/core/architecture/bootloader/fastbootd`

Ключевой вывод: Fastboot host-driven/synchronous, имеет response states `INFO/TEXT/OKAY/FAIL/DATA`; DATA phase может передавать bytes в обе стороны. Реальный `FAIL` — часть peer protocol response.

## Android USB / foreground operations

- Android USB Host overview:
  `https://developer.android.com/develop/connectivity/usb/host`
- Foreground service type `connectedDevice`:
  `https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device`

Ключевой вывод: долгие операции с physically connected USB device должны проектироваться вокруг корректного application/service lifecycle, а не screen lifetime.

## Storage / SAF

- `ContentResolver` / file descriptors:
  `https://developer.android.com/reference/android/content/ContentResolver`
- Storage Access Framework:
  `https://developer.android.com/guide/topics/providers/document-provider`

Ключевой вывод: `content://` provider не надо автоматически считать обычным seekable filesystem path; random-access protocol требует явной capability/staging strategy.

## Adaptive UI

- Compose Material 3 Adaptive release/docs:
  `https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive`
- Adaptive apps guidance:
  `https://developer.android.com/develop/adaptive-apps`
- Navigation 3:
  `https://developer.android.com/guide/navigation/navigation-3/get-started`

На дату фиксации Material 3 Adaptive имеет stable 1.3.0; финальные dependency versions должны выбираться при bootstrap проекта, а не быть навечно захардкожены этим документом.

## Project references

- Current clean NekoFlash repository: `https://github.com/Ncorror/NekoFlash`
- Legacy frozen snapshot: `../reference/archives/NekoFlash-main-legacy.zip`
- A2 frozen snapshot: `../reference/archives/NekoFlash-A2-frozen.zip`
- Founding canonical snapshot: `../reference/archives/NekoFlash-canonical-20260827.zip`
- Snapshot integrity manifest: `../reference/SHA256SUMS`

Исторические Legacy/A2 sources больше не идентифицируются по текущему URL `Ncorror/NekoFlash`. Для migration/evidence используются immutable reference snapshots и их SHA-256.
