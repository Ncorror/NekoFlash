# ADR 0002 — Phase 2 USB evidence boundary and Fastboot backend retention

Status: accepted for Phase 2 start, 2026-09-17.

## Context

The clean rewrite intentionally removed USB/protocol code to establish ownership boundaries. That cleanup must not repeat a historical failure mode where a later clean tree omitted Native USBFS despite Legacy hardware evidence and A2 migration/test work. Separately, Poco/Xiaomi ADB behavior has been intermittent and the failing layer remains unknown.

## Decision

1. Introduce `:transport:usb-android` only when it owns executable Android USB behavior.
2. The first slice stops at discovery, permission, descriptor/interface/endpoint evidence, `openDevice`, and reversible `claimInterface(false)` probing. It sends no protocol bytes.
3. Bulk-pair eligibility is structural and vendor-neutral. Xiaomi/Poco/HyperOS are evidence targets, not host-side deny/allow lists.
4. Future Fastboot DATA must retain explicit `ASYNC_USB_REQUEST`, `NATIVE_USBFS`, and bounded `SYNC_BULK` modes. Native USBFS is a required capability, not an optional later optimization.
5. Native selection/preflight happens before `download:`. No transport switch is allowed after DATA negotiation starts.
6. Native correctness preserves confirmed-byte accounting, two 256 KiB URBs with adaptive `ENOMEM` reduction, `DISCARDURB -> REAP` before buffer release, and fail-closed poisoning when drain is unproven.
7. Shareable evidence must export the full structured event snapshot, not only the bounded UI tail, and must defensively redact likely secret/raw protocol material. Multi-file ZIP is the primary hardware-evidence bundle; it uses a manifest plus explicit named sections rather than recursively archiving app storage.

## Evidence carried forward

- Legacy POCO X3 Pro (`vayu`): real 128 MiB `recovery.img` Fastboot flash via Native USBFS, approximately 42 MB/s, success.
- Legacy POCO X7 Pro (`rodin`): real 64 MiB `vendor_boot_a` and `vendor_boot_b` Native USBFS flashes, approximately 42 MB/s each, success.
- A2 `vayu`: Java `ASYNC_USB_REQUEST` transferred exactly 134217728 confirmed bytes in about 4.437 s and `flash:recovery` succeeded. This is the fallback baseline.
- A2 Native USBFS overlay contained mode-policy and lifetime work but had not yet received a fresh A2 hardware PASS; therefore the rewrite must freshly validate it rather than claiming inherited verification.

## Consequences

Phase 2 can isolate Xiaomi/Poco failure layers before ADB framing is introduced. The owner hardware run proved the base permission/open/claim boundary on the connected Poco/Xiaomi target, so the next diagnostic layer is bulk I/O plus ADB `CNXN`/`AUTH`. Full evidence is now archivable from the app instead of being screen-only. The rewrite retains the A2 multi-file ZIP principle through a new deterministic bundle boundary rather than transplanting the A2 diagnostics store. Later Fastboot implementation has an explicit regression guard against silently losing Native USBFS performance and cancellation safety.
