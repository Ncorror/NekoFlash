#!/usr/bin/env python3
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
vm = (root / 'app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt').read_text()
fb = (root / 'app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt').read_text()
main = (root / 'app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt').read_text()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
errors=[]
checks = [
    ('quickFail removed', 'quickFail' not in fb and 'quickFail' not in vm),
    ('generic _ab flashing removed', 'flashPartitionDetailed("${base}_ab"' not in vm and 'flashPartition("${base}_ab"' not in vm),
    ('explicit slot targets', 'FastbootSlotResolver.RequestedSlot' in vm and 'resolution.targets' in vm),
    ('typed flash result', 'data class FlashResult' in fb and 'sessionCorrupted' in fb),
    ('BROKEN state', 'SessionState.BROKEN' in fb and 'markSessionBroken' in fb),
    ('has-slot check', 'inspectSlotEvidence' in vm and 'hasSlot(base)' in fb),
    ('split jobs', 'connectionJob' in vm and 'operationJob' in vm),
    ('numeric slot count', 'slotCountRaw?.toIntOrNull()' in main),
    ('singleTop activity', 'android:launchMode="singleTop"' in manifest),
    ('UsbRequest DATA transport', 'UsbRequest()' in fb and 'requestWait(DATA_REQUEST_WATCHDOG_MS)' in fb),
    ('adaptive DATA block size', 'DATA_BLOCK_BYTES_MODERN' in fb and 'DATA_BLOCK_BYTES_LEGACY' in fb and 'Build.VERSION.SDK_INT' in fb),
    ('Fastboot clearHalt removed', 'clearEndpointHalt' not in fb),
    ('BROKEN closes USB transport', 'closeUsbTransport()' in fb and 'USB-соединение закрыто' in fb),
]
for label, ok in checks:
    print(('OK  ' if ok else 'FAIL') + label)
    if not ok: errors.append(label)
if errors:
    sys.exit(1)
print('A/B SAFETY CHECK: OK')
