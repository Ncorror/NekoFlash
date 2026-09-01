# NekoFlash

[![Android CI](https://github.com/Ncorror/NekoFlash/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Ncorror/NekoFlash/actions/workflows/android-ci.yml)

NekoFlash is a clean-room professional Android host toolkit for ADB, Fastboot,
Recovery and vendor workflows.

## Project status

- Phase 0: complete; A2 final inbound framing gate passed and A2 is frozen.
- Phase 1: complete; clean application/bootstrap foundation verified by CI.
- Phase 2: complete; USB + Target/Session ownership verified on hardware.
- Phase 3: next; ADB transport and protocol engines.
- Legacy and A2 are reference/evidence only.

## Product rule

NekoFlash does not use novice/expert permission profiles, hidden capability tiers
or product whitelists as an authorization model. The UI may warn and confirm,
but valid professional protocol capabilities are not removed because they are
powerful.

There are no product-level hard guards. A confirmed `LOCKED` bootloader produces
the strongest warning and a typed `yes` confirmation for image writes, exactly as
`erase userdata` and `format` do; the command is still sent and the real device
response is shown. Refusal belongs to the device.

Correctness remains strict: transport framing, ownership, session generation,
mutation boundaries, byte accounting, peer responses and `UNKNOWN` outcomes are
core invariants.

## Languages

The product UI is maintained in **English and Russian**. English is the default
resource locale and Russian is a first-class translation with feature parity.
User-facing Compose text must come from Android string resources rather than
hardcoded Kotlin literals. Protocol commands, peer responses, partition names
and machine-readable diagnostic codes remain exact and are not rewritten by
localization.

On Android 13+ the supported locales are exposed through the platform per-app
language settings via AGP-generated locale configuration.

## Build model

The authoritative build/test environment is GitHub Actions. Termux is used for
repository work, editing, commits and pushes; it is not required to be a full
Android build machine.

Current baseline:

- applicationId / namespace: `io.github.ncorror.nekoflash`
- minSdk: 26
- targetSdk: 36
- compileSdk: 37
- Android Gradle Plugin: 9.3.2
- Gradle: 9.5.0
- Kotlin: 2.4.10
- Compose BOM: 2026.08.00
- Material 3 Adaptive: 1.3.0

## Initial modules

- `:app` — adaptive product shell.
- `:core:model` — target/session identity contracts.
- `:core:diagnostics` — structured diagnostics foundation.
- `:core:operation` — operation identity, mutation boundary and outcome contracts.

Modules are added only when they represent a real ownership boundary. USB, ADB,
Fastboot, Recovery and vendor modules are introduced in their roadmap phases,
not as empty placeholders.

## Documentation

Active canonical documentation lives in [`docs/`](docs/). Historical founding
snapshots live in [`reference/archives/`](reference/archives/) and are immutable.

## Local data policy

Automatic Android cloud backup and device-to-device migration are disabled for
NekoFlash-managed app data. The application may eventually hold ADB host keys,
vendor authentication state, diagnostics and operation metadata, so these are
not silently copied to another device. User-selected artifacts remain
user-owned through SAF rather than becoming app backup payloads.
