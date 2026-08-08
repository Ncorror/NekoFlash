# NekoFlash privacy note

This document describes the current source behavior of NekoFlash alpha9. It is
not a substitute for a jurisdiction-specific privacy policy if the application
is later distributed through a store or service that requires one.

## Local device operations

ADB, Fastboot, USB discovery, file transfer, flashing, sideload, diagnostics,
and command execution are performed locally between the Android host running
NekoFlash and the USB-connected target device.

The current source tree contains no analytics, advertising, crash-reporting, or
third-party telemetry SDK integration.

## Xiaomi / Mi Unlock network traffic

The Mi Unlock workflow uses Android WebView/HTTPS and communicates with official
Xiaomi account and unlock endpoints required by that workflow, including
`account.xiaomi.com`, `api.account.xiaomi.com`, and regional
`*.miui.com` / `*.intl.miui.com` unlock hosts.

Mi account identifiers, WebView cookies, `passToken`, `deviceId`, `ssecurity`,
service cookies, and unlock request data are handled only for the Mi Unlock
workflow. The application provides logout/session-clearing behavior for the Mi
account flow.

## Files and logs

NekoFlash uses its workspace under the device's shared storage for selected
payloads, imported files, logs, protocol traces, and exported diagnostic
reports. Local logs can contain commands, device properties, filenames, USB
information, and operation results. Exported reports pass through the project's
report sanitization logic, but users should still review diagnostic files before
sharing them publicly.

The application declares broad storage access because the current distribution
model expects direct access to user-selected firmware and diagnostic files.
Application backup is disabled in the manifest.

## No project-operated backend

The current source contains no NekoFlash-operated analytics or cloud backend.
Network access exists for the Xiaomi account/unlock feature and normal Android
WebView networking used by that feature.
