# Hardware and Evidence Ledger

No new hardware claim is made by the Phase 1 bootstrap changeset.

## Exact evidence snapshots

| Evidence source | SHA-256 |
|---|---|
| `NekoFlash-main-legacy.zip` | `74fe6f49fe466b15a2cd5b36e141c208823b4ef9e7a42ea96e0d5647b9c9d5b3` |
| `NekoFlash-A2-frozen.zip` | `386fc7a3bdf15ad2f361d552e5af55f7c489d7784490c8a4a84809e3fb73e012` |
| `NekoFlash-main.zip` | `07912264c092e64dea4076f1f1a2d370f4a4938153d3f31dfcc3efcbfca512f7` |

The exact current snapshot archive identifies commit `e89d61e405ac3c5059d0cf8bc420879cc508de9f`. Public GitHub was checked only for post-snapshot drift; it is not used as the rewrite evidence base.

The rewrite branch `production-reset` was created from that exact commit before applying the Phase 1 bootstrap; `main` remains a reference line.

## Brand evidence

- Welcome JPEG: `900x1600`, SHA-256 `d16195d6ab022a4ec8f9686a1d750a4dd83595140e68f718c992df8a0a60a8f7`.
- Launcher reference PNG: `1254x1254`, SHA-256 `fc098f5bea87aea9c2ad0dbddb74ea8277c94145403fb4d76032f36bb0ce1832`.
- Legacy accent: `#E9782B`; the full semantic source palette is represented in `app/.../ui/Theme.kt`.

## Open evidence

- Fastboot DATA IN/fetch remains hardware-open.
- Native USBFS for the rewrite does not exist yet and requires fresh validation when implemented.
- Successful destructive Fastboot mutation for the rewrite requires an owner-approved unlocked target.
- Rebuilt Mi Unlock requires fresh end-to-end validation.
- Phase 1 Welcome still requires Android visual verification.

## Phase 1 bootstrap verification — 2026-09-16

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

Public GitHub check on 2026-09-16 found the snapshot commit page for `e89d61e405ac3c5059d0cf8bc420879cc508de9f` and the public `main` page still showed the same Phase 5-era tree/status. No visible post-snapshot drift was found; the exact snapshots remain the evidence authority.
