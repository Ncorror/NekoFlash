package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Установка APK: формы команд и, главное, чтение исхода.
 *
 * Оболочка здесь подменяется целиком — проверяется не транспорт (он уже
 * доказан), а то, что уходит на устройство и как читается ответ.
 */
class AdbInstallTest {
    /** Что ушло в оболочку и что она ответила. */
    private class FakeShell(private val replies: MutableList<AdbServiceOutcome> = mutableListOf()) {
        val sent = mutableListOf<String>()

        fun willReply(text: String): FakeShell = apply {
            replies += AdbServiceOutcome.Completed(text.toByteArray(Charsets.UTF_8))
        }

        fun willFail(reason: AdbServiceFailure, detail: String): FakeShell = apply {
            replies += AdbServiceOutcome.Failed(reason, detail)
        }

        fun run(command: String): AdbServiceOutcome {
            sent += command
            return replies.removeFirstOrNull()
                ?: AdbServiceOutcome.Completed("NEKOFLASH_PM_RC:0".toByteArray(Charsets.UTF_8))
        }
    }

    private fun installer(
        shell: FakeShell,
        pushFailure: String? = null,
        pushed: MutableList<Pair<String, String>> = mutableListOf(),
        journal: MutableList<DiagnosticEvent> = mutableListOf(),
    ) = AdbInstall(
        shell = shell::run,
        push = { file, remote ->
            pushed += file.name to remote
            pushFailure
        },
        stamp = { 42L },
        diagnostics = { event -> journal += event },
        clock = { Instant.EPOCH },
    )

    /** Успех — это слово пакетного менеджера, прочитанное по коду возврата. */
    @Test
    fun aZeroReturnCodeIsTheInstall() {
        val shell = FakeShell().willReply("Success\nNEKOFLASH_PM_RC:0")

        val outcome = installer(shell).install(AdbInstallFile("app.apk", 10))

        assertEquals("Success", (outcome as AdbInstallOutcome.Installed).output)
    }

    /** Маркер кода возврата из вывода убирается: он наш, а не устройства. */
    @Test
    fun theReturnCodeMarkerIsNotShownAsDeviceOutput() {
        val shell = FakeShell().willReply("Success\nNEKOFLASH_PM_RC:0")

        val outcome = installer(shell).install(AdbInstallFile("app.apk", 10))

        assertTrue(
            "маркер не должен попадать в вывод",
            !(outcome as AdbInstallOutcome.Installed).output.contains("NEKOFLASH"),
        )
    }

    /** Ненулевой код — отказ устройства, и его объяснение сохраняется дословно. */
    @Test
    fun aNonZeroReturnCodeIsTheDeviceRefusing() {
        val shell = FakeShell().willReply("Failure [INSTALL_FAILED_INVALID_APK]\nNEKOFLASH_PM_RC:1")

        val outcome = installer(shell).install(AdbInstallFile("app.apk", 10))

        val refused = outcome as AdbInstallOutcome.Refused
        assertEquals(AdbInstallStage.COMMIT, refused.stage)
        assertTrue(refused.output, refused.output.contains("INSTALL_FAILED_INVALID_APK"))
    }

    /**
     * Ответ без кода возврата — это незнание, а не отказ.
     *
     * Строка могла оборваться уже **после** установки, и объявить такое
     * неудачей значило бы соврать о состоянии устройства (`03` §3).
     */
    @Test
    fun anAnswerWithoutAReturnCodeIsUnknownNotFailure() {
        val shell = FakeShell().willReply("Success")

        val outcome = installer(shell).install(AdbInstallFile("app.apk", 10))

        assertEquals(AdbInstallStage.COMMIT, (outcome as AdbInstallOutcome.Unknown).stage)
    }

    /** Оборванный вызов на границе мутации — тоже неизвестность, с названной стадией. */
    @Test
    fun aBrokenCallAtTheBoundaryIsUnknown() {
        val shell = FakeShell().willFail(AdbServiceFailure.TIMED_OUT, "ответа не было")

        val outcome = installer(shell).install(AdbInstallFile("app.apk", 10))

        val unknown = outcome as AdbInstallOutcome.Unknown
        assertEquals(AdbInstallStage.COMMIT, unknown.stage)
        assertTrue(unknown.detail, unknown.detail.contains("TIMED_OUT"))
    }

    /** Файл не лёг — пакеты не тронуты, и стадия это говорит. */
    @Test
    fun aFailedUploadNeverReachesThePackageManager() {
        val shell = FakeShell()

        val outcome = installer(shell, pushFailure = "места нет").install(AdbInstallFile("app.apk", 10))

        assertEquals(AdbInstallStage.UPLOAD, (outcome as AdbInstallOutcome.Refused).stage)
        assertTrue("оболочку трогать было незачем", shell.sent.isEmpty())
    }

