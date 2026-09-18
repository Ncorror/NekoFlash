# Full protocol/capability audit — 2026-09-18

This document is a repository-resident capability audit. It is **not** the current status source; current phase/status remains exclusively in `03_ROADMAP.md`.

## 1. Scope and evidence base

Audited together:

- production rewrite through commit `54b89ccf3f255dd8dc0fa4614a4a393f74211eda`;
- frozen Legacy snapshot;
- frozen A2 snapshot;
- previous `main` clean-tree snapshot;
- recorded POCO X3 Pro / POCO F5 hardware evidence;
- current AOSP ADB/Fastboot protocol/client behavior where the archives themselves were incomplete.

The purpose is not to restore an old tree. The purpose is to identify verified capabilities, correctness invariants, regressions, and genuine capability gaps so the clean rewrite does not accidentally become narrower than the professional tool it replaces.

## 2. Non-negotiable product rule: professional capability, not host authorization

NekoFlash is a professional Android host toolkit. It does not create a second authorization system above ADB/Fastboot/Recovery/vendor protocols.

Therefore:

- there is no Novice/Expert permission profile, unlockable Expert Mode, hidden capability tier, product VID/PID whitelist, or product allowlist that decides whether a valid protocol capability may be used;
- guided UI may warn, explain consequences, and request explicit/typed confirmation for destructive intent;
- raw protocol surfaces remain available without a narrower capability policy than typed UI;
- peer/device responses remain authoritative: `FAIL`, lock state, AVB/OEM restriction, missing ADB authorization, unsupported Recovery service, and vendor rejection are reported rather than replaced by a host-side ban;
- host-side stop/fail is mandatory only for a concrete correctness condition such as malformed framing, stale generation/ownership, impossible wire representation, ambiguous mutating write, invalid exact-byte promise, or cancellation/drain state that cannot be proved safe;
- `Unknown` is a legitimate professional outcome when mutation may have happened but final state cannot be proved; it is never hidden behind an automatic retry.

This is the recovered four-class rule from the previous clean-tree: **hard invariant / device authority / advisory / user intent**. Correctness protection is not capability restriction.

## 3. Current rewrite audit at `54b89ccf...`

Authoritative verification artifact for the auto-first commit reports:

- 31/31 JVM tests PASS;
- 0 failures, 0 errors, 0 skipped;
- `:transport:usb-android` lint: no issues;
- app lint: 0 errors, 11 non-blocking warnings.

Current production capability is deliberately narrow but real:

- Application-scoped USB discovery/permission ownership;
- descriptor/interface/endpoint evidence;
- open/claim hardware proof on POCO X3 Pro;
- deterministic multi-file evidence ZIP with per-target session identity;
- ADB packet framing and persistent RSA host identity;
- hardware-proven bounded `CNXN/AUTH` handshake on POCO X3 Pro;
- auto-first startup/attach -> permission -> open/claim -> handshake orchestration for one unambiguous canonical ADB target;
- manual diagnostic controls retained as fallback.

No ADB service stream and no Fastboot protocol byte exists in production yet. That is roadmap scope, not a product ban.

### 3.1 Current code hardening findings before broad ADB services

1. **USB permission callback identity is weaker than A2.** Current `UsbPermissionResult` carries only `deviceName` + granted state into the auto-flow. A2 encoded a process token + monotonically increasing permission generation into PendingIntent identity because extras do not define PendingIntent identity. The rewrite should restore that concept before more lifecycle complexity is layered on top.
2. **Mode-switch/re-enumeration tracking is not yet restored.** A2 had an explicit re-enumeration watch (750 ms cadence, bounded 16 attempts) and generation-aware detach semantics. Current auto-flow covers startup/attach but not ADB -> Recovery/Fastboot/fastbootd transitions. The POCO F5 historical evidence makes this worth preserving when those transitions return.
3. **Auto-flow orchestration tests are currently policy-heavy.** Candidate selection has tests, but stale permission callback, detach-during-permission, permission callback after re-enumeration, close/re-arm races, and one-shot suppression deserve executable state-transition tests.
4. **Architecture wording drift exists.** The auto-first coordinator is production behavior, so docs must not still describe the ADB probe as only a separate explicit action.

