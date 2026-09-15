package io.github.ncorror.nekoflash.protocol.adb

import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticEvent
import io.github.ncorror.nekoflash.core.diagnostics.DiagnosticSink
import java.time.Instant

/** Файл, который ставим: имя для оператора и объём для `install-write`. */
public data class AdbInstallFile(
    val name: String,
    val sizeBytes: Long,
)

/**
 * Где остановилась установка.
 *
 * Нужна не для красоты отчёта: до [COMMIT] на устройстве меняется только
 * временный файл в `/data/local/tmp`, а после — состояние пакета. Сказать
 * «не установилось», не назвав стадию, значит смешать «ничего не произошло» с
 * «неизвестно, что произошло».
 */
public enum class AdbInstallStage {
    /** Файл кладётся на устройство. Пакеты не тронуты. */
    UPLOAD,

    /** `pm install-create`. Сессия ещё пуста. */
    CREATE,

    /** `pm install-write`. Сессия наполняется, установка не начата. */
    WRITE,

    /** `pm install` или `pm install-commit`. **Граница мутации.** */
    COMMIT,
}

/** Чем кончилась установка. */
public sealed interface AdbInstallOutcome {
    /** Пакетный менеджер сказал, что поставил. Слово устройства, не наше. */
    public data class Installed(val output: String) : AdbInstallOutcome

    /**
     * Устройство отказало и назвало причину.
     *
     * Это **ответ**, а не наша ошибка: `pm` вернул ненулевой код и объяснил, и
     * подменять его объяснение своим значило бы скрыть слово peer'а (`03` §2).
     */
    public data class Refused(
        val stage: AdbInstallStage,
        val detail: String,
        val output: String,
    ) : AdbInstallOutcome

    /**
     * Чем кончилось — неизвестно.
     *
     * Обрыв на [AdbInstallStage.COMMIT] означает ровно это: команда ушла, ответ
     * не прочитан, и пакет мог установиться. Назвать такое неудачей значило бы
     * соврать о состоянии устройства (`03` §3) — тот же случай, что `DONEDONE`
     * у sideload (`07` §6.82).
     */
    public data class Unknown(
        val stage: AdbInstallStage,
        val detail: String,
    ) : AdbInstallOutcome

    /** До устройства не дошло: команда не отправлялась. */
    public data class NotStarted(val detail: String) : AdbInstallOutcome
}

/**
 * Как файл попадает на устройство.
 *
 * Швом, а не готовой реализацией: запись по `sync:` уже написана
 * ([AdbSyncUpload]), а источник байтов живёт в приложении и тянуть его в
 * протокольный модуль незачем — ровно как у [AdbSideloadSource].
 *
 * @return `null`, если файл лёг; иначе — почему нет.
 */
public fun interface AdbInstallPush {
    public fun push(file: AdbInstallFile, remotePath: String): String?
}

/**
 * Установка APK через пакетный менеджер устройства.
 *
 * **Устроено как в Legacy, и это разобрано, а не угадано** (`AdbProtocol.kt`:
 * `installApk` строки 979–1000, `installMultipleApks` 1272–1357). Файл кладётся
 * во временный путь и ставится оттуда, а не стримится в `pm install -S -`:
 * потокового варианта в архиве нет, и придумывать его под видом переноса
 * нельзя. A2 установки не умеет вовсе — проверено, источник один.
 *
 * **Код возврата приходится добывать эхом.** Сервис `shell:` кода не несёт, и
 * Legacy дописывает к команде `rc=$?; echo …:$rc`. Берём вместе с причиной.
 * Маркера не видно — значит мы **не знаем**, чем кончилось, и это [
 * AdbInstallOutcome.Unknown], а не отказ: строка могла оборваться после
 * установки.
 *
 * **Временный файл убирается той же командной строкой**, что и ставит. Отдельным
 * вызовом он остался бы лежать при любом обрыве связи между ними.
 *
 * **Каждый шаг называется в журнале.** Установка — мутация, и до прогона `07`
 * §6.98 она не писала о себе ничего: в выгрузке оставались `service_open` с
 * командной строкой и `service_completed bytes=85`, из которых исход не
 * восстанавливается. Три из четырёх установок того прогона так и остались
 * неразобранными — устройство назвало причину отказа на экране, экран уехал, а
 * в журнале число байт. Fastboot пишет `reply=` и `payload=` у каждой команды;
 * здесь не писалось ничего, и несимметрично это было именно у того из двух,
 * который меняет состояние устройства.
 */
