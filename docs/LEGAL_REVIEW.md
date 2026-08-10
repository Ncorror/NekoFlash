# Legal review

This document is a maintainer checklist, not legal advice.

## Project license

NekoFlash source code is distributed under the Apache License 2.0.

The root `LICENSE` file should contain the canonical Apache-2.0 license text. Project-specific attribution, trademark disclaimers, release notes and third-party notices should not be mixed into the canonical license text.

## Independence statement

NekoFlash is an independent project and is not affiliated with or endorsed by Google, Xiaomi, MiForge, Termux or any referenced open-source project.

NekoFlash does not require Termux, desktop adb/fastboot, MiFlash, MiForge, Google Play Services or vendor cloud services for the documented USB workflows.

Android platform APIs, build tools and declared open-source dependencies remain under their own licenses.

## Maintainer policy

- Do not claim ownership of third-party names, trademarks or projects.
- Do not claim that the project has no dependencies.
- Do not bundle third-party binaries, vendor firmware, proprietary blobs or copied source code unless their license allows redistribution and the notice is recorded.
- Keep raw private hardware logs, serials, tokens and personal identifiers outside Git.
- Keep public evidence sanitized.
- Keep release APKs in GitHub Releases assets, not in the repository tree.

## Third-party dependency review

Before a stable release, review Gradle files and any bundled components.

If a dependency is bundled into the APK, record it in `THIRD_PARTY_NOTICES.md` when required by its license.

## Current release

Current public release:

- tag: `v6.0.0-alpha10.1`
- base commit: `de1225b9cd5be110c72c6f130320b2d62d927cc0`
- release asset: `NekoFlash-v6.0.0-alpha10.1-release.apk`