These are correctness/lifecycle gaps, not capability restrictions.

## 4. ADB audit across Legacy / A2 / previous main

### 4.1 Verified useful capability to carry forward

Legacy already demonstrated a broad direct-host workflow:

- ADB authentication and feature parsing;
- shell plus interactive shell;
- shell v2 and legacy fallback;
- generic raw ADB service execution;
- push/pull;
- install, install-multiple and APK/APKS/XAPK handling;
- sideload and Recovery correlation;
- reboot/service commands.

The previous `main` supplied the cleaner architecture that should inform the rewrite:

- one physical reader with multiplexed logical streams;
- bounded per-stream mailboxes/backpressure;
- concurrent streams;
- generic `AdbServiceCall` rather than a service allowlist;
- shell v2 + legacy shell;
- interactive shell;
- Sync `STAT`/`RECV`/`SEND`;
- install/install-multiple;
- reboot;
- forward/reverse;
- sideload + Recovery evidence.

The raw service path is important for a professional tool: a valid ADB service must not need a product-policy allowlist merely to reach the peer. Typed helpers add parsing and UX; they do not grant permission.

### 4.2 Genuine ADB gaps found in the archives

#### A. Sync `LIST` was promised but never implemented

Previous `main` documentation declared Sync `STAT/LIST/SEND/RECV` mandatory. The actual `AdbSyncSession` only exposes `stat`, `receive`, and `send`. `ID_LIST`/`ID_DENT` constants exist, but there is no production directory-list operation.

This is a confirmed docs-vs-code capability gap and must not be inherited as a false green box.

#### B. Modern Sync v2/compression is absent

No archive implements the modern Sync v2 request structures/flags for `stat_v2`, `list_v2`, `sendrecv_v2` or Brotli/LZ4/Zstd transfer compression. Previous hardware evidence saw peers advertise modern features, but the host client remained on the v1 Sync path.

This is new work, not a migration task.

#### C. Wireless ADB host transport is absent

No archive implements host-side modern Wireless debugging pairing/connect:

- pairing-code / pairing server client;
- TLS ADB transport;
- mDNS discovery of `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`;
- paired-device auto-connect.

Legacy can ask a USB-connected daemon to enter legacy `tcpip:<port>` mode, but there is no host-side network ADB transport. Modern Wireless ADB is therefore a genuine capability missing from all archived implementations.

#### D. Specialized ADB services are mostly reachable only through generic raw service

AOSP exposes professional services such as `jdwp:<pid>`, `track-jdwp`, `track-app:`, framebuffer, and other service strings. Previous `main`'s generic stream/service engine is the correct architectural escape hatch; dedicated typed UI/parsers are optional UX on top. Long-lived/streaming services still need appropriate streaming semantics rather than forcing them through a finite-response collector.

#### E. ADB USB Zero-Length Packet (ZLP) write semantics are not explicitly implemented or verified

Current AOSP ADB documentation requires host-to-device USB transfers whose length lands exactly on the endpoint `wMaxPacketSize` boundary to terminate with a **Zero-Length Packet (ZLP)**. Without that terminator the device-side FunctionFS reader can wait for more data; AOSP documents the failure as nondeterministic, ranging from apparently normal behavior to stalls or disconnects.

No supplied NekoFlash archive contains an explicit ADB ZLP contract, backend flag, endpoint-aware terminator, or test that proves this behavior. Previous `main`'s `AdbPacketWriter` splits writes into 16 KiB transfers and continues partial writes, but it does not prove what happens when an outbound transfer is an exact multiple of the endpoint max packet size. The rewrite must therefore treat **endpoint-aware ZLP behavior as a transport correctness requirement before broad `WRTE`/Sync/service traffic**. The implementation may use a backend capability/flag or an explicit supported zero-length transfer; the requirement is to prove the wire behavior, not to mandate one mechanism.

