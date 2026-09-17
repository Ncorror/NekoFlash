# Hardware and Evidence Ledger

Phase 1 is closed. The closeout adds no protocol or device-capability claim; it records build/test/lint evidence and owner-observed UI behavior only.

## Exact evidence snapshots

| Evidence source | SHA-256 |
|---|---|
| `NekoFlash-main-legacy.zip` | `74fe6f49fe466b15a2cd5b36e141c208823b4ef9e7a42ea96e0d5647b9c9d5b3` |
| `NekoFlash-A2-frozen.zip` | `386fc7a3bdf15ad2f361d552e5af55f7c489d7784490c8a4a84809e3fb73e012` |
| `NekoFlash-main.zip` | `07912264c092e64dea4076f1f1a2d370f4a4938153d3f31dfcc3efcbfca512f7` |

The exact current snapshot archive identifies commit `e89d61e405ac3c5059d0cf8bc420879cc508de9f`. Public GitHub was checked only for post-snapshot drift; it is not used as the rewrite evidence base.

The rewrite branch `production-reset` was created from that exact commit before applying the Phase 1 bootstrap; `main` remains a reference line.
The Phase 2 work recorded below started from the supplied GitHub source snapshot whose archive comment identifies `production-reset` commit `921c124a59626e2b179e94daa81c510f76a699fd`. This is a historical starting point; the current Git HEAD remains authoritative after the changeset is applied.

## Brand evidence

- Welcome JPEG: `900x1600`, SHA-256 `d16195d6ab022a4ec8f9686a1d750a4dd83595140e68f718c992df8a0a60a8f7`.
- Launcher reference PNG: `1254x1254`, SHA-256 `fc098f5bea87aea9c2ad0dbddb74ea8277c94145403fb4d76032f36bb0ce1832`.
- Legacy accent: `#E9782B`; the full semantic source palette is represented in `app/.../ui/Theme.kt`.

## Open evidence

- Fastboot DATA IN/fetch remains hardware-open.
- Native USBFS for the rewrite does not exist yet and requires fresh validation when implemented.
- Successful destructive Fastboot mutation for the rewrite requires an owner-approved unlocked target.
- Rebuilt Mi Unlock requires fresh end-to-end validation.
- Phase 1 has no open bootstrap build or visual gate. Remaining open evidence belongs to later transport/protocol/vendor phases.

## Phase 1 verification history

