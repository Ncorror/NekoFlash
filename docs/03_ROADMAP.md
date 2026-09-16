<!-- CURRENT_STATUS_SOURCE -->
# Roadmap and Current Status

This file is the only current roadmap/status source.

Active production branch: `production-reset`. `main` remains a reference line and is not part of the production rewrite flow.

## Phase 0 — DONE

Exact Legacy, A2 and current snapshots were audited before this rewrite. Their hashes are recorded in `04_HARDWARE_EVIDENCE.md` and `reference/SHA256SUMS`.

## Phase 1 — IN PROGRESS

Bootstrap acceptance criteria:

- [x] clean Gradle tree with only `:app`, `:core:model`, `:core:diagnostics`;
- [x] application identity and current toolchain baseline;
- [x] EN/RU resource parity gate;
- [x] diagnostics skeleton;
- [x] original byte-identical Legacy Welcome JPEG wired into the real Welcome screen;
- [x] semantic palette derived from Legacy colors;
- [x] launcher artwork preserved as reference only;
- [x] repository hygiene and documentation-consistency checks;
- [x] CI wired to `production-reset` and configured to run bootstrap gates plus `test lint assembleDebug`;
- [x] minimal founding-model tests;
- [x] authoritative Gradle `test lint assembleDebug` pass on a runner with Android SDK/dependency access;
- [x] visual verification of Welcome and Continue transition on Android hardware.
- [ ] final closeout changeset (documentation audit + Welcome scrim removal) passes CI and the end-of-iteration drift audit.

Phase 1 is not complete until the remaining closeout gate is closed. ADB/Fastboot implementation is forbidden before Phase 1 bootstrap is closed.

Bootstrap verification on 2026-09-16: repository hygiene, EN/RU parity and documentation consistency passed; the JVM core sources compiled with the local Kotlin compiler and a smoke check passed. The Gradle command did not reach project configuration because this environment could not resolve `services.gradle.org`, so no Gradle test/lint/assemble PASS is claimed.

GitHub Actions run `95185983331` for commit `99bfee22be8f821c404fa64654580881c2b1c902` reached the Android SDK setup but stopped before Gradle because the workflow requested the obsolete API-37 package identifier `platforms;android-37`. The runner's `sdkmanager` requires the minor-aware package `platforms;android-37.0`. This is a CI provisioning defect; no project build/test/lint result is inferred from that failed run.

GitHub Actions run `95189797254` for commit `61d9b3a692d0c85105ec3b0c8d3fe345d004b24d` successfully provisioned the Android SDK, passed the repository/localization/documentation gates, downloaded Gradle 9.5.0 and entered `test lint assembleDebug`. It then failed at `:app:extractDebugSupportedLocales` because automatic locale-config generation was enabled without the required `app/src/main/res/resources.properties` default-locale declaration. This is a Phase 1 bootstrap configuration defect; no complete Gradle PASS is claimed. The corrective changeset adds `unqualifiedResLocale=en` and makes the localization gate enforce that invariant.

GitHub Actions run `95193972365` for commit `99a34bdff03c8401e570a8ef780176508b8dd05c` closed the authoritative build gate: repository hygiene, EN/RU/default-locale and documentation-consistency checks passed; Gradle 9.5.0 completed `test lint assembleDebug` successfully; `:core:model:test`, `:core:diagnostics:test`, `:app:assembleDebug` and `:app:lint` all completed without failure. The run reported `BUILD SUCCESSFUL` with 58 actionable tasks (57 executed, 1 from cache).

The CI workflow publishes the generated debug APK as a short-lived artifact so Welcome/closeout visual checks are reproducible on Android hardware/emulator. It also publishes a separate verification artifact containing JVM test results/reports and Android lint reports. These artifacts do not add a product module or protocol implementation.

Android visual verification on 2026-09-17 found that the JPEG bytes were correct but the Compose presentation was not: `ContentScale.Fit` produced the wrong viewport treatment relative to the Legacy reference, the bottom text block was obscured by edge-to-edge navigation insets, and the bootstrap had no continuation path. This is a Phase 1 UI/bootstrap defect, not artwork drift. The corrective changeset keeps the JPEG byte-identical, restores full-viewport `Crop`, applies system-bar-safe overlays, adds a minimal Continue transition to an explicit Phase 1-ready shell, and leaves USB/ADB/Fastboot absent.

Follow-up Android verification on 2026-09-17 against commit `f759e25f7f8d465056678d52ddab8248d3e54d7f` passed the functional visual gate: the Welcome image filled the viewport correctly, title/subtitle and controls were visible outside system-bar occlusion, and Continue reached the Phase 1-ready shell. The owner identified the remaining dark bottom scrim as cosmetic polish rather than a navigation/layout blocker; the closeout changeset removes that scrim without changing the immutable JPEG.

The supplied verification artifact `NekoFlash-phase1-verification-f759e25f7f8d465056678d52ddab8248d3e54d7f.zip` contains all expected JVM test XML/HTML reports plus Android lint reports. Four JVM tests passed with zero failures/errors. Android lint reported 0 errors and 8 warnings: target/API baseline advisory, Gradle/AGP/Compose/Kotlin version advisories, Android 12+ data-extraction-rules advisory, and the intentionally unresolved launcher-icon warning while launcher artwork remains reference-only pending owner review. These warnings are recorded evidence, not silently treated as zero-warning output.

## Next minimal step

Apply the closeout changeset that removes only the cosmetic Welcome bottom scrim and reconciles the repository handoff/status/evidence documents. Run the full CI and end-of-iteration code/tests/docs/evidence audit. After the resulting APK receives a quick visual smoke check confirming that scrim removal did not regress layout or Continue, mark Phase 1 `DONE` in the single status source. Do not add ADB/Fastboot implementation before that closure.
