package ru.forum.adbfastboottool

fun main() {
    val standard = FastbootFlashPreparationPolicy.Mode.STANDARD_ONE_PASS
    val strict = FastbootFlashPreparationPolicy.Mode.STRICT_QUALIFICATION

    check(FastbootFlashPreparationPolicy.forGuidedPartition("boot") == standard)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("boot_a") == standard)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("recovery") == standard)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("vendor_boot_b") == standard)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("dtbo") == standard)

    check(FastbootFlashPreparationPolicy.forGuidedPartition("vbmeta") == strict)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("super") == strict)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("system_a") == strict)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("userdata") == strict)
    check(FastbootFlashPreparationPolicy.forGuidedPartition("unknown_partition") == strict)

    println("FASTBOOT FLASH PREPARATION POLICY TESTS: OK")
}
