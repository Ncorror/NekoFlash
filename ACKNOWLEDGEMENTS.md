# Acknowledgements

NekoFlash was developed independently, but several open-source projects were
useful references for protocol behavior, Android-to-Android USB workflows, and
Xiaomi account/unlock sequencing. They are **not vendored source dependencies**
of this repository.

- **termux-adb** — nohajc / community, MIT License  
  https://github.com/nohajc/termux-adb  
  Reference for operating ADB/Fastboot from one Android device against another
  over USB without requiring root on the host device.

- **MiUnlockTool** — MiForge / offici5l, Apache License 2.0  
  https://github.com/MiForge/MiUnlockTool  
  Reference for Xiaomi unlock request sequencing and protocol behavior.

- **migate** — MiForge / offici5l, MIT License  
  https://github.com/MiForge/migate  
  Reference for Xiaomi account service-login and data-center-zone behavior.

- **MiTool** — MiForge / offici5l, Apache License 2.0  
  https://github.com/MiForge/MiTool  
  Historical reference for phone-side Android flashing/unlock workflows.

- **Android Open Source Project (AOSP)** — Apache License 2.0 and component-
  specific licenses  
  https://source.android.com/  
  Reference for ADB, Fastboot, Android USB behavior, and related public
  platform documentation.

The previous NOTICE entry for “Xiaomi Flash Master” was intentionally removed:
it was a vague UI-inspiration statement with no concrete bundled material or
license obligation and did not belong in a legal NOTICE file.
