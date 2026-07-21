package ru.forum.adbfastboottool

/**
 * Chooses how a guided flash payload is prepared.
 *
 * NORMAL boot-chain/recovery partitions use one-pass verified private staging:
 * source SHA-256 -> private copy + fsync -> staged SHA-256 -> one real Fastboot
 * download followed by flash. Advanced, critical and unknown targets keep the
 * explicit download-only qualification gate.
 *
 * This policy does not choose a partition, slot or transport and cannot authorize
 * a mutation by itself.
 */
object FastbootFlashPreparationPolicy {

    enum class Mode {
        STANDARD_ONE_PASS,
        STRICT_QUALIFICATION
    }

    fun forGuidedPartition(partitionName: String): Mode =
        when (FastbootPartitionInventory.riskTier(partitionName)) {
            FastbootPartitionInventory.RiskTier.NORMAL -> Mode.STANDARD_ONE_PASS
            FastbootPartitionInventory.RiskTier.ADVANCED,
            FastbootPartitionInventory.RiskTier.CRITICAL -> Mode.STRICT_QUALIFICATION
        }
}
