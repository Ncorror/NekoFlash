package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAndSideloadRegressionTest {

    @Test
    fun classicAdbVersionRequiresUnsignedPayloadChecksum() {
        val payload = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())

        assertEquals(510, AdbPacketChecksum.compute(payload))
        assertTrue(
            AdbPacketChecksum.isValid(
                expected = 510,
                payload = payload,
                localVersion = AdbPacketChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbPacketChecksum.VERSION_WITH_CHECKSUM
            )
        )
        assertFalse(
            AdbPacketChecksum.isValid(
                expected = 0,
                payload = payload,
                localVersion = AdbPacketChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbPacketChecksum.VERSION_WITH_CHECKSUM
            )
        )
    }

    @Test
    fun newerAdbPeersMaySkipChecksumOnlyWhenBothSidesNegotiateIt() {
        val payload = "NekoFlash".toByteArray()

        assertTrue(
            AdbPacketChecksum.isValid(
                expected = 0,
                payload = payload,
                localVersion = AdbPacketChecksum.VERSION_SKIP_CHECKSUM,
                peerVersion = AdbPacketChecksum.VERSION_SKIP_CHECKSUM
            )
        )
        assertFalse(
            AdbPacketChecksum.isValid(
                expected = 0,
                payload = payload,
                localVersion = AdbPacketChecksum.VERSION_WITH_CHECKSUM,
                peerVersion = AdbPacketChecksum.VERSION_SKIP_CHECKSUM
            )
        )
    }

    @Test
    fun rebootDisconnectIsExpectedOnlyAfterOpenWasWritten() {
        assertEquals("reboot:", AdbServiceCompletionPolicy.normalizeRebootService("system"))
        assertEquals("reboot:recovery", AdbServiceCompletionPolicy.normalizeRebootService(" Recovery "))
        assertTrue(AdbServiceCompletionPolicy.expectsOneWayDisconnect("reboot:bootloader"))
        assertFalse(AdbServiceCompletionPolicy.expectsOneWayDisconnect("shell:reboot"))

        assertTrue(
            AdbServiceCompletionPolicy.isExpectedCompletion(
                service = "reboot:bootloader",
                openPacketWritten = true,
                signal = AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_CLOSED
            )
        )
        assertFalse(
            AdbServiceCompletionPolicy.isExpectedCompletion(
                service = "reboot:bootloader",
                openPacketWritten = false,
                signal = AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_CLOSED
            )
        )
        assertFalse(
            AdbServiceCompletionPolicy.isExpectedCompletion(
                service = "shell:reboot",
                openPacketWritten = true,
                signal = AdbServiceCompletionPolicy.TerminalSignal.TRANSPORT_CLOSED
            )
        )
    }

    @Test
    fun sideloadCloseBeforeDoneDoneUsesExistingNinetyFivePercentBoundary() {
        assertEquals(
            SideloadCompletionPolicy.CloseClassification.FAILED,
            SideloadCompletionPolicy.classifyCloseBeforeDoneDone(94, 100)
        )
        assertEquals(
            SideloadCompletionPolicy.CloseClassification.VERIFY_PENDING,
            SideloadCompletionPolicy.classifyCloseBeforeDoneDone(95, 100)
        )
        assertEquals(
            SideloadCompletionPolicy.CloseClassification.VERIFY_PENDING,
            SideloadCompletionPolicy.classifyCloseBeforeDoneDone(100, 100)
        )
    }

    @Test
    fun doneDoneAloneNeverBecomesRecoveryInstallSuccess() {
        val result = RecoveryInstallVerifier.evaluate(
            listOf(
                RecoveryInstallVerifier.LogSource(
                    path = "/tmp/recovery.log",
                    text = "Starting ADB sideload\nDONEDONE\n"
                )
            )
        )

        assertEquals(RecoveryInstallVerifier.Verdict.UNKNOWN, result.verdict)
    }

    @Test
    fun explicitRecoveryStatusDeterminesFinalSideloadResult() {
        val success = RecoveryInstallVerifier.evaluate(
            listOf(
                RecoveryInstallVerifier.LogSource(
                    path = "/tmp/recovery.log",
                    text = "Starting ADB sideload\nInstall from ADB complete (status: 0)\n"
                )
            )
        )
        val failure = RecoveryInstallVerifier.evaluate(
            listOf(
                RecoveryInstallVerifier.LogSource(
                    path = "/tmp/recovery.log",
                    text = "Starting ADB sideload\nInstall from ADB complete (status: 1)\n"
                )
            )
        )

        assertEquals(RecoveryInstallVerifier.Verdict.SUCCESS, success.verdict)
        assertEquals(RecoveryInstallVerifier.Verdict.FAILED, failure.verdict)
    }
}
