# ADR 0003 — Phase 2 minimal ADB CNXN/AUTH evidence probe

Status: accepted for Phase 2, 2026-09-18.

## Context

The owner hardware run proved Android USB discovery, permission, `openDevice` and `claimInterface(false)` on the known POCO X3 Pro ADB descriptor shape. Historical Poco/Xiaomi ADB behavior remains intermittent, so the next useful boundary is not shell/push breadth but evidence at the first aprotocol exchange.

POCO F5 (`marble`) also has historical Fastboot evidence in both A2/current reference trees. The previous `main` evidence records a canonical Fastboot peer at `18D1:D00D`, `getvar:product -> marble`, unlocked state and successful bootloader/fastbootd command traffic. A2 records a `marble` campaign where host refresh could rediscover/claim after a failure but writes stayed broken until the target left and re-entered Fastboot, causing a real detach/attach re-enumeration. These observations are retained for the later Fastboot phase and are not converted into an automatic retry/reboot policy.

## Decision

1. Add pure JVM `:protocol:adb` only because it owns executable aprotocol framing and a real handshake state machine.
2. The first probe sends exactly one `CNXN`; if the peer requests AUTH it sends a signature and, only after a repeated token, the ADB mincrypt public key. It accepts terminal `CNXN` and then closes the USB connection.
3. No ADB service packets (`OPEN`, `WRTE`, shell, sync/push, reboot) are implemented in this slice.
4. Android selection requires the ADB interface protocol triplet `FF/42/01` plus bulk IN+OUT, but never a Xiaomi/Google/vendor VID whitelist.
5. The probe claims with `force=false` and does not retry a failed transfer, send a second CNXN, clear endpoint halt, reopen the connection or silently switch strategy.
6. The host RSA key is app-private and persistent so a user-approved target can be recognized on later runs. AUTH token/signature/public-key bytes and raw peer banners are excluded from diagnostics and ZIP evidence.
7. Evidence records packet semantic/type, payload byte count, USB call requested/result byte count, call duration, timeout budget, terminal outcome, peer version/max payload and coarse peer kind.
8. `adb-events.txt` becomes a separate bundle section. Existing USB evidence remains separate so the failing layer can be read without reconstructing one mixed log.

## Consequences

A successful run proves only USB + ADB handshake. It does not prove shell, sync, file transfer, recovery services, reboot, or any Fastboot function. A failure remains actionable because the evidence differentiates interface/open/claim, header write, payload write, header read, payload read, protocol validation and AUTH stage without exposing authentication material.
