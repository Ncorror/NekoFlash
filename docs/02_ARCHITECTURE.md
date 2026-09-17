# Architecture

Phase 1 closed with three production boundaries:

- `:app` — Android presentation/bootstrap shell.
- `:core:model` — target/session identity and cross-feature outcome vocabulary.
- `:core:diagnostics` — structured local evidence primitives.

Phase 2 has started with one additional executable boundary:

- `:transport:usb-android` — Application-scoped Android USB evidence owner for discovery, permission callbacks, descriptor/interface/endpoint snapshots, and a reversible `openDevice -> claimInterface(false) -> release -> close` probe.

The USB evidence boundary deliberately sends no ADB/Fastboot bytes. It classifies a claim candidate only by the concrete transport need for both bulk IN and bulk OUT; vendor, product, class, subclass and protocol are recorded as evidence rather than used as Xiaomi/Poco bans.

Current flow:

```text
Phase 2 evidence UI
        ↓
:transport:usb-android
        ↓
Android UsbManager / UsbDeviceConnection
        ↓
structured :core:diagnostics evidence
```

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
