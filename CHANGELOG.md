# Changelog

Release-level changes only. Commit-by-commit development notes, temporary plans and maintainer workflow history belong in Git history.

## v6.0.0-alpha10.1 — published hotfix release

- Fixed the Quick Flash slot-selection dialog so slotted partitions expose explicit active-slot and all-slots choices on hardware.
- Fixed ADB one-shot shell stream close handling so stale `CLSE` packets from older stream IDs do not break subsequent shell commands.
- Physically qualified the late alpha10 hotfix paths on POCO X7 Pro (`rodin`), including the Quick Flash slot selector, real `vendor_boot_a` / `vendor_boot_b` flashing, P3 Sideload classification, final Android boot and post-fix H13 ADB shell lifecycle.
- Ran an exact merged release-base regression on POCO X3 Pro (`vayu`) across ADB, bootloader Fastboot, real Recovery flashing, Recovery/Sideload, Fastbootd and final Android boot.
- Retained the POCO X3 Pro Sideload `verificationPending` counter and one post-success stream-close log as reporting/severity cleanup notes rather than misreporting them as installation failures.
- Published GitHub release assets under `v6.0.0-alpha10.1`; the APK embedded version remains `versionName=6.0.0-alpha10`, `versionCode=232`.

## 6.0.0-alpha10 (232) — source line

- Added Quick Flash slot-target selection: when a partition reports `has-slot=yes`, the user can choose the current slot or all reported slots; explicitly suffixed `_a` / `_b` targets remain concrete.
- Added desktop-fastboot-compatible `--slot` parsing for terminal `flash`, `erase` and `format`, including `--slot=all` expansion from device-reported slot information.
- Improved ADB Sideload completion handling so a near-complete transport close can remain pending Recovery verification instead of being reported immediately as a hard failure.
- Replaced the blocking flash progress dialog with a non-modal Operation Status panel while keeping Terminal & Logs available during long operations.
- Improved Fastboot terminal output: visible `OKAY` / `FAIL` / `INFO` responses and returned `getvar` values are shown in Console.
- Improved Xiaomi unlock token parsing for multi-line `oem get_token` INFO responses.
- Updated Unlock UI state so the unlock action is hidden when Fastboot already reports `unlocked=yes`.
- Clarified the Home bootloader status label and localized boolean values.
- Consolidated transport bookkeeping and progress calculations without changing the wire protocol.
- Preserved fail-closed production signing with permanent certificate verification in local scripts and GitHub Actions.
- Cleaned the public repository documentation: removed temporary planning/state/continuity material and consolidated permanent build, architecture, release and Termux guidance into `README.md`.

## 6.0.0-alpha8 (222)

- Hardened Fastboot numeric size parsing against overflow.
- Made operation brightness handling idempotent and restoration predictable.
- Aligned release scripts with the signed-release-only invariant.
- Added local keystore certificate verification and post-build APK signature verification.

## 6.0.0-alpha7 (220–221)

- Restored the persistent ADB/Fastboot terminal in Console.
- Simplified Home into a device/transport dashboard and streamlined Quick Flash and Mi Unlock UI.
- Reported missing ADB/Fastboot connections and invalid Fastboot tokens inline in Console.
- Preserved raw/OEM Fastboot passthrough for connected devices.

## 6.0.0-alpha6 (218, development line)

- Hardened Native USBFS/JNI ownership with RAII and bounded URB lifetime handling.
- Added transfer progress based on confirmed URB completions and overflow-safe calculations.
- Improved ADB reboot completion handling around expected USB teardown.
- Added structured diagnostic severity and improved compact/session logging.
- Performed broad source, warning and layout cleanup while preserving physical USB transport behavior.

## 6.0.0-alpha5 (217, development line)

- Introduced Recovery-first Quick Flash and partition/topology-aware target selection.
- Added Fastboot DATA lifecycle work and legacy A-only device handling.
- Hardened Xiaomi account/unlock URL and cookie scoping.
- Improved onboarding, sideload UX and device-smoke diagnostics.

## 6.0.0-alpha4 (216)

- Restored pending unlock and sideload verification state required by the active Android build.
- Fixed Android compilation regressions in the V6 cleanup line.

## 6.0.0-alpha3 (215)

- Completed the first V6 source cleanup after removing the legacy full Mi Flash workflow.
- Removed old service/profile/history layers and raw hardware-log artifacts from the active source tree.

## 6.0.0-alpha2 (214)

- Added the Home device/workspace card and navigation to Terminal, Quick Flash, ADB Sideload and Mi Unlock.

## 6.0.0-alpha1 (213)

- Started the V6 line by removing the legacy full Mi Flash workflow.
- Retained direct ADB/Fastboot transport, Terminal, Quick Flash, ADB Sideload, Mi Unlock and sanitized diagnostics.