    /** Временный файл убирается той же строкой, что и ставит. */
    @Test
    fun theTemporaryFileIsRemovedByTheSameCommandLine() {
        val shell = FakeShell().willReply("Success\nNEKOFLASH_PM_RC:0")

        installer(shell).install(AdbInstallFile("app.apk", 10))

        val line = shell.sent.single()
        assertTrue(line, line.contains("pm install "))
        assertTrue(line, line.contains("rm -f '/data/local/tmp/nekoflash-42-app.apk'"))
    }

    /** Имя временного пути чистится, а кавычка внутри не ломает строку оболочки. */
    @Test
    fun anAwkwardNameNeitherBreaksTheLineNorLeaksIntoIt() {
        val shell = FakeShell().willReply("Success\nNEKOFLASH_PM_RC:0")

        installer(shell).install(AdbInstallFile("it's a; rm -rf /.apk", 10))

        val line = shell.sent.single()
        assertTrue(line, line.contains("nekoflash-42-it_s_a__rm_-rf__.apk"))
        assertTrue("исходное имя не должно уходить как есть", !line.contains("rm -rf /"))
    }

    /** Один файл в набор не идёт: для него есть обычная установка. */
    @Test
    fun aSetOfOneIsNotASet() {
        val shell = FakeShell()

        val outcome = installer(shell).installMultiple(listOf(AdbInstallFile("base.apk", 1)))

        assertTrue(outcome is AdbInstallOutcome.NotStarted)
        assertTrue(shell.sent.isEmpty())
    }

    /** Набор идёт сессией: create с общим объёмом, write на каждый файл, commit. */
    @Test
    fun aSplitSetGoesThroughOneSession() {
        val shell = FakeShell()
            .willReply("Success: created install session [77]\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")

        val outcome = installer(shell).installMultiple(
            listOf(AdbInstallFile("base.apk", 100), AdbInstallFile("split_config.arm64.apk", 20)),
        )

        assertTrue(outcome is AdbInstallOutcome.Installed)
        assertTrue(shell.sent[0], shell.sent[0].contains("pm install-create -S 120"))
        assertTrue(shell.sent[1], shell.sent[1].contains("pm install-write -S 100 77 'base.apk'"))
        assertTrue(shell.sent[2], shell.sent[2].contains("pm install-write -S 20 77 'split_config.arm64.apk'"))
        assertTrue(shell.sent[3], shell.sent[3].contains("pm install-commit 77"))
        assertTrue("временные файлы убираются", shell.sent.last().startsWith("rm -f "))
    }

    /** Номер сессии читается и из текстовой формы, не только из скобок. */
    @Test
    fun theSessionNumberIsAlsoReadFromTheTextualForm() {
        val shell = FakeShell()
            .willReply("created install session 501\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")

        installer(shell).installMultiple(listOf(AdbInstallFile("a.apk", 1), AdbInstallFile("b.apk", 2)))

        assertTrue(shell.sent[3], shell.sent[3].contains("pm install-commit 501"))
    }

    /** Сессия создана, а номер не разобран — это незнание, и сессию надо отменять вручную. */
    @Test
    fun aCreatedSessionWithoutANumberIsUnknown() {
        val shell = FakeShell().willReply("Success\nNEKOFLASH_PM_RC:0")

        val outcome = installer(shell).installMultiple(
            listOf(AdbInstallFile("a.apk", 1), AdbInstallFile("b.apk", 2)),
        )

        assertEquals(AdbInstallStage.CREATE, (outcome as AdbInstallOutcome.Unknown).stage)
    }

    /** Отказ на записи отменяет сессию, а не оставляет её на устройстве. */
    @Test
    fun aRefusedWriteAbandonsTheSession() {
        val shell = FakeShell()
            .willReply("Success: created install session [9]\nNEKOFLASH_PM_RC:0")
            .willReply("Failure\nNEKOFLASH_PM_RC:1")

        val outcome = installer(shell).installMultiple(
            listOf(AdbInstallFile("a.apk", 1), AdbInstallFile("b.apk", 2)),
        )

        assertEquals(AdbInstallStage.WRITE, (outcome as AdbInstallOutcome.Refused).stage)
        assertTrue("сессию надо отменить", shell.sent.any { it.contains("pm install-abandon 9") })
    }