- Repository hygiene: PASS.
- EN/RU resource parity: PASS (4 translatable strings per locale).
- Documentation consistency: PASS (one current-status source, exactly three Phase 1 modules, brand hashes exact, 4 `@Test` methods present).
- Local Kotlin compilation of `:core:model` + `:core:diagnostics`: PASS.
- Local smoke check for first-class `Unknown`, target/generation separation and diagnostics retention: PASS.
- `./gradlew test lint assembleDebug`: NOT RUN TO PROJECT CONFIGURATION. The wrapper failed while fetching Gradle 9.5.0 because `services.gradle.org` could not be resolved in this environment. This is an environment limitation, not a build PASS or a product failure.
- New hardware runs: none.
- CI definition for the rewrite branch now targets `production-reset` and includes `./gradlew --no-daemon test lint assembleDebug`; no CI PASS is claimed until GitHub executes that exact changeset.
- GitHub Actions run `95185983331` on commit `99bfee22be8f821c404fa64654580881c2b1c902`: CI checkout and JDK setup passed; Android SDK license acceptance and `platform-tools` installation passed; provisioning then failed before Gradle with `Warning: Failed to find package 'platforms;android-37'`. The corrective CI baseline is `platforms;android-37.0` with AGP 9.3-compatible Build Tools `36.0.0`. This run is evidence of a CI provisioning defect only; it is not a product build failure and not a build PASS.
- GitHub Actions run `95189797254` on commit `61d9b3a692d0c85105ec3b0c8d3fe345d004b24d`: Android SDK provisioning passed; repository hygiene, EN/RU parity and documentation consistency passed; Gradle 9.5.0 downloaded and task execution began. The run failed at `:app:extractDebugSupportedLocales` with `No resources.properties file found` because `generateLocaleConfig = true` requires a default locale declaration. The corrective bootstrap invariant is `app/src/main/res/resources.properties` containing `unqualifiedResLocale=en`, enforced by `scripts/ci/check_localization.py`. No complete `test lint assembleDebug` PASS is claimed from this run.
- GitHub Actions run `95193972365` on commit `99a34bdff03c8401e570a8ef780176508b8dd05c`: repository hygiene PASS; localization PASS (`4` strings EN/RU, default locale `en`); documentation consistency PASS; Gradle 9.5.0 `test lint assembleDebug` PASS; `:core:model:test`, `:core:diagnostics:test`, `:app:assembleDebug` and `:app:lint` completed successfully; overall `BUILD SUCCESSFUL` with 58 actionable tasks (57 executed, 1 from cache). This is the authoritative Phase 1 build/test/lint PASS.
- CI artifact publication exposes `app-debug.apk` for Welcome verification and a separate verification archive with JVM test/lint reports; neither artifact constitutes hardware evidence by itself.
- Android visual check on 2026-09-17: the original Welcome JPEG was present, but the Compose screen used `ContentScale.Fit`, the lower overlay extended under the navigation bar, and no continuation control/path was available. Visual gate: FAIL. Root cause classification: Phase 1 presentation/bootstrap defect; JPEG asset integrity remains PASS.
- Follow-up Android visual check on 2026-09-17 for commit `f759e25f7f8d465056678d52ddab8248d3e54d7f`: Welcome full-viewport crop PASS; title/subtitle visibility PASS; navigation-bar-safe Continue control PASS; Continue transition to the explicit Phase 1-ready shell PASS. Functional visual gate: PASS. The remaining dark bottom scrim was classified by the owner as cosmetic polish and selected for removal; JPEG integrity remains unchanged.
- Verification artifact `NekoFlash-phase1-verification-f759e25f7f8d465056678d52ddab8248d3e54d7f.zip`: `OutcomeTest` 1/1 PASS, `TargetTest` 2/2 PASS, `InMemoryDiagnosticSinkTest` 1/1 PASS; total 4 tests, 0 failures, 0 errors, 0 skipped. Android lint: 0 errors, 8 warnings. Warning classes are baseline/version advisories (`OldTargetApi`, Gradle/AGP/Compose/Kotlin newer-version notices), `DataExtractionRules`, and `MissingApplicationIcon`. The launcher warning is consistent with the Phase 1 rule that launcher artwork stays reference-only until owner review; no warning is promoted to a capability restriction or protocol claim.
- Documentation audit on 2026-09-17 found one real drift: roadmap/evidence still described the earlier visual FAIL after the corrected APK had passed. The closeout changeset reconciles that history, adds an explicit repository-resident new-chat recovery contract, and removes the cosmetic Welcome scrim. No architecture/module/protocol boundary drift was found.
- Final Android owner smoke check on 2026-09-17 for commit `713c542f5524f19744b4e18ea7e97bf78f3aa75a`: Welcome full-viewport presentation PASS; app-owned bottom scrim removal PASS; title/subtitle/control visibility PASS; Continue transition to the Phase 1-ready shell PASS. The black strip containing Android navigation controls is system UI, not an app-owned overlay.
- Verification artifact `NekoFlash-phase1-verification-713c542f5524f19744b4e18ea7e97bf78f3aa75a.zip`: `OutcomeTest` 1/1 PASS, `TargetTest` 2/2 PASS, `InMemoryDiagnosticSinkTest` 1/1 PASS; total 4 tests, 0 failures, 0 errors, 0 skipped. Android lint: 0 errors, 8 warnings. The warning classes remain `OldTargetApi`, tool/dependency newer-version advisories, `DataExtractionRules`, and `MissingApplicationIcon`; they are evidence for later maintenance/launcher review, not protocol restrictions.
- Final Phase 1 code/tests/docs/evidence audit on 2026-09-17: repository hygiene PASS; localization/default-locale PASS; documentation consistency PASS; exactly `:app`, `:core:model`, `:core:diagnostics` present; `protocol/` and `usb/` absent; immutable Welcome and launcher-reference hashes exact; new-session recovery contract present; roadmap and evidence reconciled to Phase 1 `DONE`.

## Deferred hardware observations for the future ADB phase

- Multiple Poco/Xiaomi devices have shown intermittent ADB connection behavior: connection may succeed slowly, fail, or behave inconsistently across attempts.
- Poco F5 on HyperOS has one observed case with no successful ADB connection through the existing/reference implementation.
- Root cause is `UNKNOWN`. These observations must be reproduced later with descriptor, permission, interface-claim, bulk-transfer and ADB-handshake tracing. They do not justify a host-side Xiaomi/Poco/HyperOS capability ban. Device/peer protocol behavior remains authoritative.

Public GitHub check on 2026-09-16 found the snapshot commit page for `e89d61e405ac3c5059d0cf8bc420879cc508de9f` and the public `main` page still showed the same Phase 5-era tree/status. No visible post-snapshot drift was found; the exact snapshots remain the evidence authority.

## Phase 2 retained Fastboot DATA performance evidence

Native USBFS is now a protected rewrite capability because the reference trees contain real hardware value that must not be lost during architectural cleanup:

- Legacy POCO X3 Pro (`vayu`): real `recovery.img` flash, 128 MiB, Native USBFS, application-observed throughput approximately 42 MB/s, final `flash:recovery` success.
- Legacy POCO X7 Pro (`rodin`): real `vendor_boot_a` and `vendor_boot_b` flashes, 64 MiB each, Native USBFS, approximately 42 MB/s for each payload, both flash operations successful.
- A2 POCO X3 Pro (`vayu`) fallback baseline at commit `3863cbc407052b177d82889714491ef0de03de97`: Java `ASYNC_USB_REQUEST` transferred exactly 134217728 confirmed bytes in about 4.437 s; download terminal `OKAY`; `flash:recovery` terminal `OKAY`; session remained uncorrupted.