The current bounded `CNXN/AUTH` hardware PASS does not close this gap because those particular payload sizes do not exercise every max-packet boundary.

#### F. Modern install workflows are incomplete across the archives

Legacy and previous `main` contain normal install/install-multiple behavior, but no archive implements ADB **incremental install** with the Incremental Server/block-request/V4-signature (`.idsig`) workflow. Current AOSP also distinguishes `install-multiple` (multiple APKs for one package) from **`install-multi-package`** (one or more packages installed atomically); no supplied archive exposes a first-class atomic multi-package host workflow.

A generic shell/raw-service escape hatch is still valuable, but it is not evidence that the host-side file/session protocol for these install modes exists. Incremental install and atomic multi-package install are therefore genuine future professional capabilities, not hidden policy gates.

#### G. ADB Burst Mode / delayed-ACK throughput support is absent

Current AOSP documents the delayed-ACK/Burst Mode extension that negotiates additional in-flight send bytes through `A_OPEN`/`A_OKAY`, allowing multiple `A_WRTE` packets to be outstanding. None of the supplied archives implements this negotiation.

This is a **performance capability, not an initial correctness gate**. The ordinary one-`WRTE`/one-`OKAY` stream core must be correct first; Burst Mode can then be negotiated when both sides support it and benchmarked on real hardware. AOSP's own published USB-3 experiment reported a large throughput increase, but NekoFlash must measure its own devices/backends rather than importing that number as a promise.

## 5. Fastboot audit across Legacy / A2 / previous main

### 5.1 Strong capabilities already proven and required to return

Legacy contains the broadest practical command behavior and hardware evidence:

- raw Fastboot command path with peer `INFO/TEXT/OKAY/FAIL` preserved;
- `getvar` / `getvar:all`;
- `download:` + `flash:`;
- `boot` after download;
- `fetch:` DATA IN;
- erase/format/set_active/reboot variants;
- `oem` / `flashing` commands;
- logical-partition commands and `update-super:`;
- GSI/snapshot command strings;
- partition inventory/slot helpers;
- Xiaomi unlock-token/vendor flow;
- hardware-proven Native USBFS DATA OUT around ~42 MB/s on POCO hardware.

A2 contributed the strongest transport correctness evidence:

- single transaction ownership;
- explicit DATA backend policy;
- Java `UsbRequest` hardware baseline (~128 MiB / 4.437 s on vayu);
- Native USBFS two-URB pipeline design;
- cancellation/drain/poison semantics;
- partition/probe tests and diagnostics.

Previous `main` contributed a cleaner Fastboot protocol model:

- one synchronous lane;
- explicit `INFO/TEXT/OKAY/FAIL/DATA` outcomes;
- exact command/DATA byte accounting;
- raw command lane with no command-name allowlist;
- mutation/Unknown semantics;
- bootloader vs fastbootd role probe;
- lock state as advisory/device authority, not a host permission;
- DATA OUT and DATA IN (`fetch`) engines;
- partition inventory/logical partition helpers;
- sparse-image parser/planner groundwork.

The previous `main` nevertheless regressed one proven capability: it did not retain Native USBFS. The rewrite already protects against repeating that loss.

### 5.2 Genuine Fastboot gaps found in the archives

#### A. `fastboot update` / `flashall` batch orchestration is absent

Legacy explicitly prints that `update` and `flashall` require desktop-fastboot batch logic and does not emulate them. A2 and previous `main` do not add a complete factory-image update/flashall engine.

A professional Android-host toolkit should eventually understand factory-image packages/tasks rather than forcing the operator to manually reconstruct every desktop fastboot batch step.

#### B. Sparse support was never complete end-to-end

Legacy and A2 contain no sparse image engine. Previous `main` added sparse format parsing/planning, but its own roadmap says the planner is **not wired into the production flash path**, and already-sparse input cannot be re-split.

Therefore large-image handling over `max-download-size` is still an incomplete capability even in the most advanced archive.

#### C. Presence of a terminal command string is not proof of full host orchestration

