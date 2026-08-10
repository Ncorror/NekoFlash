# POCO X3 Pro (`vayu`) release-base hardware validation

## Scope

This file records the physical regression run performed on POCO X3 Pro after the alpha10 hotfix chain was merged. It is intentionally a sanitized summary rather than a dump of raw USB/device logs.

### Test configuration

- Host: POCO F7; NekoFlash-reported host platform Xiaomi `25053PC47G` / `onyx`, Android 16 (SDK 36).
- DUT: POCO X3 Pro, product/device `vayu`.
- Device OS observed during normal ADB: Android 14 / Evolution X.
- Build: `6.0.0-alpha10+de1225b9cd5b.31373308470`.
- Release-base commit: `de1225b9cd5be110c72c6f130320b2d62d927cc0`.

## ADB DEVICE and stream lifecycle

The release-base build established authorized ADB DEVICE mode with `shell_v2`. The hardware run successfully executed separate one-shot shell commands including:

- `getprop ro.product.device` -> `vayu`;
- `id` -> shell user;
- `pwd` -> `/`;
- `getprop ro.build.version.release` -> `14`;
- `echo NEKOFLASH_ALPHA10_FINAL_OK` -> marker returned with exit code 0.

The marker was also repeated after reconnect. Logs show stale `CLSE` packets from older local stream IDs being ignored rather than breaking the next shell stream.

**ADB lifecycle regression: PASS.**

## Bootloader Fastboot

`adb reboot bootloader` was accepted and the device re-enumerated in Fastboot. The handshake reported `product=vayu` and the device was identified as a legacy A-only topology.

**ADB -> bootloader Fastboot transition: PASS.**

## Real Recovery flash

A real `recovery.img` flash was performed:

- payload: 128 MiB;
- transport: Native USBFS;
- application-observed throughput: approximately 42 MB/s;
- final `flash:recovery` result: success.

The device was then rebooted into Recovery.

**Real Recovery partition flash: PASS.**

## Recovery and ADB Sideload detection

The device was correctly classified first as ADB Recovery and then as ADB SIDELOAD. The ADB banner identified `vayu`, and the Sideload peer mode was selected correctly.

**Recovery/Sideload mode detection: PASS.**

## Real Sideload workflows

Three different package paths were exercised.

### 1. Firmware package

- size: approximately 103 MiB;
- transfer reached the Sideload completion path;
- Recovery reconnect succeeded;
- Recovery evidence contained `operation_end - status=0`.

**Recovery-verified result: SUCCESS.**

### 2. Evolution X package

- size: approximately 2.18 GiB;
- Sideload reached `DONEDONE`;
- Recovery evidence later reported that the script succeeded and the process ended without errors.

A post-result collection step emitted `ADB stream closed during write`, but the later Recovery evidence still confirmed successful installation. This is retained as a logging/severity cleanup item rather than converted into a package failure.

**Recovery-verified result: SUCCESS with non-blocking log note.**

### 3. DerpXtreme package

- size: approximately 14.6 MiB;
- transport closed before `DONEDONE` after approximately 100% had been sent;
- Recovery reconnected;
- Recovery evidence contained `operation_end - status=0`.

This exercises the same guarded close-near-completion class that P3 was designed for.

**Recovery-verified result: SUCCESS; close-before-`DONEDONE` handled non-fatally.**

## Fastbootd

`adb reboot fastboot` was accepted. After re-enumeration the application identified:

- Fastboot product `vayu`;
- userspace Fastboot / Fastbootd;
- `super` as the super partition.

**ADB -> Fastbootd transition: PASS.**

## Final boot evidence

Physical screenshots/photos show:

- POCO X3 Pro device identity;
- Evolution X setup/system UI;
- Android 14 / Evolution X information after the Sideload cycle.

**Final boot regression: PASS.**

## Session-result note

The complete diagnostic summary reported:

- operations started: 15;
- succeeded: 12;
- failed: 0;
- cancelled: 0;
- verification pending: 3;
- last outcome: `VERIFY_PENDING`.

All three Sideload workflows subsequently had Recovery-side success evidence, so the retained `verificationPending=3` count indicates reporting/bookkeeping was not promoted after verification. It should not be rewritten as three failed installations.

A single retained `ADB stream closed during write` error occurred during post-Sideload result collection even though later Recovery evidence confirmed success. This should be treated as a result-reporting/severity cleanup candidate.

## Final POCO X3 Pro matrix

| Check | Result |
|---|---|
| ADB DEVICE / RSA / `shell_v2` | **PASS** |
| Repeated ADB shell streams | **PASS** |
| Reconnect + final marker | **PASS** |
| ADB -> bootloader Fastboot | **PASS** |
| Real 128 MiB Recovery flash | **PASS** |
| ADB Recovery detection | **PASS** |
| ADB Sideload detection | **PASS** |
| Firmware Sideload + Recovery verification | **PASS** |
| Evolution X Sideload + Recovery verification | **PASS with log note** |
| Close-before-`DONEDONE` Sideload + Recovery verification | **PASS** |
| ADB -> Fastbootd | **PASS** |
| Final Evolution X / Android boot | **PASS** |
| Operation failures in session summary | **0** |

## Public evidence rule

Do not commit raw serials, USB traces, private photographs, account data or chat/recovery archives. This sanitized report is the public evidence artifact.
