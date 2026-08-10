# NekoFlash alpha10.1 Final Validation Evidence

## Release

-   Release: `v6.0.0-alpha10.1`
-   Base commit: `de1225b9cd5be110c72c6f130320b2d62d927cc0`

## Test host

Validated host:

-   Device: POCO F7
-   Android: 16
-   Manufacturer: Xiaomi
-   Model: 25053PC47G
-   Codename: onyx

## Validation scope

This document records validation evidence collected from:

-   ADB terminal logs
-   USB session snapshots
-   Recovery/Sideload logs
-   Fastboot transition logs
-   Device screenshots

## ADB DEVICE validation

Status: PASS

Validated:

-   USB device detection
-   RSA authorization
-   ADB DEVICE mode
-   shell_v2 capability
-   stdout/stderr separation
-   exit code handling
-   repeated shell sessions
-   reconnect after disconnect

Final marker:

`NEKOFLASH_ALPHA10_FINAL_OK`

Result:

`ADB SHELL EXIT: 0`

## ADB lifecycle validation

Status: PASS

Validated:

-   ADB stream lifecycle handling
-   stale CLSE handling
-   new stream creation after previous stream close
-   continued shell operation after reconnect

Observed non-blocking event:

`ADB stream closed during write`

Classification:

Cleanup item only.

Reason:

Recovery evidence confirmed successful operation completion.

## Fastboot validation

Status: PASS

Validated:

-   ADB to bootloader transition
-   fastboot handshake
-   product detection
-   fastbootd detection

## Recovery validation

Status: PASS

Validated:

-   ADB Recovery detection
-   Recovery communication
-   recovery log collection
-   result parsing

## ADB Sideload validation

Status: PASS WITH NOTE

Validated:

-   Sideload mode detection
-   ZIP selection workflow
-   package transfer
-   Recovery result verification

Recovery success evidence:

`operation_end status=0`

## Screenshot evidence

Screenshots confirm:

-   Evolution X successfully booted
-   Android setup/system screens accessible
-   device information screens visible
-   NekoFlash ADB SIDELOAD interface operational
-   ZIP package selection available

## Final result

NekoFlash alpha10.1 validation status:

PASS

Blocking issues:

None.

Known follow-up cleanup:

Improve classification of benign post-success ADB transport close
messages.

## Suggested repository location

    docs/evidence/ALPHA10_FINAL_VALIDATION.md
