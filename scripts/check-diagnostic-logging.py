#!/usr/bin/env python3
"""Permanent guard for compact/trace logging separation and bounded reports."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"FAILED: {message}", file=sys.stderr)
        raise SystemExit(1)

vm = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt").read_text(encoding="utf-8")
store = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DiagnosticLogStore.kt").read_text(encoding="utf-8")
policy = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DiagnosticLogPolicy.kt").read_text(encoding="utf-8")
fastboot = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt").read_text(encoding="utf-8")
report = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/ForumReportManager.kt").read_text(encoding="utf-8")
main = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt").read_text(encoding="utf-8")

log_file_only = vm.split("fun logFileOnly(message: String)", 1)[-1].split("private fun appendCompactMessageLocked", 1)[0]
require("appendRawToTraceFile" in log_file_only, "logFileOnly must write to trace stream")
require("appendRawToLogFile" not in log_file_only, "logFileOnly must not pollute compact log")
require("while (lines.size > 600)" in vm, "visible log must remain bounded")
require("DiagnosticSessionTracker" in vm and "persistSessionSummary" in vm, "session summary tracker must be wired")
require("duplicateWindowMs" in vm and "suppressedDuplicateCount" in vm, "duplicate coalescing must remain enabled")

for token in ("compactSegmentBytes", "traceSegmentBytes", "maxSegmentsPerStream", "pruneDirectory"):
    require(token in store, f"DiagnosticLogStore missing {token}")
require("64L * 1024L * 1024L" in store, "total old-log budget must remain bounded")

require("progressLogStepPercent(debugLogging)" in fastboot, "Fastboot DATA progress must use compact cadence policy")
require("uiProgressStepPercent" in fastboot, "UI progress must remain independent from file log cadence")
require("fun progressLogStepPercent" in policy, "progress cadence policy missing")

for token in (
    "logs/compact-",
    "logs/trace-",
    "session-summary.json",
    "partition-inventory.json",
    "forum-report.v6",
):
    require(token in report, f"diagnostic ZIP missing {token}")
for token in ("currentLogFiles()", "currentTraceLogFiles()", "currentDiagnosticSessionSummary()"):
    require(token in main, f"MainActivity report wiring missing {token}")


# Diagnostic readiness and ADB single-reader invariants.
mode = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DiagnosticModePolicy.kt").read_text(encoding="utf-8")
readiness = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DiagnosticReadiness.kt").read_text(encoding="utf-8")
archive = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/DiagnosticArchiveVerifier.kt").read_text(encoding="utf-8")
adb = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/AdbProtocol.kt").read_text(encoding="utf-8")
dispatcher = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/AdbPacketDispatcher.kt").read_text(encoding="utf-8")
adb_read_only = (ROOT / "app/src/main/java/ru/forum/adbfastboottool/AdbReadOnlyPolicy.kt").read_text(encoding="utf-8")
for token in ("EXTENDED_READ_ONLY", "USB_STRESS_READ_ONLY", "mutationLockRequired"):
    require(token in mode, f"diagnostic mode policy missing {token}")
for token in ("READ_ONLY", "ZIP_PROBE", "BULK_ENDPOINTS", "TRANSPORT_CLEAN"):
    require(token in readiness, f"readiness check missing {token}")
for token in ("DiagnosticArchiveVerifier.verify", "usb-session.json"):
    require(token in report, f"diagnostic report readiness missing {token}")
for token in ("readOnlyMutationLock", "BrokenReasonCode", "runMutationPreflight"):
    require(token in fastboot, f"Fastboot diagnostic guard missing {token}")
require("AdbPacketDispatcher" in adb and "startPacketDispatcher()" in adb, "ADB single-reader dispatcher not wired")
require("synchronized(adbWriteLock)" in adb, "ADB writes must remain serialized")
require("readOnlyMutationLock" in adb and "AdbReadOnlyPolicy" in adb, "ADB READ-ONLY policy not wired")
require("isShellReadOnly" in adb_read_only and "isServiceReadOnly" in adb_read_only, "ADB READ-ONLY allow-list missing")
require("LinkedBlockingQueue" in dispatcher and "QUEUE_OVERFLOW" in dispatcher, "ADB dispatcher must be bounded and fail closed")
for token in ("runDiagnosticReadinessFromUi", "toggleDiagnosticReadOnlyFromUi", "copyDiagnosticSummary"):
    require(token in main, f"MainActivity readiness UI missing {token}")

print("Diagnostic logging guard: OK")