Some desktop fastboot commands are host workflows, not a single wire string. For example, AOSP `wipe-super` locates/reads `super_empty.img`, enters userspace Fastboot when necessary, downloads the image and performs the appropriate `update-super:` workflow. A Legacy parser entry that forwards `wipe-super` as a raw string must not be treated as proof that the desktop behavior was implemented.

The audit must distinguish:

- **wire command capability** — raw lane can send a valid command string and report the peer result;
- **host workflow capability** — NekoFlash performs required file parsing, DATA phase(s), mode transitions and postconditions.

#### D. Native USBFS is proven for DATA OUT, not a universal backend claim

The ~42 MB/s evidence is Fastboot DATA OUT. Future DATA IN/fetch performance should be measured independently rather than assuming the same backend/property applies automatically.

#### E. `stage` / `get_staged` and Fastboot network transport are absent as first-class host capabilities

Current AOSP desktop Fastboot still exposes `stage IN_FILE` / `get_staged OUT_FILE` (download/upload staging) and can select network devices through `tcp:` / `udp:` transport syntax. No supplied NekoFlash archive contains first-class implementations of those host capabilities.

These are lower-priority than the USB flashing/data correctness path, but the audit records them so the professional capability surface is not silently assumed to be complete. The future raw Fastboot lane plus DATA OUT/DATA IN primitives should make staging straightforward; a network Fastboot transport, if implemented, should share the same protocol engine rather than fork command semantics.

## 6. Recovery/Sideload audit

A2 contains correctness behavior worth retaining when this capability returns:

- transfer completion is not Recovery installation success;
- mutation boundary is explicit;
- cancel semantics change after the irreversible boundary;
- duplicate requested blocks do not inflate unique progress;
- served traffic and unique coverage are separate metrics;
- terminal/cancelled Sideload generation may require detach/new generation before reuse;
- persistent pre/post Recovery evidence correlation;
- local package SHA-256 proves local bytes, not Recovery success.

These are correctness invariants. They must not be weakened for convenience, but they also do not justify removing Sideload capability.

## 7. Capability completeness rule

A capability is not considered restored merely because:

- a command string appears in a parser;
- a constant or model exists;
- a unit-test-only planner exists;
- an old document calls it complete;
- a generic raw path could theoretically express part of the workflow.

For production completeness, the required wire/data/lifecycle path must be connected to the production engine, covered by executable tests, represented honestly in docs, and hardware-validated when hardware behavior matters.

This rule explains two important audit corrections: Sync `LIST` is not complete merely because `ID_LIST` exists, and sparse flashing is not complete merely because a planner exists.

## 8. Recommended implementation sequence after the auto-first hardware check

This sequence is about dependency/correctness, not permission tiers.

1. Harden USB lifecycle ownership: generation-aware permission callback identity, stale-callback rejection, detach/re-enumeration state tests.
2. Before broad ADB payload traffic, prove **ADB USB ZLP** behavior at endpoint max-packet boundaries and make it an executable transport invariant.
3. Restore the ADB stream core: negotiated packet reader/writer, one physical reader, stream router/mailboxes, generic raw service/open path.
4. Layer professional ADB clients on that same engine: shell v2/legacy, interactive shell, Sync including the missing LIST path, push/pull, normal/streaming install, reboot, forward/reverse, Sideload/Recovery.
5. Add modern ADB work that no archive supplied: Sync v2/compression, Wireless ADB pairing/TLS/mDNS, incremental install/atomic multi-package workflow; add Burst Mode only after the baseline stream path is correct and benchmarked.
6. Build the new Fastboot single-lane core with raw command surface first, exact outcomes and no host capability allowlist.
7. Restore DATA OUT with explicit Java/Native USBFS/sync backend selection; hardware-validate Native USBFS again before claiming its historical throughput.
8. Restore DATA IN/fetch, staging, mutations, logical/fastbootd workflows, boot/flash helpers and mode-transition/re-enumeration ownership; network Fastboot can later reuse the same protocol engine.
9. Complete sparse flashing (including already-sparse re-splitting) and factory-image `update`/`flashall` orchestration as first-class host workflows.
10. Rebuild Quick Flash and Mi Unlock only over those shared production engines; neither feature gets a private protocol stack or private capability policy.

