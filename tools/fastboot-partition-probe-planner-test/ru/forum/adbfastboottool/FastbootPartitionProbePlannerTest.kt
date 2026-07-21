package ru.forum.adbfastboottool

fun main() {
    val vayuSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:vayu",
            "INFOhas-slot:recovery:no"
        ),
        complete = false,
        finalStatus = "FAIL"
    )
    val vayuInventory = FastbootPartitionInventory.from(vayuSource)
    val vayuPlan = FastbootPartitionProbePlanner.plan(vayuSource, vayuInventory, maxQueries = 24)
    check(vayuPlan.requests.any { it.partition == "recovery" })
    check(vayuPlan.requests.none { it.partition.endsWith("_a") || it.partition.endsWith("_b") })

    val abSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:marble",
            "INFOslot-count:2",
            "INFOhas-slot:boot:yes"
        )
    )
    val abInventory = FastbootPartitionInventory.from(abSource)
    val abPlan = FastbootPartitionProbePlanner.plan(abSource, abInventory, maxQueries = 24)
    check(abPlan.requests.any { it.partition == "boot_a" })
    check(abPlan.requests.any { it.partition == "boot_b" })
    check(abPlan.requests.none { it.partition == "boot" && it.field != FastbootGetVarAllParser.MetadataField.HAS_SLOT })

    val concreteSource = FastbootGetVarAllParser.parse(
        listOf(
            "INFOproduct:marble",
            "INFOslot-count:2",
            "INFOpartition-size:boot_a:0x6000000"
        )
    )
    val concreteInventory = FastbootPartitionInventory.from(concreteSource)
    val concretePlan = FastbootPartitionProbePlanner.plan(concreteSource, concreteInventory, maxQueries = 24)
    check(concretePlan.requests.any { it.partition == "boot_a" && it.field == FastbootGetVarAllParser.MetadataField.TYPE })
    check(concretePlan.requests.any { it.partition == "boot_a" && it.field == FastbootGetVarAllParser.MetadataField.LOGICAL })
    check(concretePlan.requests.none { it.partition == "boot_a" && it.field == FastbootGetVarAllParser.MetadataField.SIZE })

    val emptyUnknownSource = FastbootGetVarAllParser.parse(
        listOf("INFOproduct:unknown"),
        complete = false,
        finalStatus = "FAIL"
    )
    val emptyUnknownInventory = FastbootPartitionInventory.from(emptyUnknownSource)
    val limited = FastbootPartitionProbePlanner.plan(emptyUnknownSource, emptyUnknownInventory, maxQueries = 5)
    check(limited.discoveryFallbackUsed)
    check(limited.requests.size == 5)
    check(limited.omittedRequestCount > 0)
    check(limited.requests.none { it.partition.endsWith("_a") || it.partition.endsWith("_b") })

    println("FASTBOOT PARTITION PROBE PLANNER TESTS: OK")
}
