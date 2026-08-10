# POCO X7 Pro (`rodin`) hardware validation

## Scope

This file records the physical-device evidence for the POCO X7 Pro campaign. It intentionally excludes raw traces, unique serials, account information, private chat/continuation notes and unredacted photographs.

### Test configuration

- Host: POCO F7; NekoFlash-reported host platform Xiaomi `25053PC47G` / `onyx`, Android 16 (SDK 36).
- DUT: POCO X7 Pro, model `2412DPC0AG`, product/device `rodin`.
- Connection: USB OTG, Type-C to Type-C.
- Host root: not required.

## Earlier alpha10 baseline

The retained repository history already contains real-device evidence on `rodin` for:

- canonical ADB connection and RSA authorization;
- `shell_v2` negotiation;
- ADB reboot to bootloader;
- canonical Fastboot handshake;
- Xiaomi unlock-token parsing and real unlock flow;
- persistent `unlocked=yes` after reconnect;
- A/B partition inventory;
- real 64 MiB Native USBFS transfer and `vendor_boot_b` flash;
- Fastbootd/userspace detection;
- multi-gigabyte ROM Sideload and GApps Sideload transport;
- final return to normal ADB DEVICE mode.

Those runs exposed two late alpha10 issues that required dedicated hardware retest: P3 Sideload result classification and P4 Quick Flash slot selection.

## 2026-08-10 — P4 Quick Flash slot selector

Build under test:

`6.0.0-alpha10+34ca8302eb59.31363505877`

Hotfix commit:

`34ca8302eb59bcd437dae0de3c9b45ba036f30c4`

### UI result

The post-fix Quick Flash dialog was physically observed with explicit choices:

- active slot `a` -> `vendor_boot_a`;
- all slots `a..b` -> `--slot=all`;
- cancel.

**P4 slot-selector UI: PASS.**

### Real all-slot flash

The same hardware session reported:

- Fastboot product `rodin`;
- active slot `a`;
- two slots;
- bootloader unlocked;
- `vendor_boot_a`: 64 MiB payload, Native USBFS transfer 100%, flash success;
- `vendor_boot_b`: 64 MiB payload, Native USBFS transfer 100%, flash success;
- approximate payload throughput: 42 MB/s for each transfer.

**Real `vendor_boot_a` + `vendor_boot_b` flash: PASS.**

Qualification note: the offline manual's SAFE all-slots branch expected a cancel before mutation. The operator instead performed the stronger real all-slot flash. Functionally the target selection and both flashes passed, but the execution deviated from the SAFE-only branch of the test plan.

## 2026-08-10 — P3 ADB Sideload classification

The device entered ADB SIDELOAD with an ADB banner reporting:

- product/device `rodin`;
- model `2412DPC0AG`;
- peer mode `SIDELOAD`.

Payload:

`EvolutionX-16.0-20260703-rodin-11.8-Unofficial.zip`

Recorded size:

`3,889,931,331 bytes`

The transfer progressed through the logged 5% increments to approximately 95%, then the transport closed before `DONEDONE` after approximately 100% had been sent. NekoFlash did not classify this as a hard transport failure; the operation remained pending Recovery-side verification.

Session summary for the campaign recorded:

- operations started: 3;
- succeeded: 2;
- failed: 0;
- verification pending: 1;
- last outcome: `VERIFY_PENDING`.

**P3 classification path: PASS.**

`VERIFY_PENDING` is the expected classification for this branch; it is not itself proof of installation success. The subsequent physical boot evidence is what closes the system-level result.

An RSA authorization timeout occurred after the Sideload transport had already disconnected and does not invalidate the Sideload classification result.

## H12 — final Android boot

Physical photographs from the same campaign show:

- the device's About phone screen identifying POCO X7 Pro;
- an accessible Evolution X system screen after the Sideload cycle.

**H12 final Android boot: PASS.**

The original About phone photograph contains account information and therefore must remain private. It must not be committed to the public repository.

## H13 — ADB shell lifecycle regression before fix

On build `34ca8302...`, ADB DEVICE mode and `shell_v2` authorization succeeded. The first device query returned:

`ro.product.device = rodin` with exit code 0.

The final marker then failed because subsequent one-shot shell streams closed unexpectedly. After reconnect, the first shell command could succeed again while following commands failed. This reproduced the ADB stream close/lifecycle regression.

**H13 before lifecycle fix: FAIL / PARTIAL.**

This result is retained as regression evidence rather than hidden by the later fix.

## H13 — post-fix validation

Fixed build:

`6.0.0-alpha10+3d3e85e5fce1.31369769922`

Fix commit:

`3d3e85e5fce176daefb2fa71f36738f1119148c8`

On the same `rodin` hardware, the fixed build completed the repeated shell sequence:

- `getprop ro.product.device` -> `rodin`, exit 0;
- `id` -> shell user, exit 0;
- `pwd` -> `/`, exit 0;
- `getprop ro.build.version.release` -> `16`, exit 0;
- `echo NEKOFLASH_ALPHA10_FINAL_OK` -> marker returned, exit 0.

The log also shows stale `CLSE` packets for previous local stream IDs being ignored while the new stream continues normally.

Session summary:

- operations started: 5;
- succeeded: 5;
- failed: 0;
- cancelled: 0;
- verification pending: 0;
- warnings: 0;
- errors: 0;
- last outcome: `SUCCESS`.

**H13 final ADB after fix: PASS.**

## Final POCO X7 Pro matrix

| Check | Result | Evidence |
|---|---|---|
| Fastboot handshake / `product=rodin` | **PASS** | physical log |
| P4 Quick Flash slot selector | **PASS** | physical UI screenshot |
| Real `vendor_boot_a` flash | **PASS** | physical log + Fastboot result |
| Real `vendor_boot_b` flash | **PASS** | physical log + Fastboot result |
| ADB SIDELOAD detection | **PASS** | ADB banner / peer mode |
| P3 close-before-`DONEDONE` classification | **PASS / VERIFY_PENDING** | physical Sideload log + session summary |
| H12 final Android boot | **PASS** | private physical photographs |
| H13 pre-fix regression reproduction | **REPRODUCED** | physical ADB log |
| H13 fixed repeated shell lifecycle | **PASS** | fixed-build log + 5/5 session summary |

## Public/private boundary

The continuation backup is an audit source, not a GitHub artifact. Do not copy its `current-state`, `raw-private`, chat restore, backup, temporary report or operator-confirmation files into the repository. The public repository should retain this sanitized hardware summary only.
