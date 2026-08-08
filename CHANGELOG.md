## 6.0.0-alpha9 build 223 - Architecture cleanup, public workflow retained

- Bumped active source identifiers from `6.0.0-alpha8` / `222` to `6.0.0-alpha9` / `223`.
- Reduced duplication in MainActivity terminal usage errors, DeviceViewModel persisted string normalization, AdbProtocol inbound payload resets and FastbootProtocol DATA progress calculation without changing protocol behavior or UX.
- Kept the terminal inline-error hotfix from alpha7/alpha8: invalid Fastboot tokens and missing ADB/Fastboot connections stay in Console instead of opening the operation dialog.
- Switched current local release-signing instructions to `NEKOFLASH_RELEASE_*` variables while retaining legacy env names as compatibility fallback.
- Preserved the existing public `Ncorror/NekoFlash` workflow and did not import alternate private-repo defaults from the audited alpha9 archive.
- Retained historical recovery/export docs and scripts until hardware validation is complete.
- Exact Android CI and hardware smoke remain required before publishing a public alpha9 release.

## 6.0.0-alpha8 build 222 - Project audit cleanup

- Synchronized active project version/build identifiers to `6.0.0-alpha8` / `222`.
- Fixed terminal Fastboot size parsing so oversized numeric values fail explicitly instead of overflowing `Long` byte counts.
- Made operation brightness reduction idempotent and restored saved brightness state predictably.
- Aligned local APK scripts and release documentation with the fail-closed signed-release invariant: unsigned production release artifacts are not advertised or copied.
- Removed stale recovery-bundle publication commands with hardcoded old baseline SHA, PR branch and commit message.
- Removed the unreferenced terminal keyboard drawable and unused coroutine imports.
- Removed the final orphaned `home_workspace_path_copied` resource left after Home workspace-action cleanup.
- Hardened local release builds to verify the permanent NekoFlash certificate before packaging and re-verify the produced APK with `apksigner`.

## 6.0.0-alpha7 build 221 - Terminal error UX

- Missing ADB/Fastboot connection is reported inline in Console.
- Raw Fastboot terminal commands no longer open the blocking flash progress dialog.
- Non-ASCII host Fastboot operation tokens are rejected before USB execution.
- Valid raw/OEM Fastboot passthrough remains available on a connected device.

# История изменений NekoFlash

## V6.0.0-alpha7 — UI cleanup + hardware-test readiness

- Restored the real ADB/Fastboot command input in the persistent Console.
- Simplified Home into a device/transport dashboard; removed duplicate navigation/workspace actions.
- Reworked Recovery-first Quick Flash to readable two-column targets and removed hardcoded `vendor_kernel_boot`.
- Simplified Mi Unlock to account state + action, removing tutorial-only requirements/process cards.
- Refit the approved launcher artwork into the Android adaptive-icon safe area.
- Version: `6.0.0-alpha7` (`versionCode 220`).


## V6.0.0-alpha6 development — `6.0.0-alpha6-dev-nekoflash` (`218`)


### System audit batch 2 — Native USBFS RAII ownership — 2026-08-05

- Replaced manual payload/USB descriptor cleanup with move-only `UniqueFd` ownership and scoped JNI UTF-character release.
- Replaced raw URB and bulk-buffer allocations with `std::unique_ptr`, eliminating normal-path `delete[]`, `delete` and `free_slots()` cleanup branches.
- Added RAII transfer-registration cleanup for every early return and exceptional exit.
- Added a pending-URB ownership guard: unexpected C++ unwind or failed drain intentionally releases bounded native allocations without freeing kernel-referenced memory and poisons the backend until process restart.
- Preserved JNI signatures, the 14-field transfer result, block/depth bounds, cancellation semantics and diagnostic-only Native USBFS policy.
- Extended the USB connectivity regression guard to prohibit return of manual native cleanup and require the pending-URB fail-closed escape.
- Local host C++17 warnings-as-errors and Clang Static Analyzer pass. Exact-head Android/NDK/Debug/Release CI and separate hardware USBFS evidence remain required.

### System audit batch 1 — Native USBFS/JNI hardening — 2026-08-04

- Added a fail-closed C++ exception boundary for Native USBFS JNI calls so `std::bad_alloc` or an unexpected native exception returns structured diagnostics instead of crossing into ART and terminating the process.
- Corrected read-vs-submit failure staging and rejected negative or oversized `usbdevfs_urb.actual_length` before updating confirmed-byte counters.
- Serialized terminal Native USBFS progress publication with polling-thread completion and stopped catching fatal VM `Error` classes as ordinary transport failures.
- Added permanent USB connectivity guards for JNI exception containment, read-stage truth, length bounds and callback serialization.
- Cleaned Python dead imports/warning plumbing and made safety guards explicitly UTF-8 portable.
- Added a self-contained recovery bundle plus a bootstrap-compatible reviewed-source ZIP, and taught the updated protected-main publisher to import either a plain source ZIP or a nested recovery bundle.
- Local evidence: Clang C++17 warnings-as-errors PASS, Python/static/safety guards PASS and pure/JVM `27/27` PASS. Android Lint/NDK/Debug/Release and hardware USBFS remain exact-head CI/device requirements.

### Protected-main consolidation recovery — 2026-08-03