    /**
     * После неизвестного исхода `commit` сессия **не** отменяется.
     *
     * Она могла уже установиться, и `install-abandon` поверх неизвестного
     * состояния — действие с неизвестными последствиями.
     */
    @Test
    fun anUnknownCommitIsNotFollowedByAbandon() {
        val shell = FakeShell()
            .willReply("Success: created install session [5]\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willFail(AdbServiceFailure.FRAMING_LOST, "кадр потерян")

        val outcome = installer(shell).installMultiple(
            listOf(AdbInstallFile("a.apk", 1), AdbInstallFile("b.apk", 2)),
        )

        assertEquals(AdbInstallStage.COMMIT, (outcome as AdbInstallOutcome.Unknown).stage)
        assertTrue("отменять нечего — состояние неизвестно", shell.sent.none { it.contains("install-abandon") })
    }

    /** Первый файл набора, названный base…, объявляется base.apk. */
    @Test
    fun theFirstBaseFileIsDeclaredAsBaseApk() {
        val shell = FakeShell()
            .willReply("Success: created install session [1]\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")

        installer(shell).installMultiple(
            listOf(AdbInstallFile("000-base_master.apk", 1), AdbInstallFile("001-split.apk", 2)),
        )

        assertTrue(shell.sent[1], shell.sent[1].contains(" 1 'base.apk' "))
        assertTrue("нумерующий префикс распаковщика снимается", shell.sent[2].contains(" 1 'split.apk' "))
    }

    /** Все файлы набора кладутся под одной отметкой: это один набор, не три. */
    @Test
    fun theWholeSetSharesOneStamp() {
        val pushed = mutableListOf<Pair<String, String>>()
        val shell = FakeShell()
            .willReply("Success: created install session [1]\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")
            .willReply("Success\nNEKOFLASH_PM_RC:0")

        installer(shell, pushed = pushed).installMultiple(
            listOf(AdbInstallFile("a.apk", 1), AdbInstallFile("b.apk", 2)),
        )

        assertEquals("/data/local/tmp/nekoflash-session-42-0-a.apk", pushed[0].second)
        assertEquals("/data/local/tmp/nekoflash-session-42-1-b.apk", pushed[1].second)
    }

    /**
     * Слова пакетного менеджера попадают в журнал, а не только на экран.
     *
     * На прогоне `07` §6.98 три установки из четырёх отказали, и в выгрузке от
     * них осталось `service_completed bytes=85`: причина была названа
     * устройством, показана оператору и потеряна. Разобрать их теперь нечем.
     */
    @Test
    fun theDeviceWordsReachTheJournal() {
        val journal = mutableListOf<DiagnosticEvent>()
        val shell = FakeShell().willReply("Failure [INSTALL_FAILED_ALREADY_EXISTS]\nNEKOFLASH_PM_RC:1")

        installer(shell, journal = journal).install(AdbInstallFile("app.apk", 10))

        val step = journal.single { it.message == "install_step" }
        assertEquals("1", step.fields["rc"])
        assertTrue(step.fields.toString(), step.fields["output"]!!.contains("INSTALL_FAILED_ALREADY_EXISTS"))
    }

    /** Исход называется своим классом: `Unknown` не должен читаться как отказ. */
    @Test
    fun theOutcomeIsNamedByItsOwnClass() {
        val journal = mutableListOf<DiagnosticEvent>()
        val shell = FakeShell().willReply("что-то сказал и оборвался")

        installer(shell, journal = journal).install(AdbInstallFile("app.apk", 10))

        val finished = journal.single { it.message == "install_finished" }
        assertEquals("UNKNOWN", finished.fields["outcome"])
        assertEquals(AdbInstallStage.COMMIT.name, finished.fields["stage"])
    }

    /** Начало установки называется до отправки: по обрыву видно, что её начинали. */
    @Test
    fun theStartIsNamedBeforeAnythingIsSent() {
        val journal = mutableListOf<DiagnosticEvent>()

        installer(FakeShell(), journal = journal).install(AdbInstallFile("app.apk", 10))

        val started = journal.first()
        assertEquals("install_started", started.message)
        assertEquals("app.apk", started.fields["name"])
    }

    /** Отказ записи файла — тоже исход, и он тоже называется. */
    @Test
    fun aFailedUploadIsJournalledToo() {
        val journal = mutableListOf<DiagnosticEvent>()

        installer(FakeShell(), pushFailure = "CANCELLED: остановлено", journal = journal)
            .install(AdbInstallFile("app.apk", 10))

        val finished = journal.single { it.message == "install_finished" }
        assertEquals("REFUSED", finished.fields["outcome"])
        assertEquals(AdbInstallStage.UPLOAD.name, finished.fields["stage"])
    }
}