## 9. Audit conclusion

The clean rewrite has not lost a currently implemented production capability because it intentionally has not reached broad ADB/Fastboot yet. The risk is **future selective restoration**: copying only what the last `main` had would lose Native USBFS; copying only Legacy would lose cleaner ownership and would still miss Sync LIST, sparse completion, factory-image update/flashall, Wireless ADB, ADB ZLP proof, modern install workflows, and Burst Mode.

The rewrite therefore uses the union of verified archive evidence plus current protocol reality, while preserving one product rule throughout: **NekoFlash protects protocol correctness and tells the truth about uncertainty; it does not invent host-side permissions for professional protocol capabilities.**

## 10. Reproducible archive/code pointers used by this audit

These paths are evidence pointers, not a request to transplant the old classes:

- previous `main` ADB Sync gap: `protocol/adb/.../AdbSyncSession.kt` exposes `open`, `close`, `stat`, `receive`, `send`; `AdbSyncProtocol.kt` defines `ID_LIST`/`ID_DENT`, proving constants existed without a production `LIST` operation;
- previous `main` stream foundation: `AdbConnection.kt`, `AdbStreamRouter.kt`, `AdbStreamMailbox.kt`, `AdbStreamDispatcher.kt`, `AdbServiceCall.kt`, plus their concurrent/inbound/service tests;
- ADB USB write gap: no archive contains explicit `ZLP`/zero-length-packet handling for host-to-device ADB; previous `main` `AdbPacketWriter.kt` chunks outbound bytes but has no endpoint-max-packet ZLP contract/test;
- modern install/performance gap: no archive contains `install-incremental`, `.idsig`/V4 Incremental Server handling, `install-multi-package`, or ADB Burst Mode/delayed-ACK negotiation;
- Legacy factory-batch gap: `app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt` explicitly reports that Fastboot `update`/`flashall` require desktop-fastboot batch logic and are not emulated;
- previous `main` sparse gap: `protocol/fastboot/SparseFormat.kt` and `FastbootSparsePlan.kt` exist, while `docs/09_IMPLEMENTATION_ROADMAP_RU.md` records that the planner was not wired into production flashing and already-sparse re-splitting was not implemented;
- A2 stale-permission protection: `usb/session/UsbPermissionCallbackIdentity.kt` plus its tests encode process token + monotonic generation into PendingIntent identity because extras do not define PendingIntent identity;
- Legacy/A2 Native USBFS: `app/src/main/cpp/native_usbfs.cpp` and `NativeUsbfsBackend.kt`; hardware evidence is recorded separately in `04_HARDWARE_EVIDENCE.md`;
- previous `main` professional-tool policy: `docs/01_PRODUCT_CHARTER_RU.md`, `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`, ADR `0002_LOCKED_BOOTLOADER_IS_ADVISORY_RU.md`, and the founding-decision log record the removal of the earlier product-level lock guard.

Current-protocol cross-check used Android Open Source Project ADB/Fastboot sources rather than assuming the archives were exhaustive: ADB developer services documentation, ADB Wi-Fi architecture/pairing/TLS/mDNS sources, current `file_sync_protocol.h`, ADB ZLP/Burst Mode/incremental-install developer notes, the current ADB user command surface, and current desktop Fastboot command/task implementation. This is why modern Wireless ADB, Sync v2/compression, endpoint-aware USB ZLP semantics, modern install workflows, Burst Mode, staging/network Fastboot, and desktop-style Fastboot host workflows are explicit audit items even though none of the supplied NekoFlash archives fully implemented them.

Desktop ADB-server administration commands that exist only to manage the desktop `adb` server process are **not** automatically NekoFlash feature requirements. NekoFlash is a direct Android host, so the audit tracks target/protocol capabilities and host workflows that map to that architecture rather than chasing CLI parity for its own sake.
