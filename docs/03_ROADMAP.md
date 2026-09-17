<!-- CURRENT_STATUS_SOURCE -->
# Roadmap and Current Status

This file is the only current roadmap/status source.

Active production branch: `production-reset`. `main` remains a reference line and is not part of the production rewrite flow.

## Phase 0 — DONE

Exact Legacy, A2 and current snapshots were audited before this rewrite. Their hashes are recorded in `04_HARDWARE_EVIDENCE.md` and `reference/SHA256SUMS`.

## Phase 1 — DONE

Phase 1 bootstrap closed on 2026-09-17 after the build/test/lint gates, repository-resident handoff audit, verification-artifact review and owner device smoke check all passed.

Bootstrap acceptance criteria:

- [x] clean Gradle tree with only `:app`, `:core:model`, `:core:diagnostics`;
- [x] application identity and current toolchain baseline;
- [x] EN/RU resource parity gate and explicit default locale;
- [x] diagnostics skeleton;
- [x] original byte-identical Legacy Welcome JPEG wired into the real Welcome screen;
- [x] semantic palette derived from Legacy colors;
- [x] launcher artwork preserved as reference only;
- [x] repository hygiene and documentation-consistency checks;
- [x] CI on `production-reset` running repository gates plus `test lint assembleDebug`;
- [x] minimal founding-model tests;
- [x] authoritative Gradle `test lint assembleDebug` pass on GitHub Actions;
- [x] reproducible APK and verification-report artifacts;
- [x] Welcome full-viewport presentation and system-bar-safe controls verified on Android hardware;
- [x] Continue transition verified on Android hardware;
- [x] final code/tests/docs/evidence drift audit and repository-resident new-session handoff contract.

Phase 1 closed with no USB, ADB, Fastboot, Quick Flash or Mi Unlock production implementation. That absence is intentional, not an unsupported capability restriction.

### Phase 1 verification history

Bootstrap verification on 2026-09-16: repository hygiene, EN/RU parity and documentation consistency passed; the JVM core sources compiled with the local Kotlin compiler and a smoke check passed. The Gradle command did not reach project configuration because that environment could not resolve `services.gradle.org`, so no Gradle test/lint/assemble PASS was claimed from that local attempt.

GitHub Actions run `95185983331` for commit `99bfee22be8f821c404fa64654580881c2b1c902` reached Android SDK setup but stopped before Gradle because the workflow requested the obsolete API-37 package identifier `platforms;android-37`. The corrective workflow uses `platforms;android-37.0`.

GitHub Actions run `95189797254` for commit `61d9b3a692d0c85105ec3b0c8d3fe345d004b24d` successfully provisioned the Android SDK and entered Gradle, then failed at `:app:extractDebugSupportedLocales` because automatic locale-config generation lacked `app/src/main/res/resources.properties`. The corrective invariant is `unqualifiedResLocale=en`, enforced by the localization gate.

GitHub Actions run `95193972365` for commit `99a34bdff03c8401e570a8ef780176508b8dd05c` closed the authoritative build gate: repository hygiene, localization/default-locale and documentation-consistency checks passed; Gradle 9.5.0 completed `test lint assembleDebug`; `:core:model:test`, `:core:diagnostics:test`, `:app:assembleDebug` and `:app:lint` completed without failure; the run reported `BUILD SUCCESSFUL` with 58 actionable tasks.

Android verification then found and corrected a Phase 1 presentation defect without changing the immutable JPEG: the Welcome viewport moved from `Fit` to full-viewport `Crop`, system-bar-safe controls were added, and Continue was wired to an explicit Phase 1-ready shell.

Follow-up verification against commit `f759e25f7f8d465056678d52ddab8248d3e54d7f` passed the functional Welcome/Continue gate. A remaining app-owned bottom scrim was classified as cosmetic and removed in the next closeout changeset.

Final owner device smoke verification against commit `713c542f5524f19744b4e18ea7e97bf78f3aa75a` passed: the Welcome image fills the viewport, the app-owned bottom scrim is gone, text and controls remain visible outside system-bar occlusion, and Continue opens the Phase 1-ready shell. The remaining black navigation area is Android system UI, not an app scrim.

The supplied verification artifact `NekoFlash-phase1-verification-713c542f5524f19744b4e18ea7e97bf78f3aa75a.zip` contains 4 passing JVM tests with 0 failures/errors/skips and Android lint with 0 errors and 8 recorded warnings. Warning classes remain version/API advisories, `DataExtractionRules`, and `MissingApplicationIcon` while launcher artwork stays reference-only pending owner review.

## Phase 2 — IN PROGRESS

Phase 2 started on 2026-09-17 after the pre-change code/tests/docs/evidence audit. The first production slice is intentionally evidence-only.

Implemented in the first slice:

- [x] executable `:transport:usb-android` ownership boundary with Application-scoped permission receiver lifetime;
- [x] Android USB enumeration with VID/PID and complete interface/endpoint evidence;
- [x] Android USB permission request/callback evidence;
- [x] reversible `openDevice -> claimInterface(false) -> release -> close` probe;
- [x] claim candidates require only a bulk IN+OUT pair; no Xiaomi/Poco/HyperOS host-side ban or protocol whitelist;
- [x] unit coverage for the known `18D1:4EE7`, `FF/42/01`, bulk `0x01/0x81` shape and vendor-neutral bulk-pair selection;
- [x] repository rules/ADR retain Native USBFS as a required future Fastboot DATA backend rather than an optional optimization.

Hardware/CI closure for the first USB boundary slice:

