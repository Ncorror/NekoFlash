# Capability Matrix

This file records capability/evidence direction, not current implementation status. Current implementation status lives only in `03_ROADMAP.md`.

| Area | Evidence carried forward | Rewrite direction |
|---|---|---|
| Target/session identity | A2/current tests and hardware observations | Separate `TargetId`, `SessionGeneration`, `TargetMode` and transport ownership |
| Diagnostics | A2/current source/tests + Phase 2 hardware run | Structured evidence first; per-target session IDs + owner labels; fresh pre-export USB snapshot; deterministic multi-file ZIP plus text fallback; manifest and explicit sections only; redact secrets in shareable bundles |
| Android USB | Legacy/A2 descriptors, permission and hardware observations + Phase 2 owner hardware PASS | Discovery/permission/open/claim are hardware-proven; ordinary entry is auto-first after Welcome with 350 ms startup scan and attach delivery, while generic/ambiguous candidates remain manual |
| ADB | Legacy breadth, A2 framing tests, current multi-stream evidence + POCO X3 Pro CNXN/AUTH hardware PASS | Minimal pure `:protocol:adb` handshake is hardware-proven for CNXN/AUTH only; auto-first entry advances a single canonical candidate through permission/open/claim/handshake; manual controls remain fallback; no services yet |
| Fastboot | Legacy breadth + ~42 MB/s Native USBFS hardware flashes; A2 128 MiB / ~4.437 s Java UsbRequest baseline; A2 transaction/DATA tests | Future single transaction lane; peer `FAIL` preserved; exact byte accounting; preselected `ASYNC_USB_REQUEST` + required `NATIVE_USBFS` + bounded `SYNC_BULK`; DATA IN still open |
| Recovery/Sideload | Legacy behavior, A2 mutation/correlation tests, current evidence | Distinguish transfer completion from installation success; retain `Unknown` semantics |
| Quick Flash | Legacy behavior/evidence | Future use case over the same Fastboot engine |
| Mi Unlock | Legacy source/historical evidence | Future vendor use case over generic Fastboot core; fresh final verification required |
| Brand | Legacy exact assets/colors | Welcome immutable; launcher reference retained; semantic Legacy color DNA |
