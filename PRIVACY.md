# NekoFlash privacy note

This document describes the behavior of the current NekoFlash source tree. It is not a substitute for a jurisdiction-specific privacy policy if the application is later distributed through a store or service that requires one.

## Local device operations

ADB, Fastboot, USB discovery, file transfer, flashing, sideload, diagnostics and command execution are performed locally between the Android host running NekoFlash and the USB-connected target device.

The current source tree contains no analytics, advertising, third-party crash-reporting or telemetry SDK integration.

## Xiaomi / Mi Unlock network traffic

The optional Mi Unlock workflow requires Internet/network access and communicates over HTTPS with official Xiaomi account and unlock endpoints used by that flow, including `account.xiaomi.com`, `api.account.xiaomi.com` and the bounded regional unlock hosts defined in source.

Mi account identifiers, WebView cookies, `passToken`, `deviceId`, `ssecurity`, service cookies and unlock request data are handled only for the Mi Unlock workflow. The application provides logout/session-clearing behavior for the Mi account flow.

NekoFlash does not operate its own account, analytics or telemetry backend.

## Files and logs

NekoFlash uses a workspace in shared storage for selected payloads, imported files, logs, protocol traces and exported diagnostic reports. Local logs can contain commands, device properties, filenames, USB information and operation results.

Sanitized sharing/export paths apply the project's report sanitization logic, but users should still review diagnostic files before sharing them publicly.

The application declares broad storage access because the current distribution model expects direct access to firmware and diagnostic files. Application backup is disabled in the Android manifest.

## Permissions relevant to data handling

The manifest declares Internet/network state permissions for the optional Xiaomi / Mi Unlock feature, storage access for user-selected firmware/diagnostic files, foreground-service and wake-lock permissions for long USB operations, notification permission where required by Android, and overlay-hiding protection for sensitive screens. It does not request contacts, SMS, microphone, camera, phone or location permissions.