- [x] authoritative CI `test lint assembleDebug` PASS for commit `17b46cb2bdb2c25ec53cb8c62197e2f35885f4d9`; verification artifact reports 7/7 JVM tests PASS and USB lint with 0 issues;
- [x] owner hardware run on the connected Poco/Xiaomi target reached permission granted, `openDevice` success and `claimInterface(false)` success on interface 0;
- [x] observed descriptor shape remained `18D1:4EE7`, interface `FF/42/01`, bulk OUT `0x01` / bulk IN `0x81`, max packet size 512;
- [x] the base Android USB discovery/permission/open/claim boundary is therefore hardware-proven for this target; the intermittent Xiaomi/Poco ADB root cause remains `UNKNOWN` and is now narrowed to a layer after successful claim.

Evidence usability follow-up:

- [x] full in-memory diagnostics can be saved through Android's document picker as a UTF-8 `.txt` file;
- [x] full diagnostics can be shared through the system share sheet;
- [x] exported structured fields defensively redact likely secret/raw protocol material;
- [x] the on-screen `bulk-pair` label now identifies interface indexes explicitly instead of looking like a count;
- [x] CI verification for commit `d340424f5879c457c0ee441a6561b848b3b6f326`: 8/8 JVM tests PASS, USB lint 0 issues, app lint 0 errors / 11 warnings;
- [x] multi-file evidence ZIP code restores the useful A2 archive principle without copying the old architecture: explicit allowlisted sections, deterministic ZIP entry timestamps, manifest-first layout, caller-owned output streams, Android document-picker save and FileProvider-backed share;
- [x] authoritative CI verification for ZIP commit `cf7da5fa7d9357600b778bb76fa9f6f178cf8e98`: 14/14 JVM tests PASS, USB lint 0 issues, app lint 0 errors / 11 warnings;
- [x] owner-device save validation: four real ZIPs opened successfully and contained `export-manifest.txt` plus all six expected sections;
- [x] those two-device runs exposed a semantics gap before ADB work: one process-wide run ID and cumulative event sink mixed target histories, while export could serialize stale pre-permission UI descriptors;
- [x] per-target evidence session fix: manual **New evidence session** rotates `sessionId`, clears prior in-memory events without restarting the Application-scoped USB owner, records an owner-supplied target label, and performs a fresh `UsbManager` scan immediately before TXT/ZIP export;
- [x] authoritative CI verification for the per-target session fix on commit `23c82b1028ae0cda23a68e8bd31b0497dff0ef72`: 15/15 JVM tests PASS, USB lint 0 issues, app lint 0 errors / 11 warnings;
- [ ] fresh owner rerun on both phones remains useful evidence, but it is no longer a blocker for the next ADB slice: POCO X3 Pro is an enumerating ADB target, while the POCO F5 Android-mode `deviceCount=0` observation is tracked separately and Fastboot remains historically/currently observable.

Current ZIP payload is intentionally small and extensible: `summary.txt`, `usb-events.txt`, `adb-events.txt`, `usb-descriptors.txt`, `device-info.txt`, `app-build.txt`, and `session-info.txt`, preceded by `export-manifest.txt`. `adb-events.txt` is empty before the explicit ADB probe and then contains only safe handshake metadata/timing, never AUTH material or raw banners. `summary.txt` and `session-info.txt` now carry the per-target `sessionId` and owner-supplied `targetLabel`. Future ADB/Fastboot/USBFS evidence gets additional named sections rather than being flattened into one giant text file.

Fastboot DATA retention is now an explicit requirement for later Phase 2/3 protocol work: `ASYNC_USB_REQUEST` remains the A2 hardware-proven Java fallback; `NATIVE_USBFS` must return as a freshly validated high-throughput backend; a bounded `SYNC_BULK` path may exist as an explicitly preselected fallback/diagnostic mode. Native selection occurs before `download:` and no backend switch/retry is allowed after DATA negotiation starts.

### Minimal ADB CNXN/AUTH evidence slice

This changeset starts the next boundary without turning NekoFlash into a general ADB client:

- [x] add executable pure-JVM `:protocol:adb` framing/handshake ownership;
- [x] exactly one outbound `CNXN`; handle peer `AUTH TOKEN` with persistent app-private RSA signature and, only after a repeated token, the mincrypt public-key payload;
- [x] no `OPEN`, `WRTE`, shell, sync/push, reboot or other ADB service traffic;
- [x] Android adapter selects `FF/42/01` + bulk IN/OUT without a VID/PID whitelist, opens and claims with `force=false`, then releases/closes after the terminal handshake outcome;
- [x] no hidden reconnect, second `CNXN`, endpoint-halt clear or retry after a failed transfer;
- [x] evidence records safe packet semantics, payload byte counts, individual USB read/write requested/result bytes and timing, and terminal outcome; AUTH token/signature/public-key bytes and raw banners are never logged;
- [x] `adb-events.txt` becomes a first-class evidence ZIP section;
- [ ] authoritative CI for this changeset;
- [ ] owner hardware run on an enumerating target, starting with POCO X3 Pro.

The POCO F5 Android-mode enumeration quirk is deliberately not used to block this slice. Reference evidence already proves `marble` as a real Fastboot peer, including `18D1:D00D`, `getvar:product -> marble`, bootloader/fastbootd traffic, and a historical recovery case where leaving/re-entering Fastboot caused the re-enumeration that restored writes. That observation is retained for the later Fastboot/lifecycle phase rather than turned into a hidden recovery policy now.

## Next minimal step

Run authoritative CI for the minimal ADB handshake changeset, install the APK, create a clean `POCO X3 Pro` evidence session, then run `Scan -> Permission -> ADB CNXN/AUTH probe -> ZIP`. If the target asks for RSA authorization, approve it and let the probe terminate on `CNXN` or an explicit transfer/protocol failure. Review `adb-events.txt` before adding any ADB service command.
