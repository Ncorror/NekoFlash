# Third-party notices and acknowledgements

This file documents third-party material bundled into, linked into, or used to build NekoFlash, plus non-bundled projects that informed implementation choices.

## Android robot artwork

NekoFlash contains `app/src/main/res/drawable/ic_nf_recovery_green.xml`, a modified depiction of the Android robot.

> The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License.

License: Creative Commons Attribution 3.0. Android is a trademark of Google LLC.

## Direct runtime/build dependencies

Direct dependencies declared by the current project include:

- AndroidX Core KTX `1.17.0` — Apache License 2.0;
- AndroidX AppCompat `1.7.1` — Apache License 2.0;
- AndroidX Activity KTX `1.13.0` — Apache License 2.0;
- AndroidX RecyclerView `1.4.0` — Apache License 2.0;
- AndroidX Lifecycle ViewModel KTX / SavedState / LiveData KTX / Runtime KTX `2.11.0` — Apache License 2.0;
- AndroidX Palette KTX `1.0.0` — Apache License 2.0;
- Material Components for Android `1.14.0` — Apache License 2.0;
- Kotlin runtime used by Kotlin plugin `2.3.21` — Apache License 2.0;
- kotlinx-coroutines-android `1.11.0` — Apache License 2.0;
- Gradle Wrapper files included in the source tree — Apache License 2.0.

Transitive dependencies are resolved by Gradle and may carry additional notices.

Upstream license references:

- AndroidX: https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt
- Material Components: https://github.com/material-components/material-components-android/blob/master/LICENSE
- Kotlin: https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- kotlinx.coroutines: https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt

## Android Open Source Project

NekoFlash implements ADB/Fastboot protocol behavior using Android platform APIs and public AOSP documentation/protocol behavior. No AOSP source tree is vendored into this repository.

## Non-bundled reference projects

The following projects were implementation references and are **not vendored source dependencies** of this repository:

- **termux-adb** — nohajc / community, MIT License — https://github.com/nohajc/termux-adb
- **MiUnlockTool** — MiForge / offici5l, Apache License 2.0 — https://github.com/MiForge/MiUnlockTool
- **migate** — MiForge / offici5l, MIT License — https://github.com/MiForge/migate
- **MiTool** — MiForge / offici5l, Apache License 2.0 — https://github.com/MiForge/MiTool
- **Android Open Source Project** — Apache License 2.0 and component-specific licenses — https://source.android.com/

These references informed Android-to-Android USB workflows, ADB/Fastboot behavior and Xiaomi account/unlock sequencing; they do not imply affiliation or endorsement.
