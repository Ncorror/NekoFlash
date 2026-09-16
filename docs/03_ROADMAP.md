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
- [ ] authoritative Gradle `test lint assembleDebug` pass on a runner with Android SDK/dependency access;
- [ ] visual verification of Welcome on Android hardware/emulator.

Phase 1 is not complete until the unchecked gates are closed. ADB/Fastboot implementation is forbidden before Phase 1 bootstrap is closed.

Bootstrap verification on 2026-09-16: repository hygiene, EN/RU parity and documentation consistency passed; the JVM core sources compiled with the local Kotlin compiler and a smoke check passed. The Gradle command did not reach project configuration because this environment could not resolve `services.gradle.org`, so no Gradle test/lint/assemble PASS is claimed.

GitHub Actions run `95185983331` for commit `99bfee22be8f821c404fa64654580881c2b1c902` reached the Android SDK setup but stopped before Gradle because the workflow requested the obsolete API-37 package identifier `platforms;android-37`. The runner's `sdkmanager` requires the minor-aware package `platforms;android-37.0`. This is a CI provisioning defect; no project build/test/lint result is inferred from that failed run.

GitHub Actions run `95189797254` for commit `61d9b3a692d0c85105ec3b0c8d3fe345d004b24d` successfully provisioned the Android SDK, passed the repository/localization/documentation gates, downloaded Gradle 9.5.0 and entered `test lint assembleDebug`. It then failed at `:app:extractDebugSupportedLocales` because automatic locale-config generation was enabled without the required `app/src/main/res/resources.properties` default-locale declaration. This is a Phase 1 bootstrap configuration defect; no complete Gradle PASS is claimed. The corrective changeset adds `unqualifiedResLocale=en` and makes the localization gate enforce that invariant.

## Next minimal step

Rerun the authoritative `production-reset` workflow after the default-locale fix, then close only any concrete build/lint/test defect that remains. Do not add ADB/Fastboot implementation until the full Phase 1 bootstrap gates are green and recorded in this file and `04_HARDWARE_EVIDENCE.md`.