- GitHub Actions run `30850881076` confirmed exact consolidation head `e47fc664a5b6e8b3368c366b2573fb56fcad0edd` with Android Lint `0/0`, Debug/Release APK PASS, pure/JVM `27/27` and all safety guards PASS.
- The first consolidation attempt created recovery tag `archive/recovery-first-quick-flash-final-2026-08-03` and locally fast-forwarded `main`, but GitHub correctly rejected the direct remote push with protected-branch error `GH006: Changes must be made through a pull request`. Remote `main` was not changed and the source branch was not deleted.
- Reworked `scripts/termux-consolidate-to-main.sh` to create/reuse a Pull Request, verify its exact source SHA, wait for checks, merge through GitHub policy, verify the reviewed source as an ancestor of merged `main`, and only then delete the feature branch.
- Reworked `scripts/termux-publish.sh`: protected `main` is now the only permanent branch, while every publication uses a short-lived `termux/update-*` branch and Pull Request. Direct push, force push and protection bypass remain forbidden.
- Updated the canonical state, AI entrypoint, branch runbook, Termux workflow, build guide, README, changelog and static/documentation guards for protected-main operation.
- Runtime code, Android resources, ADB/Fastboot transport, staging, topology and mutation authorization are unchanged. A new exact-head CI is required before completing consolidation.

### Single-main branch consolidation package — 2026-08-03

- GitHub Actions run `30847362607` on exact head `a40262ed615c108229e2e7d9b18bb896b3f628e6` confirmed Android Lint `0 warnings / 0 errors`, Debug/Release APK PASS, pure/JVM `27/27` and all static/safety guards PASS.
- Recorded the target Git model as one live branch: `main`. The current remote contains only `main` and `feature/recovery-first-quick-flash`; the latter is a one-time consolidation source, not a permanent second line.
- Added `scripts/termux-consolidate-to-main.sh`: it requires a clean tree, exact-head green CI, local/remote SHA equality and fast-forward ancestry; it creates a recovery tag, advances `main` with `--ff-only`, verifies remote `main`, then deletes the feature branch. It never rebases or force-pushes.
- Updated `scripts/termux-publish.sh` for post-consolidation main-only publishing and kept `scripts/termux-ci.sh` defaulted to `main`.
- Added `docs/BRANCH_CONSOLIDATION.md` and updated the canonical state, AI entrypoint, Alpha6/release plans, Termux workflow, build guide, hardware summary and documentation guards.
- Replaced the obsolete Gate 5 PR task with `ALPHA6-MAINLINE-RELEASE-001`: final reviewed main diff, signed RC evidence, release notes/checksums and annotated release tag.
- Moved the next device session to 2026-08-04. Gate 0 remains read-only; real Sideload, Quick Flash mutation and final Mi Unlock stay blocked until Gate 0 PASS.
- Runtime code, USB/ADB/Fastboot transport, staging, topology and mutation authorization are unchanged.

### Clean CI baseline and rest/device-test handoff — 2026-08-03

- Exact-head report `045a4374480ac8c4ee7909a741bddc07fa2f0a60` confirmed Android Lint `0 warnings / 0 errors`, Debug/Release APK PASS, pure/JVM `27/27` and all static/safety guards PASS.
- Migrated the single live project state from the oversized root tracker to canonical `docs/PROJECT_STATE.md`; the root tracker remains only as a compatibility redirect for old ZIP import detection.
- Updated AI entrypoint, active Alpha6 plan, hardware summary, README, BUILDING, Termux context export and documentation guards.
- Recorded `PAUSED_FOR_REST` and the exact 2026-08-03 device plan: publish handoff → new exact-head CI → download that run's APK → Gate 0 only.
- No runtime/transport/mutation code changed in this handoff.

### Warning cleanup batch 11 — compiler and Gradle DSL cleanup

- GitHub Actions run `30765986561` on exact head `3cd1d6a8bccfc9d195526bf8c52ee4cd53b21805` completed successfully: static/safety guards, pure/JVM `27/27`, Android Lint, Debug APK and Release APK all passed.
- Android Lint reached the target baseline of `0 warnings, 0 errors` after the KTX and synchronous commit-result fixes.
- Removed the remaining Kotlin compiler warning in guided Fastboot staging by expressing the prepared-artifact reuse/creation as one nullable fallback and preserving the exact same staging lifetime and cleanup.
- Migrated the Groovy Android build script from deprecated space-assignment syntax to explicit property assignment and explicit method calls, and migrated `android.kotlinOptions` to the typed Kotlin `compilerOptions` DSL with JVM target 17.
- Updated CI and local APK scripts to parse the explicit `versionName`/`versionCode` assignments and to run Gradle with `--warning-mode all`. The next exact-head CI must prove whether any remaining Gradle deprecation originates outside project build scripts.
- ADB/Fastboot transport, staging thresholds, topology, mutation authorization and flash commands are unchanged.


### Warning cleanup batch 10 — synchronous commit-result hotfix

- GitHub Actions run `30765306763` on exact head `ec49be311ed1d1eefb56a02f4e11c59671142a06` failed during `compileDebugKotlin` before Lint or APK assembly. Static/safety guards and pure/JVM `27/27` passed.
- Root cause: AndroidX `SharedPreferences.edit(commit = true) { ... }` returns `Unit`; batch 9 incorrectly treated that value as the Boolean result of platform `Editor.commit()` in onboarding authorization and persisted ADB Sideload verification.
- Restored explicit synchronous `Editor.commit()` only at those two safety-sensitive boundaries and narrowly suppressed `UseKtx` there. The Boolean result is again checked before authorizing the onboarding session or trusting that the Sideload verification marker was durably written.
- Kept all other Core-1.17-compatible KTX migrations unchanged.
- Routed `fastboot-flash-preparation-policy` directly through the deterministic single-thread Kotlin backend after run `30765306763` showed a parallel compiler race followed by a successful retry.
- ADB/Fastboot transport, staging, topology, mutation authorization and flash commands are unchanged. Exact-head Android CI is required to resume Lint and Debug/Release assembly.