public class AdbInstall(
    private val shell: (String) -> AdbServiceOutcome,
    private val push: AdbInstallPush,
    private val stamp: () -> Long = System::currentTimeMillis,
    private val diagnostics: DiagnosticSink = DiagnosticSink { },
    private val clock: () -> Instant = Instant::now,
) {
    /** Ставит один APK. */
    public fun install(file: AdbInstallFile, options: List<String> = emptyList()): AdbInstallOutcome {
        val remote = tempPath(file.name, index = null, at = stamp())
        emit(
            "install_started",
            mapOf("name" to file.name, "bytes" to file.sizeBytes.toString(), "path" to remote),
        )
        val pushFailure = push.push(file, remote)
        return if (pushFailure != null) {
            journalled(AdbInstallOutcome.Refused(AdbInstallStage.UPLOAD, pushFailure, ""))
        } else {
            journalled(commitSingle(remote, options))
        }
    }

    /**
     * Ставит набор split-APK одной сессией пакетного менеджера.
     *
     * Меньше двух файлов сюда не идут: `install-multiple` для одного файла — это
     * [install], и делать вид, что разница только в имени метода, значило бы
     * заводить сессию там, где она не нужна. Legacy отказывает так же.
     */
    public fun installMultiple(
        files: List<AdbInstallFile>,
        options: List<String> = emptyList(),
    ): AdbInstallOutcome = if (files.size < 2) {
        journalled(AdbInstallOutcome.NotStarted("для набора нужны хотя бы два файла, получено ${files.size}"))
    } else {
        emit(
            "install_started",
            mapOf("files" to files.size.toString(), "bytes" to files.sumOf { it.sizeBytes }.toString()),
        )
        journalled(SplitSession(files, options).run())
    }

    private fun commitSingle(remote: String, options: List<String>): AdbInstallOutcome {
        val line = buildString {
            append("pm install")
            options.filter { it.isNotBlank() }.forEach { append(' ').append(quote(it)) }
            append(' ').append(quote(remote))
            append("; rc=\$?; echo ").append(RC_MARKER).append(":\$rc")
            append("; rm -f ").append(quote(remote))
            append("; exit \$rc")
        }
        return outcomeOf(AdbInstallStage.COMMIT, shell(line))
    }

    /**
     * Одна сессия `install-create` → `install-write` → `install-commit`.
     *
     * Отдельным классом, потому что у неё есть своё состояние — идентификатор
     * сессии, — и его надо уметь отменить с любого шага. Сессия, брошенная без
     * `install-abandon`, остаётся на устройстве и держит место.
     */
    private inner class SplitSession(
        private val files: List<AdbInstallFile>,
        private val options: List<String>,
    ) {
        // Одна отметка на всю сессию, а не по одной на файл: пути одного набора
        // должны читаться как один набор, в том числе когда их придётся убирать
        // руками после обрыва.
        private val at = stamp()
        private val remotes = files.mapIndexed { index, file -> file to tempPath(file.name, index, at) }

        fun run(): AdbInstallOutcome {
            val uploaded = upload()
            val outcome = uploaded ?: create()
            cleanup()
            return outcome
        }

        /** Кладёт все файлы; `null` — все легли. */
        private fun upload(): AdbInstallOutcome? = remotes
            .firstNotNullOfOrNull { (file, remote) ->
                push.push(file, remote)?.let { AdbInstallStage.UPLOAD to it }
            }
            ?.let { (stage, detail) -> AdbInstallOutcome.Refused(stage, detail, "") }

        private fun create(): AdbInstallOutcome {
            val line = buildString {
                append("pm install-create")
                if (options.none { it == "-S" || it == "--size" }) {
                    append(" -S ").append(files.sumOf { it.sizeBytes })
                }
                options.filter { it.isNotBlank() }.forEach { append(' ').append(quote(it)) }
                append("; rc=\$?; echo ").append(RC_MARKER).append(":\$rc; exit \$rc")
            }
            return when (val created = outcomeOf(AdbInstallStage.CREATE, shell(line))) {
                is AdbInstallOutcome.Installed -> withSession(created.output)
                else -> created
            }
        }

        /**
         * Идентификатор сессии из вывода `install-create`.
         *
         * Формы две, и обе наблюдались: `Success: created install session [123]`
         * и текстовая `session 123`. Legacy разбирает обе в том же порядке.
         * Не разобрали — сессия **создана**, а мы её потеряли, и это не отказ, а
         * незнание: её надо отменить, а сказать про неё нечего.
         */
        private fun withSession(output: String): AdbInstallOutcome {
            val id = BRACKETED.find(output)?.groupValues?.get(1)
                ?: NAMED.find(output)?.groupValues?.get(1)
            return if (id.isNullOrBlank()) {
                AdbInstallOutcome.Unknown(
                    AdbInstallStage.CREATE,
                    "сессия создана, но её номер в ответе не найден: ${output.take(OUTPUT_EXCERPT)}",
                )
            } else {
                writeThenCommit(id)
            }
        }

        private fun writeThenCommit(id: String): AdbInstallOutcome {
            val failed = remotes.withIndex().firstNotNullOfOrNull { (index, pair) ->
                val (file, remote) = pair
                val line = "pm install-write -S ${file.sizeBytes} $id " +
                    "${quote(splitName(index, file.name))} ${quote(remote)}" +
                    "; rc=\$?; echo $RC_MARKER:\$rc; exit \$rc"
                outcomeOf(AdbInstallStage.WRITE, shell(line)).takeIf { it !is AdbInstallOutcome.Installed }
            }
            return if (failed != null) {
                abandon(id)
                failed
            } else {
                commit(id)
            }
        }

        private fun commit(id: String): AdbInstallOutcome {
            val committed = outcomeOf(
                AdbInstallStage.COMMIT,
                shell("pm install-commit $id; rc=\$?; echo $RC_MARKER:\$rc; exit \$rc"),
            )
            // Отменяется только объявленный отказ. После `Unknown` сессия могла
            // уже установиться, и `install-abandon` по ней — это действие с
            // неизвестными последствиями поверх неизвестного состояния.
            if (committed is AdbInstallOutcome.Refused) abandon(id)
            return committed
        }

        private fun abandon(id: String) {
            shell("pm install-abandon $id")
        }

        private fun cleanup() {
            val paths = remotes.joinToString(" ") { (_, remote) -> quote(remote) }
            if (paths.isNotBlank()) shell("rm -f $paths")
        }
    }

    /**
     * Исход по выводу команды.
     *
     * Маркер кода возврата обязателен: без него строка оборвалась где-то
     * посреди, и что успело выполниться — неизвестно.
     */
    private fun outcomeOf(stage: AdbInstallStage, call: AdbServiceOutcome): AdbInstallOutcome = when (call) {
        is AdbServiceOutcome.Failed -> {
            emit(
                "install_step",
                mapOf("stage" to stage.name, "rc" to "none", "failure" to "${call.reason}: ${call.detail}"),
            )
            AdbInstallOutcome.Unknown(stage, "${call.reason}: ${call.detail}")
        }

        is AdbServiceOutcome.Completed -> {
            val text = call.text()
            val code = RC.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val clean = RC.replace(text, "").trim()
            emit(
                "install_step",
                mapOf(
                    "stage" to stage.name,
                    "rc" to (code?.toString() ?: "none"),
                    // Слова пакетного менеджера — единственное объяснение отказа,
                    // которое вообще существует, и держать их только на экране
                    // значит потерять их к разбору (`07` §6.98).
                    "output" to clean.take(OUTPUT_EXCERPT),
                ),
            )
            when {
                code == null -> AdbInstallOutcome.Unknown(
                    stage,
                    "ответ без кода возврата: ${clean.take(OUTPUT_EXCERPT)}",
                )

                code == 0 -> AdbInstallOutcome.Installed(clean)
                else -> AdbInstallOutcome.Refused(stage, "pm вернул $code", clean)
            }
        }
    }

    /**
     * Называет исход в журнале и возвращает его же.
     *
     * Отдельно от [outcomeOf]: тот пишет **ответ устройства** на один шаг, а
     * это — наше о нём заключение. Сводить их к одной строке значило бы сделать
     * `Unknown` неотличимым от отказа в записи ровно там, где `03` §3 требует
     * их различать.
     */
    private fun journalled(outcome: AdbInstallOutcome): AdbInstallOutcome {
        val fields = when (outcome) {
            is AdbInstallOutcome.Installed ->
                mapOf("outcome" to "INSTALLED", "output" to outcome.output.take(OUTPUT_EXCERPT))

            is AdbInstallOutcome.Refused ->
                mapOf("outcome" to "REFUSED", "stage" to outcome.stage.name, "detail" to outcome.detail)

            is AdbInstallOutcome.Unknown ->
                mapOf("outcome" to "UNKNOWN", "stage" to outcome.stage.name, "detail" to outcome.detail)

            is AdbInstallOutcome.NotStarted -> mapOf("outcome" to "NOT_STARTED", "detail" to outcome.detail)
        }
        emit("install_finished", fields)
        return outcome
    }

    private fun emit(message: String, fields: Map<String, String>) {
        diagnostics.emit(
            DiagnosticEvent(
                timestamp = clock(),
                category = AdbHandshake.DIAGNOSTIC_CATEGORY,
                message = message,
                fields = fields,
            ),
        )
    }

    /**
     * Путь во временной папке устройства.
     *
     * Имя чистится от всего, что не `[A-Za-z0-9._-]`, как в Legacy. Молчаливой
     * подменой пользовательских данных это не является: путь наш собственный и
     * служебный, а имя, которое увидит пакетный менеджер, задаётся отдельно
     * ([splitName]).
     */
    private fun tempPath(name: String, index: Int?, at: Long): String {
        val safe = UNSAFE.replace(name, "_").ifBlank { "package.apk" }
        val middle = index?.let { "session-$at-$it" } ?: at.toString()
        return "$TEMP_DIR/nekoflash-$middle-$safe"
    }

    /**
     * Имя split'а для `install-write`.
     *
     * Первый файл набора, названный `base…`, объявляется `base.apk`: пакетный
     * менеджер различает базовый APK и добавочные по имени, и назвать базовый
     * иначе значит собрать неустановимый набор. Ведущий числовой префикс, каким
     * распаковщики нумеруют файлы, снимается — он к имени split'а не относится.
     * Всё это из Legacy `buildSplitName`.
     */
    private fun splitName(index: Int, name: String): String {
        val clean = UNSAFE.replace(name, "_")
        val numbered = NUMBER_PREFIX.replace(clean, "")
        val base = index == 0 && numbered.lowercase().startsWith("base")
        return if (base) "base.apk" else numbered.ifBlank { clean }
    }

    /** Аргумент для оболочки устройства: одинарные кавычки и экранированная кавычка внутри. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TEMP_DIR = "/data/local/tmp"
        const val RC_MARKER = "NEKOFLASH_PM_RC"
        const val OUTPUT_EXCERPT = 200
        val RC = Regex("$RC_MARKER:(-?\\d+)")
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
        val NUMBER_PREFIX = Regex("^\\d{3}-")
        val BRACKETED = Regex("\\[(\\d+)]")
        val NAMED = Regex("(?i)session\\s+(\\d+)")
    }
}
