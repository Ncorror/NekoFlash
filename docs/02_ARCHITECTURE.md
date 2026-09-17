# Architecture

Phase 1 closed with three production boundaries:

- `:app` — Android presentation/bootstrap shell.
- `:core:model` — target/session identity and cross-feature outcome vocabulary.
- `:core:diagnostics` — structured local evidence primitives.

Phase 2 has added two executable transport/protocol boundaries:

- `:transport:usb-android` — Application-scoped Android USB evidence owner for discovery, permission callbacks, descriptor/interface/endpoint snapshots, reversible open/claim probing, and the Android USB adapter used by the ADB handshake evidence probe.
- `:protocol:adb` — pure JVM ADB aprotocol framing plus the smallest `CNXN`/`AUTH` handshake state machine and persistent app-private RSA host-key formatting/signing. It owns no ADB service traffic.

The original USB evidence probe still sends no protocol bytes. The new ADB probe is a separate explicit action and sends only one `CNXN` plus the minimum `AUTH` response sequence required by the peer; it never sends `OPEN`, `WRTE`, shell, sync/push, reboot or Fastboot bytes. It classifies a claim candidate only by the concrete transport need for both bulk IN and bulk OUT; vendor, product, class, subclass and protocol are recorded as evidence rather than used as Xiaomi/Poco bans.

Shareable evidence now has a separate serialization boundary in `:core:diagnostics`: a deterministic, manifest-first multi-file ZIP writer accepts only explicit UTF-8 sections. Android-specific host/app/USB section construction and document/share integration remain in `:app`; the diagnostics core does not enumerate files or depend on Android storage. This preserves the useful A2 multi-file archive behavior without reviving its old ownership graph.

Evidence collection is scoped per target run. `NekoFlashApplication` keeps the USB permission receiver/Application owner alive while a manual **new evidence session** clears only the in-memory event sink and rotates a `sessionId`. The UI also records an owner-supplied target label because USB enumeration alone cannot reliably identify a model, especially when `UsbManager` reports zero devices. Immediately before TXT/ZIP export, the app performs a fresh `UsbManager` scan so `permissionGranted`, descriptors and device count are captured from live state rather than a stale Compose snapshot.

Current flow:

```text
Phase 2 evidence UI
        ↓
USB evidence action ─────────────→ :transport:usb-android
ADB handshake action → :protocol:adb → :transport:usb-android USB adapter
                                      ↓
                           Android UsbManager / UsbDeviceConnection
                                      ↓
                           structured :core:diagnostics evidence
```

The ADB probe is single-flight, uses the protocol-defined `FF/42/01` interface shape rather than a vendor whitelist, claims with `force=false`, performs no hidden reconnect or endpoint-halt recovery, and closes/releases after the terminal handshake outcome. Diagnostics record packet type, byte counts, individual USB call timing/results and terminal outcome, but never AUTH token/signature/public-key bytes or the raw peer banner.

Conceptual direction for later phases:

```text
Presentation / Device Workspace / Terminal
        ↓
Application use cases + operation-specific state
        ↓
ADB / Fastboot / Recovery / Vendor
        ↓
Target / SessionGeneration
        ↓
USB transport
```

`TargetId`, `SessionGeneration`, `TargetMode` and future transport handles remain separate concepts. No global mutable `currentDevice/currentConnection` contract is introduced.

## Future Fastboot DATA backend boundary

Fastboot DATA must not collapse into one opaque write call. The future engine keeps preselected transport modes so correctness and performance evidence remain visible:

- `ASYNC_USB_REQUEST` — A2's hardware-proven Java fallback baseline;
- `NATIVE_USBFS` — required high-throughput backend, reimplemented from evidence rather than copied wholesale;
- `SYNC_BULK` — bounded fallback/diagnostic mode when explicitly selected before DATA.

The Native USBFS ownership contract is fixed before implementation: two URBs with 256 KiB target blocks, adaptive reduction on `ENOMEM`, progress based only on normally reaped bytes, cancellation by `DISCARDURB` followed by `REAP`, no freeing memory while usbfs may own it, and process-level fail-closed poisoning if drain cannot be proven. No backend switch/retry occurs after `download:` starts.