### Warning cleanup batch 9 — KTX migration and zero-warning candidate

- GitHub Actions run `30763918823` on exact head `4bf09270bfc4a62f2d554899963cc46fed57aa2e` completed successfully after the Core compatibility hotfix: static/safety guards, pure/JVM `27/27`, Android Lint, Debug APK and Release APK all passed.
- The modern Core `1.17.0` lint registry exposed `106 warnings, 0 errors`: `102 UseKtx`, one empty `super.onCleared()`, one compatible Core pin notice, one Gradle-wrapper version notice and one coroutines update notice. The separately supplied `ce2c091615752798a580f3bdab461525066ac3c0` report contains the same Lint/build result.
- Migrated all Core-1.17-compatible color parsing, URI parsing and SharedPreferences writes to AndroidX KTX. The single `Int.toDrawable` suggestion is unavailable before Core 1.19, so its existing `ColorDrawable` construction is retained under a narrow method-level suppression. Synchronous onboarding and Sideload verification writes retain `commit = true`; asynchronous writes retain apply semantics through the default KTX editor behavior.
- Removed the empty `AndroidViewModel.onCleared()` super call and updated `kotlinx-coroutines-android` to stable `1.11.0`.
- Added a precise root `lint.xml` for the two deliberately compatible pins only: Gradle `8.13` remains the documented AGP `8.13.2` pairing, and Core `1.17.0` remains required because Core `1.19.0` needs API 37 / AGP 9.1.
- Routed `usb-detection` directly through the deterministic single-thread Kotlin backend after the full CI archive showed one parallel compiler race followed by a successful retry.
- ADB/Fastboot transport, staging, topology, mutation authorization and flash commands are unchanged. Exact-head Android CI is required to confirm the zero-warning candidate.

### Warning cleanup batch 8 — AndroidX Core compatibility hotfix

- GitHub Actions run `30763246389` on exact head `63a12b2bb9e31c3eb8e67d05aff7219fdd6b83b9` reached the Android toolchain successfully but failed at `checkDebugAarMetadata` before Lint or APK assembly. Static/safety guards and pure/JVM `27/27` passed.
- Root cause: AndroidX Core/Core-KTX `1.19.0` requires `compileSdk 37` and Android Gradle Plugin `9.1.0`, while the deliberately selected stable toolchain is `compileSdk 36` with AGP `8.13.2`.
- Pinned Core-KTX to stable `1.17.0`, whose compile baseline is API 36 and whose Kotlin requirement is satisfied by Kotlin Gradle Plugin `2.3.21`. AGP `8.13.2`, Gradle `8.13`, Kotlin `2.3.21`, Activity, RecyclerView, Material and Lifecycle versions remain unchanged.
- Routed `quick-flash-topology` directly through the deterministic single-thread Kotlin backend after the uploaded CI showed one parallel compiler race followed by a successful retry.
- ADB/Fastboot protocol code, USB behavior, staging, topology decisions, mutation authorization and flash commands are unchanged. Exact-head Android CI remains mandatory; expected result is restored Lint/Debug/Release execution with the previous warning-cleanup target.


### Warning cleanup batch 7 — Android 36 compile toolchain with deferred target migration

- GitHub Actions run `30762305096` on exact head `4516148ff42e9da49cef1f68b345d324f6c4bf88` completed successfully: static/safety guards, pure/JVM `27/27`, Android lint, Debug APK and Release APK all passed. Confirmed Lint baseline after batch 6 is `10 warnings, 0 errors`: eight dependency notices, one Lifecycle custom-lint compatibility notice and `OldTargetApi`.
- Updated the compile-only toolchain to Android API 36, AGP `8.13.2`, Gradle `8.13` and Kotlin `2.3.21`. The Gradle distribution remains SHA-256 pinned; Java 17 and `minSdk 26` are unchanged.
- Initially updated Core `1.19.0`, Activity `1.13.0`, RecyclerView `1.4.0`, Material `1.14.0` and Lifecycle `2.11.0` as one coordinated compile-SDK migration. AppCompat `1.7.1`, coroutines and AndroidX Test remain unchanged.
- Kept `targetSdk 34` deliberately. Targeting Android 15/16 changes window/insets and other runtime behavior and therefore requires a separate device-validation gate. `OldTargetApi` is narrowly disabled in Lint with this migration debt documented here and in the tracker; it is not treated as completed runtime migration.
- Added `preflight-validator`, `mi-unlock-client` and `fastboot-partition-probe-planner` to the deterministic single-thread Kotlin test set after the two uploaded reports showed successful retries following parallel compiler races.
- ADB/Fastboot protocol code, USB behavior, staging thresholds, mutation gates and flash commands are unchanged. Exact-head Android CI is mandatory because the offline workspace cannot download the new Gradle/Android dependencies. This initial dependency set was rejected by AAR metadata in run `30763246389`; batch 8 corrects Core to `1.17.0`.


### Warning cleanup batch 6 — modular main layouts and deterministic Mi Account tests

