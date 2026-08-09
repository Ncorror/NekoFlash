# Physical-device validation

This file keeps only concrete device evidence that is still useful for release decisions. It intentionally excludes future test plans, temporary gate numbering, internal workflow notes and raw logs.

## Release boundary

A green Android build proves compilation/lint/signing for the tested commit; it does not prove physical USB behavior. A public release candidate must receive a short hardware smoke on the exact merged build used for release.

No physical-device PASS for `6.0.0-alpha10` build `232` is recorded in the repository state audited for this release. The evidence below is retained from earlier real-device sessions and must not be presented as an exact-alpha10 PASS.

## Retained device evidence — POCO X3 Pro (`vayu`)

### ADB / Fastboot transport

Recorded on `6.0.0-alpha6-dev-nekoflash+9823538147e0.30170789394`:

- `adb reboot system` — **PASS**;
- `adb reboot bootloader` — **PASS**;
- Fastboot handshake — **PASS**;
- Fastboot DATA 32 MiB through Android `UsbRequest` — **PASS**;
- Fastboot DATA 32 MiB through synchronous `bulkTransfer` — **PASS**;
- Fastboot DATA 32 MiB through Native USBFS — **PASS**;
- larger DATA payloads, including a 128 MiB `recovery.img` — **PASS**;
- detach/cancel during DATA — **fail-closed**, staged data cleanup confirmed;
- mutation commands remained blocked by the device while bootloader reported `unlocked=no`.

### Legacy A-only topology and Recovery preflight

Recorded on `6.0.0-alpha5-dev-nekoflash+b220d48b796d.30042304245`:

- Fastboot handshake consistently reported `product=vayu` and `unlocked=no`;
- `current-slot`, `slot-count` and `slot-suffix` were unsupported/not found, matching legacy A-only behavior rather than synthetic A/B targets;
- six 100 MiB native DATA profiles completed with final `OKAY`;
- a 128 MiB `recovery.img` completed private-staged ASYNC and SYNC download-only transfer checks;
- an intentional native-transfer cancellation broke the current Fastboot session as designed; a later full reconnect restored the handshake;
- terminal `reboot-recovery` returned `OKAY`;
- Recovery ADB Sideload peer detection — **PASS**;
- ZIP import, SHA-256 calculation and recovery-package structure recognition — **PASS**.

Not performed in that session:

- no `flash:*` mutation was issued;
- no real ADB Sideload payload transfer/result was completed;
- no final Mi Unlock mutation was performed.

## Retained UI/account smoke evidence

Recorded on source/CI head `b220d48b796d09b13974d8dc39d090efbc2afb55`:

- launcher artwork displayed correctly — **PASS**;
- fullscreen Welcome layout accepted without vertical-scroll regression — **PASS**;
- ADB Sideload pre-verification state did not show a false green success indicator — **PASS**;
- Mi Account login completed in the same app run without a stale blocked-host banner — **PASS**;
- compact log did not expose raw Mi Account ID, token/cookie values; only expected cookie names were shown — **PASS**;
- Mi Unlock action with no connected Fastboot device stopped safely before any unlock operation.

## Minimum exact-release smoke

For the release commit, retain only a sanitized summary of:

1. host app cold start and USB permission flow;
2. ADB attach/authentication plus one read-only shell command;
3. ADB disconnect/reconnect without stale session state;
4. Fastboot handshake and read-only `getvar product` / bootloader state;
5. one representative Fastboot DATA transfer path appropriate for the release;
6. cancel/detach behavior returning fail-closed;
7. Recovery/ADB Sideload peer detection when that feature is part of the release smoke;
8. confirmation that sanitized diagnostic export contains no account tokens/cookies or raw device identifiers.

Do not commit raw USB traces, serial numbers, IMEI/MEID/IMSI/ICCID, Android ID, account IDs, cookies, tokens or signing secrets. Record only version/build, non-unique model/codename, mode, action, result and any payload SHA-256 needed to reproduce the test.
