package ru.forum.adbfastboottool

private fun requireCheck(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    fun evaluate(path: String, text: String) = RecoveryInstallVerifier.evaluate(
        listOf(RecoveryInstallVerifier.LogSource(path, text))
    )

    val aospSuccess = evaluate(
        "/tmp/recovery.log",
        "Installing update...\nInstall from ADB complete (status: 0).\n"
    )
    requireCheck(aospSuccess.verdict == RecoveryInstallVerifier.Verdict.SUCCESS, "status 0 must be success")

    val aospFailure = evaluate(
        "/tmp/recovery.log",
        "Installing update...\nInstall from ADB complete (status: 1).\n"
    )
    requireCheck(aospFailure.verdict == RecoveryInstallVerifier.Verdict.FAILED, "status 1 must be failure")

    val productFailure = evaluate(
        "/tmp/recovery.log",
        "Extracting system...\nFailed to flash product.img: No space left on device\nUpdater process ended with ERROR: 1\n"
    )
    requireCheck(productFailure.verdict == RecoveryInstallVerifier.Verdict.FAILED, "product.img failure must fail")
    requireCheck(productFailure.evidence?.contains("product.img") == true, "product.img evidence must be preserved")

    val twrpFailure = evaluate(
        "/tmp/recovery.log",
        "Installing zip file '/sideload/package.zip'\nError installing zip file '/sideload/package.zip'\n"
    )
    requireCheck(twrpFailure.verdict == RecoveryInstallVerifier.Verdict.FAILED, "TWRP zip error must fail")

    val latestWins = evaluate(
        "/tmp/recovery.log",
        "Install from ADB complete (status: 1).\n--- next attempt ---\nInstall from ADB complete (status: 0).\n"
    )
    requireCheck(latestWins.verdict == RecoveryInstallVerifier.Verdict.SUCCESS, "latest explicit result must win")

    val unknown = evaluate(
        "/tmp/recovery.log",
        "Starting recovery UI\nWaiting for commands\n"
    )
    requireCheck(unknown.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN, "ambiguous log must remain unknown")

    val genericComplete = evaluate(
        "/tmp/recovery.log",
        "Install from ADB complete.\n"
    )
    requireCheck(genericComplete.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN, "generic complete without numeric status must remain unknown")

    val updaterErrorZero = evaluate(
        "/tmp/recovery.log",
        "Updater process ended with ERROR: 0\n"
    )
    requireCheck(updaterErrorZero.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN, "ERROR: 0 alone must not be promoted to success")

    val mountNoise = evaluate(
        "/tmp/recovery.log",
        "Failed to mount /vendor: Invalid argument\nWaiting for commands\n"
    )
    requireCheck(mountNoise.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN, "generic mount noise alone must not be treated as install failure")

    val staleFailureBeforeNewSession = evaluate(
        "/tmp/recovery.log",
        "Updater process ended with ERROR: 1\nStarting ADB sideload\nWaiting for package data\n"
    )
    requireCheck(
        staleFailureBeforeNewSession.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN,
        "old failure before a newer sideload session must not be reused"
    )


    val orangeFoxSuccess = evaluate(
        "/tmp/recovery.log",
        """
        I:operation_start: 'Sideload'
        Установка ZIP файла '/sideload/package.zip'
        - Finished installing OrangeFox!
        I:Process ended with no errors.
        I:operation_end - status=0
        """.trimIndent()
    )
    requireCheck(
        orangeFoxSuccess.verdict == RecoveryInstallVerifier.Verdict.SUCCESS,
        "OrangeFox sideload status=0 must be recognized as success"
    )

    val orangeFoxFailure = evaluate(
        "/tmp/recovery.log",
        """
        I:operation_start: 'Sideload'
        Установка ZIP файла '/sideload/package.zip'
        I:operation_end - status=1
        """.trimIndent()
    )
    requireCheck(
        orangeFoxFailure.verdict == RecoveryInstallVerifier.Verdict.FAILED,
        "OrangeFox/TWRP sideload non-zero operation_end must fail"
    )

    val staleOrangeFoxSuccess = evaluate(
        "/cache/recovery/last_log",
        """
        Starting recovery UI
        - Finished installing OrangeFox!
        Waiting for commands
        """.trimIndent()
    )
    requireCheck(
        staleOrangeFoxSuccess.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN,
        "OrangeFox success marker outside a current sideload session must remain unknown"
    )

    val staleOperationEnd = evaluate(
        "/tmp/recovery.log",
        """
        I:operation_end - status=0
        Starting recovery UI
        Waiting for commands
        """.trimIndent()
    )
    requireCheck(
        staleOperationEnd.verdict == RecoveryInstallVerifier.Verdict.UNKNOWN,
        "operation_end status=0 outside a sideload session must remain unknown"
    )

    val currentRecoveryLogWins = RecoveryInstallVerifier.evaluate(
        listOf(
            RecoveryInstallVerifier.LogSource("/tmp/recovery.log", "Install from ADB complete (status: 0)."),
            RecoveryInstallVerifier.LogSource("/cache/recovery/last_install", "Install from ADB complete (status: 1).")
        )
    )
    requireCheck(currentRecoveryLogWins.verdict == RecoveryInstallVerifier.Verdict.SUCCESS, "current /tmp/recovery.log must have priority over possibly stale last_install")

    println("RECOVERY INSTALL VERIFIER TESTS: OK")
}
