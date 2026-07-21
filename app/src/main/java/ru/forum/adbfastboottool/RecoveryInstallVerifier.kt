package ru.forum.adbfastboottool

/**
 * Интерпретирует результат установки только по данным, которые Recovery уже записало
 * после завершения sideload-потока. DONEDONE сюда намеренно не относится: это лишь
 * завершение host-side потока передачи, а не install result.
 */
object RecoveryInstallVerifier {

    enum class Verdict { SUCCESS, FAILED, UNKNOWN }

    data class LogSource(
        val path: String,
        val text: String
    )

    data class Result(
        val verdict: Verdict,
        val message: String,
        val source: String? = null,
        val evidence: String? = null
    )

    private data class Event(
        val index: Int,
        val verdict: Verdict,
        val message: String,
        val evidence: String
    )

    fun evaluate(sources: List<LogSource>): Result {
        if (sources.isEmpty()) {
            return Result(
                verdict = Verdict.UNKNOWN,
                message = "Recovery-лог недоступен: результат установки не подтверждён"
            )
        }

        // Текущий /tmp/recovery.log имеет приоритет; last_install/last_log используются
        // только как fallback, потому что они могут содержать предыдущую попытку.
        val ordered = sources.sortedBy { sourcePriority(it.path) }
        ordered.forEach { source ->
            evaluateSource(source)?.let { return it }
        }

        return Result(
            verdict = Verdict.UNKNOWN,
            message = "Recovery-лог получен, но явный итог последней установки не найден",
            source = ordered.firstOrNull()?.path
        )
    }

    private fun evaluateSource(source: LogSource): Result? {
        val text = latestSessionSlice(source.text)
        if (text.isBlank()) return null

        val events = mutableListOf<Event>()

        Regex("(?i)Install from ADB complete \\(status:\\s*(-?\\d+)\\)").findAll(text).forEach { match ->
            val status = match.groupValues[1].toIntOrNull() ?: return@forEach
            val verdict = if (status == 0) Verdict.SUCCESS else Verdict.FAILED
            events += Event(
                index = match.range.first,
                verdict = verdict,
                message = if (status == 0) {
                    "Recovery подтвердило успешную установку: status=0"
                } else {
                    "Recovery завершило установку с ошибкой: status=$status"
                },
                evidence = lineAt(text, match.range.first)
            )
        }

        Regex("""(?i)Updater process ended with ERROR:\s*(-?\d+)""").findAll(text).forEach { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (code != 0) {
                events += Event(
                    index = match.range.first,
                    verdict = Verdict.FAILED,
                    message = "Updater завершился с ERROR: $code",
                    evidence = contextualEvidence(text, match.range.first)
                )
            }
        }

        // TWRP/OrangeFox record the final action status separately from DONEDONE.
        // Accept it only inside the current recognized Sideload session.
        if (isSideloadSession(text)) {
            Regex("(?i)Finished installing OrangeFox!").findAll(text).forEach { match ->
                events += Event(
                    index = match.range.first,
                    verdict = Verdict.SUCCESS,
                    message = "Recovery сообщает об успешной установке OrangeFox",
                    evidence = lineAt(text, match.range.first)
                )
            }

            Regex("""(?im)^\s*I:operation_end\s*-\s*status=(-?\d+)\s*$""")
                .findAll(text)
                .forEach { match ->
                    val status = match.groupValues[1].toIntOrNull() ?: return@forEach
                    events += Event(
                        index = match.range.first,
                        verdict = if (status == 0) Verdict.SUCCESS else Verdict.FAILED,
                        message = if (status == 0) {
                            "Recovery подтвердило успешное завершение Sideload: operation_end status=0"
                        } else {
                            "Recovery завершило Sideload с ошибкой: operation_end status=$status"
                        },
                        evidence = contextualEvidence(text, match.range.first)
                    )
                }
        }

        failurePatterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                events += Event(
                    index = match.range.first,
                    verdict = Verdict.FAILED,
                    message = failureMessage(match.value),
                    evidence = contextualEvidence(text, match.range.first)
                )
            }
        }

        successPatterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                events += Event(
                    index = match.range.first,
                    verdict = Verdict.SUCCESS,
                    message = "Recovery сообщает об успешной установке",
                    evidence = lineAt(text, match.range.first)
                )
            }
        }

        val lastEvent = events.maxByOrNull { it.index } ?: return null
        return Result(
            verdict = lastEvent.verdict,
            message = lastEvent.message,
            source = source.path,
            evidence = lastEvent.evidence
        )
    }

    private fun isSideloadSession(text: String): Boolean =
        sideloadSessionPatterns.any { it.containsMatchIn(text) }

    private fun latestSessionSlice(text: String): String {
        if (text.isBlank()) return text
        val start = sessionStartPatterns
            .flatMap { regex -> regex.findAll(text).map { it.range.first }.toList() }
            .maxOrNull()
            ?: return text
        return text.substring(start)
    }

    private fun sourcePriority(path: String): Int {
        val lower = path.lowercase()
        return when {
            lower.endsWith("/recovery.log") -> 0
            lower.endsWith("/last_install") -> 1
            lower.endsWith("/last_log") -> 2
            lower.endsWith("/install.log") -> 3
            else -> 4
        }
    }

    private fun failureMessage(value: String): String {
        val normalized = value.lowercase()
        return when {
            "product.img" in normalized -> "Recovery сообщает об ошибке записи product.img"
            "installation aborted" in normalized -> "Recovery прервало установку"
            "error installing zip" in normalized -> "Recovery не смогло установить ZIP"
            else -> "Recovery сообщает об ошибке установки"
        }
    }

    private fun lineAt(text: String, index: Int): String {
        val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim().take(500)
    }

    private fun contextualEvidence(text: String, index: Int): String {
        val lines = text.lines()
        var consumed = 0
        var target = 0
        for ((i, line) in lines.withIndex()) {
            val next = consumed + line.length + 1
            if (index < next) {
                target = i
                break
            }
            consumed = next
        }
        val from = (target - 2).coerceAtLeast(0)
        val to = (target + 2).coerceAtMost(lines.lastIndex)
        return lines.subList(from, to + 1)
            .joinToString(" | ") { it.trim() }
            .take(1200)
    }

    private val sideloadSessionPatterns = listOf(
        Regex("""(?im)^\s*Starting ADB sideload"""),
        Regex("""(?im)^\s*Starting sideload"""),
        Regex("""(?im)^\s*I:operation_start:\s*['"]Sideload['"]\s*$"""),
        Regex("""(?im)^\s*Запуск ADB Sideload"""),
        Regex("""(?im)^\s*Installing zip file"""),
        Regex("""(?im)^\s*Установка ZIP файла""")
    )

    private val sessionStartPatterns = sideloadSessionPatterns + listOf(
        Regex("""(?im)^\s*Installing update"""),
        Regex("""(?im)^\s*Installing package""")
    )

    private val failurePatterns = listOf(
        Regex("(?i)Error installing zip file"),
        Regex("(?i)Installation aborted"),
        Regex("(?i)E:Error in .*\\(status\\s+[-]?\\d+\\)"),
        Regex("(?i)failed[^\\n]{0,160}product\\.img"),
        Regex("(?i)product\\.img[^\\n]{0,160}failed"),
        Regex("(?i)script aborted[^\\n]*")
    )

    private val successPatterns = listOf(
        Regex("(?i)Install completed successfully"),
        Regex("(?i)installation successful"),
        Regex("(?i)script succeeded[^\\n]*")
    )

}