- GitHub Actions run `30759721994` on exact head `67bbe9d83708f85a32bb026687e53a4e9c808c38` completed successfully: static/safety guards, pure/JVM `27/27`, Android lint, Debug APK and Release APK all passed. The separately uploaded `f26522231a2a7b3d8a67d1b845caa9dbdbcc3521` report confirms the same `12 warnings, 0 errors` baseline.
- Split the monolithic `activity_main.xml` into three included page resources: `page_home.xml`, `page_fastboot.xml` and `page_adb.xml`. All existing IDs and each extracted XML subtree are preserved exactly; no Activity/ViewModel binding or transport behavior changed.
- The main layout now contains 42 views at depth 5 instead of 149 views at depth 10. Each extracted page remains below Lint thresholds, targeting the remaining `TooManyViews` and `TooDeepLayout` findings without altering the protected Recovery-first card or Console.
- Routed `mi-account-security` directly through the deterministic single-thread Kotlin backend after run `30759721994` showed one parallel compiler crash followed by a successful retry. A forced fresh compile passes without retry noise.
- Remaining warnings are intentionally separated into the SDK/toolchain migration group: `OldTargetApi`, eight `GradleDependency` findings and the Lifecycle lint/toolchain compatibility warning. Expected next Lint baseline is approximately `10 warnings, 0 errors`; exact Android CI remains required.


### Warning cleanup batch 5 — CI head `4aff61c…` follow-up

- Standalone report for exact head `4aff61c116486a184e31d1e23b4fc63927937368` confirms `lintDebug`, debug/release APK assembly, four native ABIs, static/safety guards and pure/JVM `27/27`: `18 warnings, 0 errors`.
- Replaced direct `File.usableSpace` reads with `StorageManager#getAllocatableBytes`; `StatFs.availableBytes` is the fail-safe fallback when a storage UUID cannot be resolved.
- Replaced the nested weighted Console output with a bottom-anchored `RelativeLayout`, preserving the visible log area and fixed PRO command bar without nested-weight measurement overhead.
- Applied narrow warning fixes: battery-optimization package visibility suppression, AppCompat `1.7.1`, Lifecycle `2.8.7`, AndroidX Test JUnit `1.3.0`.
- Routed `fastboot-partition-inventory` directly through the deterministic single-thread Kotlin backend after the CI report showed one parallel compiler crash followed by a successful retry.
- ADB/Fastboot protocol code, partition resolution, staging policy thresholds, mutation gates and flash commands are unchanged. Expected next Lint baseline is approximately `11 warnings, 0 errors`; exact Android CI remains required.



### Warning cleanup batch 2 — CI 30575759023 follow-up

- GitHub Actions run `30575759023` passed on exact head `781116d139478a8b95e7cba684004dcf02d20a9e`: static/safety guards, pure/JVM `27/27`, Android lint, debug and release APK assembly.
- Confirmed lint baseline after batch 1: `103` warnings, `0` errors. The only Kotlin/Gradle compile warning was the legacy WebView callback override in `MiLoginActivity.kt`.
- Added the precise `OVERRIDE_DEPRECATION` suppression while retaining the legacy callback for pre-N WebView compatibility.
- Removed deprecated `-language-version 2.0` from protocol tests and routed the two compiler-race modules through a deterministic single-threaded backend, eliminating noisy fallback stack traces.
- Applied low-risk lint fixes: compat compound drawables, minSdk-26 dead branches/resources, RTL padding symmetry, no-backup extraction rules, package-visibility queries, console autofill opt-out, densityless artwork placement, direct drawable lookup, targeted remote-device-path suppressions and specific RecyclerView notifications.
- Transport, mutation gates and flash commands were not changed. Static/safety checks and the complete pure/JVM matrix remain `27/27` PASS locally; a new exact-head Android CI is required to establish the next lint count.


### Console Bottom Sheet — half-expanded device fix

- Exact-head GitHub Actions run `30490984287` passed on `759ebccff3577f72df49fd12a8e2b6e29d39dc36`: Wrapper validation, static/safety guards, JVM `27/27`, Android lint and debug/release assembly.
- Device smoke exposed a Material layout bug: the sheet remained measured at full-screen height while half-expanded, so the bottom-anchored RecyclerView content and PRO command bar were clipped below the visible window.
- `ConsoleDockController` now constrains `consoleBody` to the currently visible slice (`root height - sheet top - handle/header chrome`) during drag, state changes, root resize and IME transitions. Half-expanded mode therefore shows both log output and the bottom command bar.
- A compact `Логи`/`Logs` action was restored in the expanded header. It opens the existing logs/session menu and is not a Console/Terminal mode switch.
- The visible-slice calculation is covered by the existing `console-resize` pure/JVM module; a new exact-head CI and repeated device smoke remain required.


### Console Bottom Sheet — Steps 1–5

- Ручной dock-resize заменён persistent Material Bottom Sheet с состояниями collapsed/half-expanded/expanded и полноэкранным `expandedOffset=0`.
- Переключатели «Логи»/«Терминал» удалены; существующие `etCommand`/`btnSend` стали единственным активным command input, а legacy terminal routes и duplicate renderer отключены.
- `adjustResize` сохранён как единственный механизм изменения доступной высоты окна; `WindowInsetsCompat` отслеживает IME без ручного `translationY` и без двойного IME padding.
- При активной клавиатуре sheet удерживается expanded и временно не перетаскивается. Back сначала скрывает IME/снимает focus, затем переводит expanded → half-expanded → collapsed и только после этого возвращается к обычной навигации Activity.
- Активный вывод Console переведён с монолитного `ScrollView + TextView` на `RecyclerView`: отдельные строки, stable IDs, bounded snapshot до 3000 строк, 75 ms coalescing и incremental remove-first/append-tail для rolling buffer без полного HTML rebuild.
- Автопрокрутка выполняется только если пользователь уже находился у конца списка; при чтении старых строк позиция сохраняется даже при eviction начала rolling buffer.
- Неактивная fullscreen Terminal page окончательно удалена из XML и `TabController`; удалены её отдельный ввод, toolbar/filter/autoscroll controls, renderer, state/prefs и неиспользуемые строки. Bottom Sheet теперь является единственным terminal/console UI route.
- Следующий обязательный шаг — exact-head Android lint/debug/release assemble и device smoke полного сценария Bottom Sheet + IME + Back.


