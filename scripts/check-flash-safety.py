#!/usr/bin/env python3
"""Flash-safety regression guard.

Restores the coverage dropped when the per-version guards were consolidated
(historical regression OPEN-CI-703) and covers the filename→partition hint:

  Fix A  — real DATA fails closed on single-URB (single-URB is diagnostic-only).
  Fix B  — a native USBDEVFS_RESET recovery exists and is invoked at the wedge site.
  Hint   — PartitionNameResolver suggests a target but never selects/flashes on its own.
  Inventory — getvar:all remains read-only/manual, preserves vayu as legacy A-only,
              and never participates in flash authorization.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(rel: str) -> str:
    p = ROOT / rel
    if not p.exists():
        errors.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding="utf-8")


def require(cond: bool, label: str) -> None:
    print(f"OK  {label}" if cond else f"-- {label}")
    if not cond:
        errors.append(label)


protocol = read("app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt")
backend = read("app/src/main/java/ru/forum/adbfastboottool/NativeUsbfsBackend.kt")
native = read("app/src/main/cpp/native_usbfs.cpp")
resolver = read("app/src/main/java/ru/forum/adbfastboottool/PartitionNameResolver.kt")
inventory = read("app/src/main/java/ru/forum/adbfastboottool/FastbootPartitionInventory.kt")
probe_planner = read("app/src/main/java/ru/forum/adbfastboottool/FastbootPartitionProbePlanner.kt")
viewmodel = read("app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt")
main_activity = read("app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt")
manifest = read("tools/tests.manifest")
quick_flash_plan = read("app/src/main/java/ru/forum/adbfastboottool/QuickFlashPlan.kt")
quick_flash_topology = read("app/src/main/java/ru/forum/adbfastboottool/QuickFlashTopologyCandidateBuilder.kt")
quick_flash_ui = read("app/src/main/java/ru/forum/adbfastboottool/QuickFlashUiPolicy.kt")
main_layout = read("app/src/main/res/layout/activity_main.xml")

# --- Fix A: single-URB is diagnostic-only for real DATA ---
require("isSingleUrbDiagnostic" in protocol, "Fix A: isSingleUrbDiagnostic present")
require(
    "!diagnostic && dataTransportMode.isNativeUsbfsDiagnostic()" in protocol,
    "Fix A: real DATA fails closed on every diagnostic-only Native USBFS profile",
)
require("diagnostic: Boolean = false" in protocol, "Fix A: transferDownloadPayload diagnostic flag")

# --- Fix B: USBDEVFS_RESET recovery ---
require("USBDEVFS_RESET" in native and "nativeUsbfsResetDevice" in native, "Fix B: native USBDEVFS_RESET present")
require("fun resetUsbDevice(" in backend, "Fix B: resetUsbDevice wrapper present")
require(
    protocol.count("attemptEndpointResetRecovery(") >= 2,
    "Fix B: reset recovery declared and invoked at the wedge site",
)

# --- Partition hint (must not weaken the gate) ---
require("object PartitionNameResolver" in resolver, "Hint: PartitionNameResolver present")
require("fun resolve(" in resolver, "Hint: resolve() classification API present")
require(
    all(b in resolver for b in ("orangefox", "twrp", "pbrp", "shrp", "redwolf")),
    "Hint: custom-recovery brands recognised (OrangeFox/TWRP/PBRP/SHRP/RedWolf)",
)
require("RECOVERY_IMAGE" in resolver and "vendor_boot" in resolver, "Hint: recovery images are topology-aware (candidates incl. vendor_boot)")
require("PartitionNameResolver.resolve(payloadFile.name)" in protocol, "Hint: wired into the diagnostic path")
# the gate itself must remain: the diagnostic path still declares no partition is selected
require("Раздел не выбран" in protocol, "Gate intact: 'partition not selected' still enforced")
# the hint must not leak into the real flash authorization path
flash_body = protocol.split("fun flashPartitionDetailed", 1)[-1].split("\n    fun ", 1)[0]
require(
    "PartitionNameResolver" not in flash_body,
    "Hint absent from the flash authorization path (flashPartitionDetailed)",
)
# resolver is compiled by the fastboot-core test module
require("PartitionNameResolver.kt" in manifest, "Hint: resolver added to fastboot-core test sources")

# --- Recovery-first Quick Flash Slice A ---
require("enum class QuickFlashTarget" in quick_flash_plan, "Quick Flash: explicit target catalog present")
require(
    "Visibility.PRIMARY" in quick_flash_plan and "Visibility.EXPERT" in quick_flash_plan,
    "Quick Flash: primary and expert targets are separated",
)
require(
    "TARGET_AMBIGUOUS" in quick_flash_plan and "CANDIDATE_NOT_CONFIRMED" in quick_flash_plan,
    "Quick Flash: missing/ambiguous evidence fails closed",
)
require(
    "MANUAL_CONFIRMATION_REQUIRED" in quick_flash_plan and "MANUAL_TARGET_FORBIDDEN" in quick_flash_plan,
    "Quick Flash: manual target requires repeat entry and blocks excluded scope",
)
require(
    'listOf("flash", partitionName)' in quick_flash_plan and "QuickFlashSlot" in quick_flash_plan,
    "Quick Flash: one plan resolves to one concrete flash command",
)
require("quick-flash-plan" in manifest, "Quick Flash: Slice A pure regression module registered")

# --- Recovery-first Quick Flash Slice B ---
require(
    "object QuickFlashTopologyCandidateBuilder" in quick_flash_topology,
    "Quick Flash: read-only topology candidate builder present",
)
require(
    "FastbootPartitionInventory.from" in quick_flash_topology and
    "FastbootSlotResolver.resolve" in quick_flash_topology and
    "FastbootPartitionProbePlanner.plan" in quick_flash_topology,
    "Quick Flash: Slice B combines inventory, slot resolution and bounded probes",
)
require(
    "PartitionNameResolver.resolve" in quick_flash_topology and
    "suggestedTargets" in quick_flash_topology,
    "Quick Flash: filename classification remains a hint",
)
require(
    "SLOT_TOPOLOGY_UNKNOWN" in quick_flash_topology and
    "SESSION_BROKEN" in quick_flash_topology and
    "IMAGE_ARCHIVE_REQUIRES_SIDELOAD" in quick_flash_topology,
    "Quick Flash: unknown topology, broken session and archives fail closed",
)
require(
    "inventory.partition" in quick_flash_topology and
    "resolution.targets != listOf(entry.name)" in quick_flash_topology,
    "Quick Flash: candidates require exact concrete inventory and slot match",
)
require("quick-flash-topology" in manifest, "Quick Flash: Slice B pure regression module registered")

# --- Recovery-first Quick Flash Slice C ---
require(
    "QuickFlashTarget.RECOVERY" in quick_flash_ui and
    quick_flash_ui.index("QuickFlashTarget.RECOVERY") < quick_flash_ui.index("QuickFlashTarget.BOOT"),
    "Quick Flash: Recovery is the first primary UI target",
)
require(
    "legacyQueueVisible: Boolean = false" in quick_flash_ui and
    'android:id="@+id/legacyFlashQueueCard"' in main_layout and
    'android:visibility="gone"' in main_layout.split('android:id="@+id/legacyFlashQueueCard"', 1)[1][:300],
    "Quick Flash: legacy multi-flash queue is hidden",
)
require(
    'android:id="@+id/containerQuickFlashExpertTargets"' in main_layout and
    'android:visibility="gone"' in main_layout.split('android:id="@+id/containerQuickFlashExpertTargets"', 1)[1][:500],
    "Quick Flash: expert targets are hidden by default",
)
require(
    "QuickFlashTopologyCandidateBuilder.buildFromInventory" in main_activity and
    "selectedPartitionName = candidate.partitionName" in main_activity,
    "Quick Flash: UI uses concrete Slice B candidates",
)
quick_flash_ui_body = main_activity.split("private fun startQuickFlashTargetFlow", 1)[-1].split("private fun showFlashConfirmation", 1)[0]
require(
    "FastbootSlotResolver.RequestedSlot.BOTH" not in quick_flash_ui_body and
    "viewModel.runFlash(plan.partitionName, file)" in quick_flash_ui_body,
    "Quick Flash: new UI confirms one concrete partition, never BOTH",
)
require(
    "currentTransportSessionId() != inventorySessionId" in quick_flash_ui_body and
    "expectedSessionId = inventorySessionId" in quick_flash_ui_body and
    "currentTransportSessionId() != expectedSessionId" in quick_flash_ui_body,
    "Quick Flash: inventory candidates remain bound to one transport session",
)
require("quick-flash-ui" in manifest, "Quick Flash: Slice C pure regression module registered")

# --- Read-only partition inventory ---
require("object FastbootPartitionInventory" in inventory, "Inventory: model present")
require(
    'legacyAOnlyProducts = setOf("vayu")' in inventory and "LEGACY_A_ONLY" in inventory,
    "Inventory: POCO X3 Pro / vayu locked to legacy A-only topology",
)
require(
    "Missing slot variables alone are not sufficient" in inventory,
    "Inventory: missing slot evidence remains UNKNOWN",
)
require(
    "if (!partition.hasConcreteEvidence) return@forEach" in inventory,
    "Inventory: has-slot family metadata cannot create a concrete partition",
)
require(
    "object FastbootPartitionProbePlanner" in probe_planner and "maxQueries" in probe_planner,
    "Inventory: bounded point-query planner present",
)
require(
    "SlotTopology.LEGACY_A_ONLY -> addMissingFields(base, null)" in probe_planner,
    "Inventory: legacy A-only fallback never synthesizes slot suffixes",
)
refresh_body = viewmodel.split("fun refreshFastbootDiagnostics()", 1)[-1].split("// ─── ВЫПОЛНЕНИЕ КОМАНД", 1)[0]
connect_body = viewmodel.split("private suspend fun connectCandidateLocked", 1)[-1].split("private suspend fun awaitNativeUsbfsIdle", 1)[0]
collector_body = protocol.split("fun collectPartitionInventory", 1)[-1].split("\n    fun hasSlot", 1)[0]
require("proto.collectPartitionInventory(diagnostics)" in refresh_body, "Inventory: collection runs only from explicit refresh")
require("getVarAll()" in collector_body, "Inventory: collector uses getvar:all as primary source")
require("collectPartitionInventory(" not in connect_body and "getVarAll(" not in connect_body, "Inventory: initial USB/Fastboot connect does not collect inventory")
require(
    "FastbootPartitionInventory" not in flash_body and "collectPartitionInventory(" not in flash_body and "getVarAll(" not in flash_body,
    "Inventory absent from flash authorization path",
)
require(
    "fastboot-partition-inventory" in manifest and
    "fastboot-partition-probe-planner" in manifest and
    "FastbootPartitionInventoryTest.kt" in manifest,
    "Inventory: pure regression modules registered",
)
require(
    "без A/B (legacy A-only)" in main_activity,
    "Inventory: legacy A-only topology is visible in device overview",
)
require(
    "showPartitionInventoryDialog()" in main_activity and "snapshot.filtered(" in main_activity,
    "Inventory: read-only filtered partition browser is wired into UI",
)
require(
    "invalidateAllFastbootArtifactQualifications(" in viewmodel and
    "staged artifact released: $reason" in viewmodel,
    "Staging lifecycle: deleting private copy invalidates stale qualification evidence",
)
preparation_policy = read("app/src/main/java/ru/forum/adbfastboottool/FastbootFlashPreparationPolicy.kt")
require(
    "STANDARD_ONE_PASS" in preparation_policy and "STRICT_QUALIFICATION" in preparation_policy,
    "Preparation policy: standard and strict modes are explicit",
)
require(
    "RiskTier.NORMAL -> Mode.STANDARD_ONE_PASS" in preparation_policy and
    "RiskTier.CRITICAL -> Mode.STRICT_QUALIFICATION" in preparation_policy,
    "Preparation policy: only normal partitions use one-pass staging",
)
require(
    "prepareGuidedFastbootDataArtifact(" in viewmodel and
    "runDataQualificationTestDetailed" not in viewmodel.split("private suspend fun prepareGuidedFastbootDataArtifact", 1)[-1].split("private fun requireQualifiedStagedFastbootDataArtifact", 1)[0],
    "Guided standard path does not duplicate the DATA transfer",
)
require(
    "fastboot-flash-preparation-policy" in manifest,
    "Preparation policy: pure regression module registered",
)

if errors:
    print("\nFAIL flash-safety checks:", file=sys.stderr)
    for e in errors:
        print(f" - {e}", file=sys.stderr)
    raise SystemExit(1)
print("\nPASS flash-safety checks")
