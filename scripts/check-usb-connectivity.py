#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = (root / 'app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt').read_text()
vm = (root / 'app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt').read_text()
inspector = (root / 'app/src/main/java/ru/forum/adbfastboottool/UsbDeviceInspector.kt').read_text()
adb = (root / 'app/src/main/java/ru/forum/adbfastboottool/AdbProtocol.kt').read_text()
fastboot = (root / 'app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
filter_xml = (root / 'app/src/main/res/xml/device_filter.xml').read_text()

checks = {
    'no runtime ATTACHED reconnect loop': (
        'addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)' not in main
        and 'runtime-broadcast' not in main
        and 'resume-scan' not in main
    ),
    'manifest singleTop attach delivery': 'android:launchMode="singleTop"' in manifest,
    'one-shot startup enumeration': (
        'scheduleStartupUsbDiscovery()' in main
        and 'findSafeCandidates(usbManager.deviceList.values)' in main
    ),
    'startup scan cancelled by real connect path': (
        'startupUsbDiscoveryRunnable' in main
        and 'cancelStartupUsbDiscovery()' in main
        and main.count('cancelStartupUsbDiscovery()') >= 3
    ),
    'mode-switch scan requires changed signature': (
        'selectModeSwitchCandidate' in main
        and 'it.logicalSignature != previousLogicalSignature' in inspector
    ),
    'mode-switch stays on same vendor': (
        'previousVendorId' in inspector
        and 'it.device.vendorId == previousVendorId' in inspector
        and 'currentUsbVendorId()' in main
    ),
    'genuine mode switch gets one fresh retry': (
        'allowModeSwitchUsbRetry(candidate)' in main
        and 'fun allowModeSwitchUsbRetry' in vm
    ),
    'failed Fastboot USB generation is quarantined': (
        'quarantinedUsbTargets' in vm
        and 'quarantinedUsbTargets.contains(retryKey)' in vm
        and 'candidate.mode == UsbDeviceInspector.Mode.FASTBOOT' in vm
        and 'candidate.stableKey' in vm
    ),
    'manual search cannot bypass Fastboot quarantine': (
        'Повторное открытие этой Fastboot USB-сессии запрещено' in main
        and 'Эта Fastboot USB-сессия уже потеряла синхронизацию' in vm
    ),
    'real USB detach releases quarantined generation': (
        'fun noteUsbDetached(device: UsbDevice)' in vm
        and 'quarantinedUsbTargets.removeIf' in vm
        and 'viewModel.noteUsbDetached(device)' in main
    ),
    'getvar retries read only within one command': (
        'GETVAR_READ_SLICE_MS' in fastboot
        and 'GETVAR_READ_RETRY_DELAY_MS' in fastboot
        and 'emptyReads += 1' in fastboot
        and 'GETVAR_MAX_FAILED_READS = 3' in fastboot
        and 'emptyReads >= GETVAR_MAX_FAILED_READS' in fastboot
        and 'readPacket(readTimeoutMs)' in fastboot
        and 'enqueueReadFailure()' in (root / 'tools/fastboot-core-test/android/hardware/usb/UsbStubs.kt').read_text()
        and 'newCommands == listOf("getvar:product")' in (root / 'tools/fastboot-core-test/ru/forum/adbfastboottool/FastbootCoreTest.kt').read_text()
    ),
    'log file setup is idempotent and does not replay history': (
        'logFileConfigured' in vm
        and 'configuredWorkspacePath == workspaceKey' in vm
        and 'val seedInitialLines = !logFileConfigured' in vm
        and 'if (seedInitialLines)' in vm
    ),
    'Fastboot connection is qualified before publish': (
        'proto.qualifyConnection()' in vm
        and 'knownProduct = qualifiedProduct' in vm
        and 'fun qualifyConnection(' in fastboot
    ),
    'single source USB inspector': (
        'selectPrimaryCandidate' in main
        and 'preferredInterfaceIndex = candidate.interfaceIndex' in vm
    ),
    'canonical ADB detection': (
        'iface.interfaceProtocol == ADB_PROTOCOL' in inspector
        and 'Mode.ADB' in inspector
    ),
    'canonical and compat Fastboot detection': (
        'isCanonicalFastboot' in inspector
        and 'isAndroidFastbootCompatible' in inspector
    ),
    'generic Fastboot manual only': (
        'GENERIC_FASTBOOT(2, false' in inspector
        and 'connectManualCandidate' in main
    ),
    'ADB uses one CNXN handshake': (
        'ADB_CONNECT_ATTEMPTS' not in adb
        and 'repeat(ADB_CONNECT_ATTEMPTS)' not in adb
        and 'closeConnectionOnly()' not in adb
    ),
    'ADB honors selected interface': 'preferredInterfaceIndex' in adb,
    'Fastboot honors selected interface': 'preferredInterfaceIndex' in fastboot,
    'universal vendor-class attach filter': (
        '<usb-device class="255" />' in filter_xml
        and 'vendor-id=' not in filter_xml
    ),
    'permission rebinds current UsbDevice object': (
        'rebindCandidate(device, it)' in main
        and 'fun rebindCandidate(device: UsbDevice, previous: Candidate)' in inspector
    ),
    'consumed attach falls back to startup enumeration': (
        'EXTRA_USB_INTENT_CONSUMED' in main
        and 'return false' in main[main.index('EXTRA_USB_INTENT_CONSUMED'):main.index('EXTRA_USB_INTENT_CONSUMED') + 700]
    ),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('OK  ' if ok else 'FAIL') + name)
if failed:
    raise SystemExit('USB CONNECTIVITY CHECK FAILED: ' + ', '.join(failed))
print('USB CONNECTIVITY CHECK: OK')