### CI Wrapper publication hotfix

- Исправлена публикационная потеря `gradle/wrapper/gradle-wrapper.jar`: source ZIP содержал JAR, но global Git ignore в Termux мог исключить его из commit при `git add -A`.
- `.gitignore` явно сохраняет Wrapper JAR; `termux-publish.sh` проверяет source/import, force-stage binary и подтверждает его наличие в staged и final commit tree.
- Workflow получил ранний tracked-wrapper preflight и использует Node 24-compatible `actions/checkout@v5`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` и `actions/upload-artifact@v6`. Отдельный `wrapper-validation` удалён, потому что `setup-gradle` выполняет Wrapper validation по умолчанию.
- `check_project.py` закрепляет workflow и publication guards. Новый exact-head Android CI обязателен; аппаратный roadmap Gate 0–5 не изменён.

### Next-chat execution roadmap locked

- Зафиксирован обязательный порядок продолжения: Gate 0 exact-head device smoke → Gate 1 real Sideload → Gate 2 calm UI Slice C → Gate 3 Diagnostic Center → Gate 4 Alpha6 stabilization → Gate 5 reviewed PR в `main`.
- Tracker содержит точные acceptance checklists для Console resize/persistence, Logs/privacy, ADB reconnect, read-only Fastboot и real Sideload lifecycle.
- Active alpha6 plan расширен Diagnostic Center и final stabilization gates; прямой push/force push в `main` остаётся запрещён.
- `docs/HARDWARE_VALIDATION.md` получил device runbook для CI `30405691356`; `docs/RELEASE_PROCESS.md` синхронизирован с тем же порядком.
- `scripts/export-chat-context.sh` экспортирует release process, а documentation guard защищает roadmap order от потери при следующем handoff.
- Source handoff содержит обязательный `gradle/wrapper/gradle-wrapper.jar`; publication hotfix гарантирует, что binary не будет потерян между ZIP import и Git commit.

### CI 30405691356 — source audit and Console resize confirmed

- GitHub Actions run `30405691356` passed on exact head `a03d6257cad7bf5f0ca585a6a9abdc1f8b2410f1`.
- Static/documentation/safety guards and pure/JVM matrix `27/27` passed.
- Android `lintDebug`, `assembleDebug`, `assembleRelease` and CMake builds for four ABIs passed.
- The orphan landscape-only dimension regression from run `30404393851` is closed.
- Lint contains `110` warnings and no errors; remaining compiler warnings are limited to three deprecated WebView API usages in `MiLoginActivity.kt`.
- Debug APK SHA-256: `5f412cb7b1555cfb9205bf8efbde27465185e649e06ccfed0dd1a229e03f89da`.
- Unsigned release APK SHA-256: `e739bdb19a255fb0e14ea700d1576250fc10297d10d30fffc4e93e04b80ecdf5`.

### CI 30404393851 lint follow-up

- GitHub Actions run `30404393851` on exact head `e206190b6a507fd93225cd8e0f3b8ab9514c15f5` confirmed all static/safety checks and pure/JVM matrix `27/27`.
- Android lint found one blocking `MissingDefaultResource`: two obsolete landscape-only dimensions remained after Welcome resource cleanup.
- Removed unused `welcome_panel_gap` and `welcome_secondary_button_height` from `values-land/dimens.xml`; application behavior is unchanged because neither resource had any reference.
- Added a host-side `check_qualified_dimen_defaults` guard so qualified-only dimensions fail before Gradle lint.
- Debug/release assembly was skipped by the failed run; a new exact-head Android CI remains required.

### Full source audit and safe cleanup

- Удалены 29 доказуемо неиспользуемых методов, осиротевший `ImageInspector.kt`, 9 resource files, 39 obsolete string keys, лишние imports/variables и dangling legacy helpers.
- `MainActivity` уменьшен с 5518 до 4950 строк, `DeviceViewModel` — с 3918 до 3723; transport, Quick Flash mutation gate, protected Welcome и Recovery-first card функционально не менялись.
- Console gesture/height/persistence logic выделена в `ConsoleDockController`; Android 13/legacy USB parcelable handling — в `IntentCompat`.
- `DeviceViewModel` переиспользует lifecycle-owned main `Handler`; Activity cleanup, Cursor handling, optional platform errors и Mi Login/WebView teardown стали явными и fail-safe.
- Исправлены затенённый `result`, неиспользуемая `highRiskAllowed`, stale callbacks и deprecated WebView cleanup.
- Добавлены source-audit regression boundaries, KDoc для ключевых entry points/transports/verifier и `docs/CODE_GUIDE.md`.
- Canonical static/safety guards и pure/JVM matrix `27/27` проходят локально. Новый exact-head Android lint/debug/release остаётся обязательным.

### Slice C micro-patch — resizable Console dock

- Верхний handle нижней Console теперь поддерживает вертикальный drag: вверх увеличивает журнал, вниз уменьшает.
- Высота ограничивается pure policy так, чтобы Console оставалась читаемой, а активная страница сохраняла минимальную видимую область.
- Выбранная высота сохраняется в dp между запусками; tap по handle сворачивает/разворачивает, double-tap возвращает стандартные 220 dp.
- Кнопки «Логи» и «Терминал» остаются в header и не входят в изменяемую по высоте область.
- Добавлен pure/JVM модуль `console-resize`; текущая локальная matrix — `27/27`. Новый exact-head Android CI и device gesture smoke остаются обязательными.

### Slice B.1 exact-head CI confirmation

- GitHub Actions run `30397037090` passed on exact code head `d90ff154820eb5878114b15dd2f685c0b34dd6ce`.
- Static/documentation/safety guards and pure/JVM matrix `26/26` passed, including `logs-menu` and `sanitized-log-share`.
- Android `lintDebug`, debug APK and release APK assembly passed; native CMake builds passed for four ABIs.
- Lint reported `136` warnings and no errors. Release APK is unsigned because CI signing secrets were not configured.
- Compile/CI gate for logs menu and device-ID sanitisation is closed; device logs/share UI smoke remains open.

### Slice B exact-head hardware evidence and log privacy hardening

- Reviewed the full 2026-07-28 compact log, protocol trace and session-summary from build `1c90383a73f8.30301994393`; raw logs remain outside Git.
- Guided Fastboot DATA is now device-confirmed on `vayu`: main 32 MiB UsbRequest twice, selected-image 128 MiB private-staged qualification and 32 MiB UsbRequest/SYNC comparison all reached final `OKAY`.
- Confirmed ADB RSA reuse, `shell_v2`, ordinary interactive-shell detach fail-closed, `adb reboot bootloader`, `adb reboot fastboot`, legacy `adb reboot systems`, bootloader Fastboot, fastbootd and ADB Sideload transitions.
- Confirmed no mutation command was sent, locked bootloader remained enforced, staging cleanup completed without failures and previous-generation DATA evidence did not authorize a new generation.
- Real Sideload ZIP transfer/cancel/recovery-result remains open; only peer detection, ZIP verification UI and return-to-system transition are confirmed.
- Hardened `ReportSanitizer` for terminal/property output: IMEI/MEID/IMSI/ICCID, Android ID, CPUID/UFS ID and fingerprint UID are replaced with `<redacted:device-id>` in bracketed `getprop`, text key/value and JSON forms.
- Expanded `sanitized-log-share` regression coverage with device-ID examples; module PASS.

### Slice B.1 — global logs menu

- Нижняя Console получила глобальную кнопку «Логи», доступную на Home, Quick Flash, Sideload, Unlock и Settings без перехода в терминал.
- Новое меню показывает краткую сводку текущей diagnostic session, current compact log, protocol trace, session-summary JSON и историю всех bounded log-файлов.
- Для каждого файла доступны bounded tail preview, копирование пути и отправка временной санитизированной копии. Активные файлы защищены от удаления; очистка старых журналов удаляет только завершённые sessions.
- Очистка экрана Console отделена от удаления файлов на диске. Добавлен pure/JVM модуль `logs-menu`; текущая matrix — `26/26`.

### Slice B — guided Fastboot DATA and Sideload

- Основной Fastboot DATA action теперь запускает один фиксированный recommended download-only тест: 32 MiB через UsbRequest, без выбора размера или transport matrix.
- Advanced DATA сокращён до двух действий: exact-size qualification выбранного `.img` через recommended transport и сравнение UsbRequest/SYNC на одном 32 MiB payload. Native USBFS matrix/content/staging diagnostics не показываются в ordinary UI и остаются diagnostic-only.
- Добавлены `FastbootDataUxPolicy` и pure regression coverage, фиксирующие bounded main action и два advanced action.
- Sideload card хранит persistent selected ZIP state: VERIFYING/VERIFIED/INVALID/STALE/CANCELLED/TRANSFER_PENDING/TRANSFER_FAILED/INSTALLED/INSTALL_FAILED.
- VERIFIED state связан с exact path, size и lastModified; изменённый/перемещённый ZIP становится `STALE`, а `runSideload` fail-closed блокирует transfer до повторной проверки.
- Результат проверки ZIP стал компактным; полный path, SHA-256, MD5, entries, scanned bytes и sidecar status перенесены в Details.
- Transfer/cancel и Recovery result обновляют ту же persistent card. Реальная transfer/cancel/result цепочка остаётся hardware `RETEST_REQUIRED`.
- Добавлен pure/JVM модуль `slice-b-ux`; локальная matrix теперь `26/26`.

### Slice A — transport truth

- Постоянный Git/Termux-контекст закреплён в `docs/AI_START_HERE.md`, `docs/TERMUX_WORKFLOW.md` и tracker: репозиторий `Ncorror/NekoFlash`, постоянные ветки `main` и `feature/recovery-first-quick-flash`, штатная публикация только существующим `scripts/termux-publish.sh` без третьей ветки, повторной авторизации и force push.
- Alpha6 handoff build `6.0.0-alpha6-dev-nekoflash+9823538147e0.30170789394` прошёл `lintDebug`, `assembleDebug`, `assembleRelease`, pure/JVM matrix и аппаратные проверки reboot/Fastboot DATA на `vayu`; оставлены отдельные negative regressions для ADB detach/malformed packet и повторный DATA test после восстановления.
- Slice A.1 переводит severity compact/session diagnostics на структурированные уровни `INFO`, `SUCCESS`, `WARNING`, `ERROR`, `SAFETY_BLOCK`. Текст сообщения больше не определяет уровень: `warnings=103`, `failed/cancelled` в cleanup-reason и пояснения «будет заблокирован» остаются `INFO`, если нет явного marker/declared level.
- Приоритет severity: объявленный уровень → текстовый marker → начальный icon marker → `INFO`; `DeviceViewModel.log(level, message)` добавлен без расширения transport/UI классов новой логикой.
- Safety-блоки отделены от реальных ошибок в `DiagnosticSessionTracker`; session summary обновлён до schema `diagnostic-session.v3` с `success`, `safetyBlocks` и `lastSafetyBlock`.
- Добавлен отдельный pure regression module `diagnostic-log-policy`; существующие русские строки `ОШИБКА:` переведены на явный `Level.ERROR`.
- После реального Fastboot USB detach compact log теперь явно сообщает, что generation закрывается fail-closed и перед следующей командой требуется новое подключение.
- README, BUILDING и documentation index/manifest синхронизированы с alpha6; Slice A.1 baseline имел pure/JVM `24/24`; documentation guard теперь проверяет текущую версию, активный план из tracker и фактическое число модулей из `tools/tests.manifest`.

- Создан `docs/ALPHA6_TRANSPORT_AND_UX_PLAN.md`: transport truth, guided DATA/Sideload и calm UI hierarchy разделены на три проверяемых slice.
- `adb reboot system` и legacy plural alias `systems` нормализуются в стандартный one-way service `reboot:`.
- После полного успешного A_OPEN для `reboot:*` ожидаемый timeout/USB disconnect больше не классифицируется как generic ADB failure. Это исключение не применяется к shell/sync/install/произвольным services.
- ADB single-reader dispatcher подавляет destructive transport-failure callback только для активного полностью отправленного reboot hand-off; обычный cable detach остаётся fail-closed.
- Corrupt/partial ADB header, invalid payload, checksum mismatch и queue overflow после `reboot:*` остаются явными protocol failures и больше не могут маскироваться как ожидаемый reboot disconnect.
- Native USBFS backend публикует active transfer state: confirmed/submitted/total bytes, monotonic timestamps и stage.
- Kotlin polling обновляет UI каждые 200 ms по **confirmed URB completions**, а submitted bytes остаются только diagnostic detail.
- Финальный Native DATA result формируется из той же metrics model, поэтому `100%` больше не должен сочетаться с `0 B`/`speed=N/A`.
- При частичном Native DATA failure итоговый progress сохраняет фактический confirmed-byte процент вместо сброса к `0%`; неизвестный total и counters около `Long.MAX_VALUE` обрабатываются без ложного completion/overflow.
- Добавлены pure regression tests для reboot completion policy/alias, protocol-failure separation и Native progress math.
- Fresh chat-context export теперь включает активный `ALPHA6_TRANSPORT_AND_UX_PLAN.md`; static continuity guard не позволит снова экспортировать только исторические alpha5/recovery документы.
- Local gate: canonical static/safety checks, pure/JVM `23/23` и host C++17 `-Wall -Wextra -Werror` syntax check — PASS; Android CI/device pending.
- Base exact head `0495345513d63b9c09b418bea06b5406d83abae7` подтверждён GitHub Actions run `30128358863` (SUCCESS); alpha6 source требует нового CI.

## V6.0.0-alpha5 development baseline — `6.0.0-alpha5-dev-nekoflash` (`217`)

### Post-smoke `vayu` A-only / DATA lifecycle patch (CI confirmed)

- Hardware log подтвердил `vayu` как legacy A-only: bootloader не поддерживает `current-slot`, `slot-count` и `slot-suffix`; Quick Flash не должен создавать `_a`/`_b` targets и обязан отдельно подтвердить unsuffixed concrete partition.
- Fastboot diagnostics теперь сразу описывают известную legacy A-only topology для `vayu`, не выдавая unsupported slot variables за обычную неопределённость. Это не заменяет inventory/point-query перед mutation.
- Исправлен lifecycle private staging: успешный native diagnostic-only прогон больше не удаляет уже qualified ASYNC/SYNC artifact и не сбрасывает его current-generation evidence.
- Standalone diagnostic-only staging удаляется с нейтральной причиной `diagnostic-only qualification completed`; failure/cancel остаются fail-closed и инвалидируют evidence.
- Добавлены pure regression checks для staging completion policy и нормализованного `vayu` product match.
- Patch опубликован как exact head `0495345513d63b9c09b418bea06b5406d83abae7`; GitHub Actions run `30128358863` завершился SUCCESS. Device regression для A-only/evidence lifecycle остаётся открытым.

### Exact-head CI и закрытие smoke-пунктов

- Exact head `b220d48b796d09b13974d8dc39d090efbc2afb55` прошёл documentation/static/safety, pure/JVM `23/23`, `lintDebug`, `assembleDebug` и `assembleRelease`.
- На device build `b220d48b796d.30042304245` подтверждены launcher, эталонный fullscreen Welcome, neutral Sideload pre-verify, Mi Account login в одном запуске и отсутствие raw account ID/token values в compact log.
- Полный Mi Unlock не заявлен как PASS: Fastboot product/device token, серверный unlock payload, `encryptData` и `oem unlock` ещё не проверялись.

### Удаление форумного диагностического отчёта (вне-scope cleanup)

- Удалён экспорт полного форумного диагностического ZIP: классы `ForumReportManager`, `DiagnosticReportFormatter`, `DiagnosticArchiveVerifier`.
- Из «Меню отчётов» убраны пункты «Создать форум-отчёт (zip)» и «Self-test форум-комплект»; терминальные команды вида `self-test forum`/`forum`/`zip`/`bundle`/`support` больше не создают ZIP (падают в обычный self-test).
- Из `DiagnosticReadiness` убраны проверка `ZIP_PROBE` и связанный `createDiagnosticZipProbe`.
- Сохранены: общий компактный/trace лог, `DiagnosticSessionTracker`, санитизация (`ReportSanitizer`), лёгкий локальный self-test отчёт (`selftest.v3`, txt/json), папка reports и лог-действия. Safety-инварианты Quick Flash и хостовый скоупинг Mi Unlock не затронуты.
- Обновлены guard'ы `check_project.py` и `check-diagnostic-logging.py`, удалены неиспользуемые строковые ресурсы, обновлены архитектура, help-текст и log-actions dialog.
- Fullscreen Welcome с прозрачным нижним overlay-gate принят maintainer на устройстве и закреплён как защищённый эталон.

### Recovery-first Quick Flash

- Реализованы pure plan/candidate models, fail-closed topology builder, Recovery-first UI и одноразовый mutation gate.
- Filename используется только как hint; target разрешается по concrete inventory/slot evidence.
- Primary и Expert targets разделены, Expert Mode выключен по умолчанию, legacy multi-flash queue скрыт.
- Один confirmation соответствует одному concrete partition и одному вызову `flashPartitionDetailed`; mutation retry отсутствует.
- Session, URI, размер, SHA-256 и topology evidence повторно проверяются перед execution.
- Baseline Slice E подтверждён GitHub Actions run `29855091700`: static/safety, pure/policy, `lintDebug`, `assembleDebug`, `assembleRelease` — success.

### Android smoke polish

- Welcome permission chips стали действиями, отдельная battery button удалена, risk row кликабельна. Неудачный adaptive ScrollView-вариант удалён: artwork теперь заполняет весь viewport без вертикальной прокрутки, а прозрачный outline-first gate закреплён поверх нижней области.
- Sideload card упрощена; Import/Verify выровнены, жёлтая памятка удалена, pre-verify note больше не показывает ложную зелёную галочку.
- Fastboot DATA card сведена к одному основному self-test; специализированные проверки перенесены в дополнительный dialog, no-device taps журналируются.
- Recovery-first card зафиксирована как защищённый эталон и в smoke-polish не меняется.

### Mi Account / Mi Unlock

- Интерактивный login принимает только точный официальный completion callback `https://unlock.update.miui.com/sts`.
- Background clientSign exchange допускает только exact `/sts` на фиксированных региональных unlock hosts.
- Account `passToken` не отправляется на unlock hosts; сохраняются только ожидаемые service-cookie names.
- Исправлена гонка первого входа: поздний `onPageFinished` больше не может заменить успешную авторизацию stale blocked-host banner.
- Добавлены pure policy/race regression tests. Device smoke build `5f119c469430.29913150722` подтвердил fresh login без перезапуска и stale banner; новый Android CI для текущего exact head остаётся обязательным. Raw account ID больше не пишется в compact log.

