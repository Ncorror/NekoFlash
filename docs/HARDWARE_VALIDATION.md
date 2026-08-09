# Physical-device validation

This file records concrete physical-device evidence that is useful for release decisions. It intentionally excludes raw traces, account data, unique device identifiers, temporary test plans and internal workflow notes.

## 2026-08-09 — alpha10 hardware validation

### Test configuration

- Host: Xiaomi `25053PC47G` / `onyx`, Android 16 (SDK 36).
- DUT: POCO X7 Pro / model `2412DPC0AG`, product `rodin`.
- Connection: USB OTG, Type-C to Type-C.
- Host root access: not required.
- Tested application line: `6.0.0-alpha10`.
- Release certificate SHA-256 used by CI: `b122576dcaa5b1ceebd977545314bd515432f9e3fa50733fa8153a1e48a6c19e`.

The retained evidence package contains compact logs, sanitized protocol traces, USB-session summaries, screenshots/photographs and CI reports. Raw account credentials, Xiaomi/Fastboot token values, signing secrets and unique device serials are intentionally excluded from this repository summary.

### Build 227 — baseline and token-parser defect

Commit `24977be483735ca1b430a50e1d38a7d8ab95d802`.

Physical evidence:

- canonical ADB connection to `rodin` — **PASS**;
- ADB authorization and `shell_v2` negotiation — **PASS**;
- `adb reboot bootloader` — **PASS**;
- canonical Fastboot handshake with `product=rodin` — **PASS**;
- bootloader state before unlock: `unlocked=no`;
- `oem get_token` returned two Fastboot `INFO` token fragments followed by `OKAY`;
- the application failed to assemble those fragments and reported that the device token could not be read.

Conclusion: USB/Fastboot token delivery worked on hardware; the failure was in application-side response parsing. This defect led to P1.

### Build 228 / P1 — real Xiaomi unlock flow

Commit `5b8ad38a8743138f035c2b8d17d7be5b536d4019`.

Physical evidence:

- empty `getvar:token` correctly fell back to `oem get_token`;
- two token fragments were assembled into the expected 80-character value;
- Xiaomi account authorization completed and the service returned unlock data;
- the device accepted `download:00000100`;
- Native USBFS transferred the 256-byte unlock payload successfully;
- `fastboot oem unlock` returned final `OKAY`;
- after wipe/reboot, the device returned to canonical ADB DEVICE mode.

Independent Fastboot reconnect then confirmed:

- `product=rodin`;
- `unlocked=yes` — **PASS**;
- `secure=no`;
- `current-slot=b`;
- two slots reported.

The reconnect is the authoritative hardware proof that the bootloader actually remained unlocked after the unlock command.

### Build 229 / P2 — post-unlock state and A/B inventory

Commit `53f392cab21037392f45985df0baafbc6b728c55`.

Physical evidence:

- bootloader remained `unlocked=yes`;
- bootloader Fastboot reported `secure=no`;
- terminal `getvar all` output was visible and usable;
- slot count: 2, active slot: `b`;
- slotted partitions included `boot`, `init_boot`, `vendor_boot`, `dtbo`, `vbmeta`, `vbmeta_system`, `vbmeta_vendor` and `lk`;
- `super` and `userdata` were reported as non-slotted;
- `boot_a/b` and `vendor_boot_a/b` were reported as 64 MiB partitions.

CI passed lint/debug/release and release-certificate verification. This build also exposed six unused-resource lint warnings; they were removed before build 230.

### Build 230 — real `vendor_boot_b` flash

Commit `5a8c7cd982d2b88056e8eafdbe1b009ece34e9c2`.

Preflight on hardware:

- Fastboot handshake: `product=rodin`;
- active slot: `b`;
- `unlocked=yes`;
- `secure=no`;
- `has-slot:vendor_boot=yes`;
- `partition-size:vendor_boot_b=0x4000000` (64 MiB);
- imported `vendor_boot.img`: 67,108,864 bytes.

Real mutation:

- target: `vendor_boot_b`;
- Fastboot download size: `0x04000000`;
- transport: Native USBFS pipeline, depth 2, 256 KiB blocks;
- 64 MiB DATA transfer reached 100%;
- application-reported average payload speed: **42.13 MB/s**;
- raw Fastboot evidence recorded DATA success and `flash:vendor_boot_b` final `OKAY`;
- application reported successful completion of the flash.

Post-check:

- Fastboot reconnect again reported `product=rodin`, slot `b`, `unlocked=yes`, `secure=no`;
- diagnostic session recorded **8 operations started / 8 succeeded / 0 failed**.

Two ADB RSA timeout messages in the same wider session occurred during mode transitions and do not represent Fastboot flash failures.

### Build 230 — Fastbootd, ROM/GApps sideload and final boot

The same build was then exercised through recovery/fastbootd workflows.

Fastbootd:

- userspace Fastboot connection — **PASS**;
- `is-userspace=yes`;
- `super-partition-name=super`;
- snapshot status `none`;
- max download/fetch reported as 256 MiB;
- unlocked state remained `yes`.

ROM sideload:

- package size: 2,620,082,319 bytes;
- ADB peer mode: SIDELOAD;
- transfer progress was recorded continuously from start through approximately 95%;
- Recovery closed the ADB transport before `DONEDONE`;
- build 230 classified that close as a hard failure;
- physical Recovery evidence showed the device continuing into the post-install/recovery workflow rather than an obvious transport abort.

This exposed a result-classification defect: a near-complete Recovery-side close could be reported as a false negative.

GApps sideload:

- package size: 471,229,202 bytes;
- ADB peer mode: SIDELOAD;
- transfer progressed through approximately 95%;
- Recovery returned `DONEDONE`;
- NekoFlash correctly kept the result as `VERIFY_PENDING`, because `DONEDONE` proves stream completion but does not by itself prove package installation success.

