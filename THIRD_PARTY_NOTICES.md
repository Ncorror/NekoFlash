# Third-party notices

This file documents third-party material that is bundled into, linked into, or
used to build the NekoFlash source/application distribution. It is intentionally
separate from `NOTICE`: general acknowledgements and non-bundled reference
projects do not belong in the Apache NOTICE file.

## Android robot artwork

NekoFlash contains `app/src/main/res/drawable/ic_nf_recovery_green.xml`, a
modified depiction of the Android robot. The required attribution is:

> The Android robot is reproduced or modified from work created and shared by
> Google and used according to terms described in the Creative Commons 3.0
> Attribution License.

License: Creative Commons Attribution 3.0

Android is a trademark of Google LLC.

## Direct runtime/build dependencies

The versions below are the direct dependencies declared by the current alpha9
project. Transitive dependencies are resolved by Gradle and may add their own
notices.

- AndroidX Core KTX `1.17.0` — Apache License 2.0
- AndroidX AppCompat `1.7.1` — Apache License 2.0
- AndroidX Activity KTX `1.13.0` — Apache License 2.0
- AndroidX RecyclerView `1.4.0` — Apache License 2.0
- AndroidX Lifecycle ViewModel KTX `2.11.0` — Apache License 2.0
- AndroidX Lifecycle ViewModel SavedState `2.11.0` — Apache License 2.0
- AndroidX Lifecycle LiveData KTX `2.11.0` — Apache License 2.0
- AndroidX Lifecycle Runtime KTX `2.11.0` — Apache License 2.0
- AndroidX Palette KTX `1.0.0` — Apache License 2.0
- Material Components for Android `1.14.0` — Apache License 2.0
- Kotlin standard runtime used by Kotlin plugin `2.3.21` — Apache License 2.0
- kotlinx-coroutines-android `1.11.0` — Apache License 2.0
- Gradle Wrapper files included in the source tree — Apache License 2.0

Upstream license references:

- AndroidX: https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt
- Material Components: https://github.com/material-components/material-components-android/blob/master/LICENSE
- Kotlin: https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- kotlinx.coroutines: https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt

## Android Open Source Project

NekoFlash implements ADB/Fastboot protocol behavior using Android platform APIs
and public AOSP documentation/protocol behavior. The Android Open Source Project
is primarily licensed under Apache License 2.0; individual AOSP components may
carry their own notices. No AOSP source tree is vendored into this repository.

For projects that informed implementation choices but are not vendored
dependencies, see `ACKNOWLEDGEMENTS.md`.
