package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRegressionTest {

    @Test
    fun usbCloseRequiresBothNativeAndKotlinTransfersToBeIdle() {
        assertTrue(UsbTransportShutdownPolicy.canCloseUsb(false, false))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(true, false))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(false, true))
        assertFalse(UsbTransportShutdownPolicy.canCloseUsb(true, true))
    }

    @Test
    fun partitionResolverNeverTurnsRecoveryBrandIntoAutomaticTarget() {
        val recovery = PartitionNameResolver.resolve("OrangeFox-R11.1_3-vayu.img")

        assertEquals(PartitionNameResolver.Kind.RECOVERY_IMAGE, recovery.kind)
        assertNull(recovery.partition)
        assertEquals(listOf("recovery", "boot", "vendor_boot", "init_boot"), recovery.candidates)
    }

    @Test
    fun partitionResolverKeepsExactAndArchiveClassificationSeparate() {
        assertEquals("vendor_boot", PartitionNameResolver.suggest("vendor_boot_a.img"))
        assertNull(PartitionNameResolver.suggest("rom-update.zip"))
        assertEquals(
            PartitionNameResolver.Kind.ARCHIVE,
            PartitionNameResolver.resolve("rom-update.zip").kind
        )
    }
}
