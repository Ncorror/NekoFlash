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

## Phase 2 — NEXT

Phase 2 has not started in this closeout changeset. Its first responsibility is transport evidence and ownership, not ADB/Fastboot feature breadth.

Starting constraints:

- perform the pre-change code/tests/docs/evidence audit first;
- begin with the smallest concrete Android USB discovery/permission/open/claim/descriptor/endpoint boundary needed for real evidence;
- instrument diagnostics so descriptor, permission, claim and transfer outcomes can be distinguished instead of collapsed into "ADB failed";
- reproduce the known Poco/Xiaomi observations, including Poco F5 + HyperOS, and keep root cause `UNKNOWN` until the failing layer is proven;
- do not add Xiaomi/Poco/HyperOS host-side bans;
- do not create placeholder modules without an executable ownership boundary;
- do not implement ADB framing/handshake or Fastboot transactions before the transport boundary and evidence path are technically credible.

## Next minimal step

Run the Phase 2 pre-audit, then implement only the smallest evidence-first Android USB discovery/permission/descriptor tracing boundary needed to distinguish device visibility, permission, interface/endpoints and open/claim outcomes on real hardware. Exercise it first against the known Poco/Xiaomi cases and record the result in `04_HARDWARE_EVIDENCE.md` in the same changeset. No ADB/Fastboot protocol engine belongs in that first Phase 2 step.
