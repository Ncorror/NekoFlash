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

## Next minimal step

Push the bootstrap changeset to `production-reset`, let authoritative Android CI run the bootstrap gates plus `test lint assembleDebug`, close only resulting build/lint defects without adding protocol code, then record that exact result in this file and `04_HARDWARE_EVIDENCE.md`.