These are retained evidence, not a claim that the rewrite's future Native USBFS implementation is already verified. The A2 Native USBFS two-URB overlay still required exact CI and a fresh real-device native run at freeze time. The rewrite must therefore reimplement and validate the backend while preserving the documented correctness invariants.

## Phase 2 USB evidence slice

Code now exists for an Application-scoped evidence-only Android USB probe, so Activity recreation does not own or replace the permission receiver. It records physical device inventory, VID/PID, interface class/subclass/protocol, endpoint address/direction/type/max-packet-size, Android permission state/callback, `openDevice` outcome, and each `claimInterface(false)` result for interfaces containing both bulk IN and bulk OUT. A successful claim is immediately released and the connection is closed; no ADB/Fastboot bytes are transmitted.

Hardware result for this rewrite slice: **PASS for the base Android USB boundary on the owner-tested Poco/Xiaomi target**. On 2026-09-17, the Phase 2 screen showed `18D1:4EE7`, interface index/id `0`, class/subclass/protocol `255/66/1` (`FF/42/01`), bulk OUT `0x01` and bulk IN `0x81`, both with max packet size 512. After Android USB permission was granted, the UI reported `ClaimSucceeded(deviceName=/dev/bus/usb/001/002, interfaceIndex=0, interfaceId=0)`. No ADB/Fastboot bytes were sent by this probe.

This closes discovery/permission/descriptor/open/claim as the immediate failing layer for that run. The historical intermittent Xiaomi/Poco ADB behavior is **not** declared fixed: its root cause remains `UNKNOWN`, but investigation can now move to bulk I/O and ADB `CNXN`/`AUTH` behavior after a proven claim. The first scan's older `permissionGranted=false` evidence is expected pre-permission state and does not contradict the later `permission=true`/claim success.

Commit `17b46cb2bdb2c25ec53cb8c62197e2f35885f4d9` also has authoritative CI verification: 7/7 JVM tests PASS, 0 failures/errors/skips, and `:transport:usb-android` lint reports 0 issues.

### Phase 2 local pre-CI verification — 2026-09-17

- Repository hygiene: PASS.
- EN/RU localization parity/default locale: PASS (`14` translatable strings per locale, default `en`).
- Documentation consistency: PASS (one status source; four production modules; Phase 2 USB boundary present; Native USBFS retention contract present; immutable brand hashes exact; seven `@Test` methods detected).
- Pure USB evidence model smoke compilation: PASS; known `18D1:4EE7` descriptor shape produced bulk-pair interface index `0`.
- Kotlin syntax/type compilation of `:core:model`, `:core:diagnostics`, all `:transport:usb-android` production sources, their JVM tests, and `NekoFlashApplication` against minimal Android/JUnit API stubs: PASS. This checks the new source boundary without claiming a real Android SDK/AGP build.
- `./gradlew test lint assembleDebug --no-daemon --stacktrace`: DID NOT REACH PROJECT CONFIGURATION because this environment could not resolve `services.gradle.org` while fetching Gradle 9.5.0 (`UnknownHostException`). No Gradle/CI PASS is claimed from this local attempt.
- New Phase 2 hardware run: PENDING.

## Phase 2 evidence export follow-up

The hardware run exposed an evidence-collection usability gap: the first slice kept structured diagnostics only in process memory and displayed only the latest 30 events, so a full machine-readable run could not be archived directly from the app. The first follow-up added full UTF-8 text export through Android's document picker plus system text sharing. The export contains the complete current `InMemoryDiagnosticSink` snapshot, not only the 30 lines rendered on screen. Likely secret/raw protocol fields are defensively redacted by key name; producers must still avoid emitting secrets by design.

Commit `d340424f5879c457c0ee441a6561b848b3b6f326` has authoritative verification for that text-export/lint-fix state: 8/8 JVM tests PASS, USB lint reports no issues, and app lint reports 0 errors with 11 non-blocking warnings.

The owner also identified a regression in usability compared with A2: A2 hardware diagnostics were intentionally exported as a ZIP containing multiple evidence files (`export-manifest.txt`, `usb-events.txt`, and session snapshots), not one monolithic text blob. The rewrite therefore restores that **archive principle**, not the A2 implementation. The new deterministic bundle writes only explicitly supplied sections, never zips an arbitrary directory, zeroes ZIP entry timestamps, leaves caller-owned streams open, and uses the Android document picker / FileProvider instead of broad storage permission.

Current bundle sections are `summary.txt`, `usb-events.txt`, `usb-descriptors.txt`, `device-info.txt`, `app-build.txt`, and `session-info.txt`, with `export-manifest.txt` first. This format is deliberately extensible: later ADB, Fastboot and Native USBFS evidence can add separate named files while keeping the current USB evidence readable and grep-friendly. Real-device save/share validation of this new ZIP changeset is still pending and must not be claimed from CI alone.