### Workflow и документация

- `termux-publish.sh` выполняет только безопасный import/commit/push feature-ветки без локальной сборки и CI.
- `termux-ci.sh` по умолчанию создаёт лёгкий evidence archive без APK; APK скачиваются отдельно по `--with-apk`.
- Python cache исключён из source tree и checksum inventory.
- Каноническая документация пересобрана: tracker сокращён до живого статуса, hardware evidence отделён от планов, stale/противоречивые утверждения удалены.

## V6.0.0-alpha4 — `6.0.0-alpha4-nekoflash` (`216`)

- Восстановлены private transient-модели `PendingUnlockVerification` и `PendingSideloadVerification` после scope cleanup.
- Исправлены Android compilation errors; static guard защищает обе модели и их поля.
- Maintainer-confirmed green GitHub Actions run: `29832274659`, commit `90871fb`.

## V6.0.0-alpha3 — `6.0.0-alpha3-nekoflash` (`215`)

- Проведён полный V6 source audit после удаления Mi Flash.
- Удалены скрытая Service page, `DeviceProfileManager`, `PartitionInventoryHistory`, raw hardware logs и исторические V5 документы из активного дерева.
- Diagnostic report обновлён до schema `forum-report.v6`.
- `TOPBAR-001`, `HOMEINFO-001`, `HOMEACTIONS-001` сохранены.

## V6.0.0-alpha2 — `6.0.0-alpha2-nekoflash` (`214`)

- Закреплена карточка устройства и рабочей папки.
- Добавлены действия «Открыть папку» и «Копировать путь».
- На Home добавлены Terminal, Quick Flash, ADB Sideload и Mi Unlock.

## V6.0.0-alpha1 — `6.0.0-alpha1-nekoflash` (`213`)

- Полный Mi Flash удалён из активной ветки.
- ADB/Fastboot transports, Terminal, Quick Flash, Sideload, Mi Unlock и sanitised logs сохранены.
- Предыдущее состояние полного Mi Flash оставлено только во внешнем архиве владельца (Google Drive); в Git V6 оно не зеркалируется.
