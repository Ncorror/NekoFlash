# Physical-device validation

This document is the public index of NekoFlash physical-device testing. It contains only hardware-validation facts that are useful for release qualification. Raw diagnostic logs, USB traces, account data, device serials, private photographs, chat/continuation notes, recovery archives, temporary plans and operator-state files are intentionally not part of the public repository.

## Test host

The 2026-08-10 qualification sessions used a POCO F7 as the Android USB host. NekoFlash reported the host platform as Xiaomi `25053PC47G` / `onyx`, Android 16 (SDK 36).

## Qualification summary

| Target | Product | Evidence scope | Result |
|---|---|---|---|
| POCO X7 Pro | `rodin` / model `2412DPC0AG` | ADB, Fastboot, unlock history, A/B inventory, real `vendor_boot` flashing, Quick Flash slot selector, Recovery/Sideload classification, final boot, post-fix ADB shell lifecycle | **PASS** |
| POCO X3 Pro | `vayu` | exact release-base regression: repeated ADB shell/reconnect, bootloader Fastboot, real `recovery` flash, Recovery/Sideload, Fastbootd, final boot | **PASS with reporting note** |

Detailed evidence:

- [POCO X7 Pro (`rodin`) hardware validation](evidence/POCO_X7_PRO_ALPHA10.md)
- [POCO X3 Pro (`vayu`) release-base hardware validation](evidence/POCO_X3_PRO_ALPHA10_1.md)

## POCO X7 Pro release evidence

The `rodin` campaign contains both the earlier alpha10 baseline and the final hotfix qualification.

Hardware-confirmed items include:

- canonical ADB and Fastboot connectivity;
- real Xiaomi unlock flow and persistent unlocked state after reconnect;
- A/B partition discovery;
- real 64 MiB `vendor_boot` payload transfer and flash;
- Fastbootd/userspace detection;
- ROM/GApps/Recovery Sideload transport;
- post-P4 Quick Flash slot selector UI;
- real all-slot `vendor_boot_a` + `vendor_boot_b` flash;
- P3 close-before-`DONEDONE` classification as `VERIFY_PENDING` instead of transport failure;
- final Android/Evolution X boot;
- reproduction of the one-shot ADB shell stream lifecycle regression;
- post-fix H13 with repeated shell commands and `NEKOFLASH_ALPHA10_FINAL_OK`, all exit code 0.

The final H13 verification used build `6.0.0-alpha10+3d3e85e5fce1.31369769922`. Five ADB operations started, five succeeded, zero failed, and no warning/error remained in the session summary.

## POCO X3 Pro release-base regression

The `vayu` campaign used build `6.0.0-alpha10+de1225b9cd5b.31373308470`, which corresponds to the merged release-base commit `de1225b9cd5be110c72c6f130320b2d62d927cc0`.

Hardware-confirmed items include:

- repeated `shell_v2` commands and reconnects after the ADB CLSE fix;
- final marker `NEKOFLASH_ALPHA10_FINAL_OK` with exit code 0;
- ADB-to-bootloader transition and Fastboot handshake;
- real 128 MiB `recovery.img` flash through Native USBFS;
- ADB Recovery and ADB Sideload mode detection;
- three real sideload workflows with Recovery-side success evidence;
- ADB-to-Fastbootd transition and userspace Fastboot detection;
- final boot into Evolution X / Android 14.

The session summary recorded zero failed operations. Three sideload operations remained counted as `verificationPending` even after Recovery-side success evidence was collected. This is retained as a reporting/bookkeeping cleanup item, not a demonstrated installation failure.

## Release qualification boundary

The evidence has two complementary levels:

1. **Hotfix-path hardware qualification on POCO X7 Pro (`rodin`).** The P4 slot-selector path and P3 Sideload classification were exercised on build `34ca8302...`; the ADB shell lifecycle fix was then validated on build `3d3e85e...`. Those fixes were subsequently merged into the release-base history.
2. **Exact merged release-base regression on POCO X3 Pro (`vayu`).** Build `de1225b9cd5b...` exercised the merged transport line across ADB, Fastboot, Recovery, Sideload and Fastbootd.

This distinction is intentional: a component-level hotfix test must not be represented as if it were an exact-head run, and an exact-head run on another device must not be represented as a replacement for device-specific `rodin` evidence.

## Public evidence policy

Only sanitized conclusions and non-unique device metadata belong in GitHub documentation. Do not commit:

- raw USB/Fastboot traces;
- device serials or unique identifiers;
- account names, phone numbers, cookies, tokens or RSA material;
- private screenshots/photos containing account information;
- chat/continuation/recovery-context documents;
- backup archives or temporary operator-state reports.

The private validation archive may retain those materials for local audit and recovery, but it is not a public repository artifact.
