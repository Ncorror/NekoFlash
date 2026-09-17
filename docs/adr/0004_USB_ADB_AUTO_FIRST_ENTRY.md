# ADR 0004 — USB/ADB auto-first entry with manual diagnostic fallback

Status: accepted for Phase 2, 2026-09-18.

## Context

The first Phase 2 slices intentionally exposed separate buttons for scan, permission, open/claim and ADB `CNXN`/`AUTH` so each boundary could be hardware-proven independently. Owner testing proved those layers, but leaving the diagnostic choreography as the ordinary product flow would regress a useful behavior already established in Legacy and intentionally preserved by A2.

Legacy handled a USB attach automatically and also ran a one-shot startup enumeration after a 350 ms settle delay when a device was already connected. Permission completion advanced to transport without requiring a second button press. A2 retained the same ownership principle in an Application-scoped coordinator and normalized pre-entry attach intents into the normal startup scan. Manual search remained a fallback for ambiguity and recovery, not a required happy-path step.

## Decision

1. The ordinary Phase 2 ADB entry is **auto-first** after the Welcome/entry gate: startup scan or USB attach -> canonical candidate selection -> Android USB permission when needed -> `openDevice`/`claimInterface(false)` inside the bounded ADB probe -> `CNXN`/`AUTH` handshake.
2. Startup enumeration uses the carried-forward Legacy settle delay of **350 ms**. This is an entry timing policy, not a transport retry loop.
3. Android attach delivery is restored with the vendor-specific class wildcard `<usb-device class="255" />`. The filter only delivers attach intents; it is not a support allowlist and does not select a protocol.
4. Automatic selection is deliberately narrower than manual diagnostics: exactly one physical device with exactly one canonical ADB `FF/42/01` interface and bulk IN+OUT may advance automatically. Multiple devices, multiple canonical ADB interfaces, and generic vendor bulk interfaces remain manual evidence cases.
5. The user may still need to approve Android's USB permission dialog and the target-side RSA authorization dialog. NekoFlash resumes automatically after the USB permission callback; RSA confirmation is handled by the already-running bounded handshake.
6. Manual `Scan USB`, `Permission`, `Open + claim`, and `ADB CNXN/AUTH` controls remain present as diagnostic/retry fallback. They are not the expected normal connection workflow.
7. A terminal ADB transport/protocol/auth failure is **not** silently retried in the same physical attachment generation. Automatic suppression lasts until detach or an explicit **New evidence session** re-arms the current device. This preserves the evidence-first no-hidden-retry rule from ADR 0003.
8. Fastboot must later follow the same product-level auto-first entry contract once its new protocol/transport boundary exists, including explicit re-enumeration evidence. This ADR does not introduce Fastboot bytes or copy the Legacy/A2 coordinator implementation.

## Consequences

Application scope owns sequencing, while protocol correctness remains inside `:protocol:adb` and USB I/O remains inside `:transport:usb-android`. The rewrite recovers the useful Legacy/A2 user experience without reintroducing vendor whitelists, a global mutable current connection, or a broad god coordinator. Hardware evidence should now be collectable by connecting a single unambiguous ADB target and approving platform dialogs, with manual buttons used only when investigating failures or ambiguity.