Final device state:

- physical evidence shows the Android/crDroid setup welcome screen after the ROM/GApps sequence;
- a later canonical ADB connection reported `device=rodin`;
- ADB peer mode returned to normal `DEVICE`.

Conclusion: the ADB Sideload transport was exercised with multi-gigabyte and hundreds-of-megabytes payloads on real hardware. The session also identified the close-before-`DONEDONE` classification problem later addressed by P3.

### Build 231 / P3 — sideload result-classification fix

Commit `8c051aba4536133a124a62abb39b081b55b8aaef`.

Change:

- a near-complete Recovery-side close before `DONEDONE` at or above the guarded completion threshold is classified as `VERIFY_PENDING`;
- earlier transport/protocol failures remain `FAILED`.

Evidence level:

- lint — **PASS**;
- debug build — **PASS**;
- release build — **PASS**;
- lint issue count — 0;
- release certificate — **PASS**;
- no separate post-P3 physical ROM sideload rerun is present in the retained 2026-08-09 evidence.

Therefore P3 is **CI/code validated, not independently hardware-rerun after the patch**.

### Build 232 / P4 — Quick Flash slot-target selection

Commit `5714282cb75da22fa1a778f5946c23dca257fd48`.

Change:

- when a selected partition reports `has-slot=yes`, Quick Flash can ask for the active slot or all reported slots;
- non-slotted partitions do not require a slot dialog;
- explicitly suffixed targets such as `_a` or `_b` remain explicit and do not require another slot choice.

Evidence level:

- lint — **PASS**;
- debug build — **PASS**;
- release build — **PASS**;
- lint issue count — 0;
- release certificate — **PASS**;
- retained screenshot evidence is pre-P4 and demonstrates the missing slot choice that motivated the change;
- no post-P4 hardware screenshot or physical flash rerun is present in the retained 2026-08-09 evidence.

Therefore build 232 is the current CI-clean alpha10 head, while its P4 UI change still lacks a separate post-patch hardware rerun in this evidence set.

## 2026-08-09 evidence matrix

| Check | Result | Evidence level |
|---|---|---|
| Canonical ADB connection | **PASS** | hardware log + USB session |
| ADB authorization / `shell_v2` | **PASS** | hardware log |
| ADB reboot to bootloader | **PASS** | hardware log + trace |
| Canonical Fastboot handshake | **PASS** | hardware log + trace |
| `oem get_token` multi-fragment parsing after P1 | **PASS** | hardware log + sanitized trace |
| Xiaomi unlock server flow | **PASS** | hardware log |
| Native USBFS 256-byte unlock DATA | **PASS** | hardware log + trace |
| `fastboot oem unlock` command | **PASS** | final Fastboot `OKAY` |
| Persistent `unlocked=yes` after reconnect | **PASS** | independent hardware reconnect |
| A/B partition discovery | **PASS** | hardware `getvar all` |
| Native USBFS 64 MiB DATA transfer | **PASS** | hardware log + trace |
| Real `flash:vendor_boot_b` | **PASS** | hardware log + final `OKAY` |
| Post-flash Fastboot reconnect | **PASS** | hardware log |
| Fastbootd/userspace detection | **PASS** | hardware log + getvar trace |
| ROM ADB Sideload transport | **PASS with classification defect found** | long-transfer log + physical recovery/final-boot evidence |
| GApps ADB Sideload stream | **PASS / VERIFY_PENDING** | hardware log + `DONEDONE` |
| Final normal ADB DEVICE after ROM/GApps | **PASS** | hardware log + physical setup-screen evidence |
| P3 close-before-`DONEDONE` fix | **CI PASS** | code/CI; no post-patch hardware rerun |
| P4 Quick Flash slot selector | **CI PASS** | code/CI; no post-patch hardware rerun |

## Release boundary

The 2026-08-09 sessions provide strong real-device evidence for the alpha10 transport line through build 230, including an actual destructive bootloader unlock and a real 64 MiB partition flash.

They do **not** justify claiming that every build-232 change has been physically rerun. In particular:

- P3/build 231 has CI evidence but no post-fix ROM sideload rerun in the retained package;
- P4/build 232 has CI evidence but no post-fix slot-dialog screenshot or physical Quick Flash rerun.

A final release may reference the hardware evidence above, but an exact-head smoke on the merged release commit remains the strongest possible release qualification.

## Earlier retained baseline — POCO X3 Pro (`vayu`)

Earlier alpha5/alpha6 physical sessions remain useful as regression history:

- ADB reboot to system/bootloader — **PASS**;
- Fastboot handshake — **PASS**;
- 32 MiB Fastboot DATA through Android `UsbRequest`, synchronous `bulkTransfer` and Native USBFS — **PASS**;
- larger payloads including 128 MiB `recovery.img` — **PASS**;
- six 100 MiB Native USBFS DATA profiles — **PASS**;
- detach/cancel behavior was fail-closed and required reconnect as designed;
- legacy A-only topology was detected without inventing synthetic A/B targets;
- Recovery ADB Sideload peer detection — **PASS**;
- ZIP import, SHA-256 calculation and Recovery-package recognition — **PASS**.

Those sessions did not perform a real partition flash, final Mi Unlock mutation or full ADB Sideload package installation.

## Repository privacy rule

Do not commit raw USB traces, account IDs, Xiaomi cookies/tokens, device-token values, unique device serials, IMEI/MEID/IMSI/ICCID, Android ID, signing keys or service credentials.

For public release evidence, retain only the application version/build or commit, non-unique device model/codename, connection mode, operation, result, and non-secret payload metadata required to understand the test.
