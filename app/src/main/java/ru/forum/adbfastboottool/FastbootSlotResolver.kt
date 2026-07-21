package ru.forum.adbfastboottool

import java.util.Locale

/**
 * Pure A/B and single-slot target resolver.
 *
 * It never infers that every partition is slotted just because slot-count >= 2.
 * Evidence must come from has-slot:<base> or from concrete partition probes.
 *
 * V5.6.7 adds an explicit device topology layer so a Xiaomi/Qualcomm target
 * that returns FAILGetVar Variable Not found for slot-count/current-slot but has
 * an unsuffixed recovery partition is classified as SINGLE_SLOT instead of
 * UNKNOWN. This fixes single-slot devices such as Poco X3 Pro / vayu.
 */
object FastbootSlotResolver {

    enum class RequestedSlot { AUTO, ACTIVE, SLOT_A, SLOT_B, BOTH }
    enum class Layout { UNSLOTTED, A_B, UNKNOWN }
    enum class SlotTopology { SINGLE_SLOT, AB, UNKNOWN_UNSTABLE }

    data class Evidence(
        val slotCount: Int? = null,
        val currentSlot: String? = null,
        val hasSlot: Boolean? = null,
        val unsuffixedExists: Boolean? = null,
        val slotAExists: Boolean? = null,
        val slotBExists: Boolean? = null,
        val sessionBroken: Boolean = false
    )

    data class Resolution(
        val basePartition: String,
        val layout: Layout,
        val targets: List<String> = emptyList(),
        val error: String? = null,
        val notes: List<String> = emptyList(),
        val topology: SlotTopology = SlotTopology.UNKNOWN_UNSTABLE
    ) {
        val canProceed: Boolean get() = error == null && targets.isNotEmpty()
    }

    fun resolve(
        partition: String,
        requested: RequestedSlot,
        evidence: Evidence
    ): Resolution {
        val normalized = partition.trim().lowercase(Locale.US)
        val explicitSlot = when {
            normalized.endsWith("_a") -> "a"
            normalized.endsWith("_b") -> "b"
            else -> null
        }
        val base = normalized.removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        if (base.isBlank() || !base.matches(Regex("[a-z0-9._:-]+"))) {
            return Resolution(base, Layout.UNKNOWN, error = "Некорректное имя раздела: $partition", topology = SlotTopology.UNKNOWN_UNSTABLE)
        }

        val layout = detectLayout(evidence)
        val topology = detectTopology(evidence)
        val effectiveRequest = when {
            requested != RequestedSlot.AUTO -> requested
            explicitSlot == "a" -> RequestedSlot.SLOT_A
            explicitSlot == "b" -> RequestedSlot.SLOT_B
            else -> RequestedSlot.ACTIVE
        }

        fun error(message: String) = Resolution(base, layout, error = message, notes = evidenceNotes(evidence), topology = topology)
        fun ok(vararg targets: String) = Resolution(base, layout, targets = targets.toList(), notes = evidenceNotes(evidence), topology = topology)

        if (topology == SlotTopology.UNKNOWN_UNSTABLE && evidence.sessionBroken) {
            return error("Fastboot-сессия нестабильна или потеряна во время определения slot topology. Нужен новый вход в Fastboot.")
        }

        return when (effectiveRequest) {
            RequestedSlot.AUTO -> error("Внутренняя ошибка разрешения AUTO target")

            RequestedSlot.ACTIVE -> when {
                layout == Layout.UNSLOTTED -> ok(base)
                normalizedCurrentSlot(evidence.currentSlot) == "a" && slotTargetAllowed("a", evidence, layout) -> ok("${base}_a")
                normalizedCurrentSlot(evidence.currentSlot) == "b" && slotTargetAllowed("b", evidence, layout) -> ok("${base}_b")
                layout == Layout.A_B -> error("Не удалось определить активный слот для slotted-раздела $base")
                evidence.unsuffixedExists == true -> ok(base)
                else -> error("Не удалось доказать, какой target соответствует разделу $base. Проверьте has-slot/partition-size и повторите.")
            }

            RequestedSlot.SLOT_A -> when {
                evidence.slotAExists == true || layout == Layout.A_B -> ok("${base}_a")
                layout == Layout.UNSLOTTED -> error("Раздел $base подтверждён как однослотовый; target ${base}_a недопустим")
                else -> error("Не удалось подтвердить существование ${base}_a")
            }

            RequestedSlot.SLOT_B -> when {
                evidence.slotBExists == true || layout == Layout.A_B -> ok("${base}_b")
                layout == Layout.UNSLOTTED -> error("Раздел $base подтверждён как однослотовый; target ${base}_b недопустим")
                else -> error("Не удалось подтвердить существование ${base}_b")
            }

            RequestedSlot.BOTH -> {
                val bothConcrete = evidence.slotAExists == true && evidence.slotBExists == true
                val abConfirmed = layout == Layout.A_B && ((evidence.slotCount ?: 0) >= 2 || bothConcrete || evidence.hasSlot == true)
                if (abConfirmed) ok("${base}_a", "${base}_b")
                else if (layout == Layout.UNSLOTTED) error("Раздел $base подтверждён как однослотовый; прошивка A+B запрещена")
                else error("Не удалось подтвердить оба target: ${base}_a и ${base}_b")
            }
        }
    }

    fun explicitSlotFromFileName(fileName: String): String? {
        val lower = fileName.trim().lowercase(Locale.US)
        val a = Regex("_a(?:\\.|$)").containsMatchIn(lower)
        val b = Regex("_b(?:\\.|$)").containsMatchIn(lower)
        return when {
            a && !b -> "a"
            b && !a -> "b"
            else -> null
        }
    }

    fun validateExplicitFileSlot(fileName: String, targets: List<String>): String? {
        val explicit = explicitSlotFromFileName(fileName) ?: return null
        if (targets.size != 1) {
            return "Файл $fileName явно помечен для слота $explicit; прошивка этого файла в несколько target запрещена."
        }
        val targetSlot = when {
            targets.single().endsWith("_a") -> "a"
            targets.single().endsWith("_b") -> "b"
            else -> null
        }
        return if (targetSlot == explicit) null
        else "Файл $fileName предназначен для слота $explicit, а выбран target ${targets.single()}."
    }

    fun detectLayout(evidence: Evidence): Layout {
        return when {
            evidence.hasSlot == true -> Layout.A_B
            evidence.hasSlot == false -> Layout.UNSLOTTED
            evidence.slotAExists == true && evidence.slotBExists == true -> Layout.A_B
            evidence.unsuffixedExists == true && evidence.slotAExists != true && evidence.slotBExists != true -> Layout.UNSLOTTED
            else -> Layout.UNKNOWN
        }
    }

    fun detectTopology(evidence: Evidence): SlotTopology {
        if (evidence.sessionBroken) return SlotTopology.UNKNOWN_UNSTABLE
        val slotCount = evidence.slotCount
        val normalizedSlot = normalizedCurrentSlot(evidence.currentSlot)
        return when {
            evidence.hasSlot == true -> SlotTopology.AB
            evidence.slotAExists == true && evidence.slotBExists == true -> SlotTopology.AB
            slotCount != null && slotCount >= 2 && normalizedSlot in setOf("a", "b") -> SlotTopology.AB
            evidence.hasSlot == false -> SlotTopology.SINGLE_SLOT
            slotCount == 1 -> SlotTopology.SINGLE_SLOT
            evidence.unsuffixedExists == true && evidence.slotAExists != true && evidence.slotBExists != true -> SlotTopology.SINGLE_SLOT
            else -> SlotTopology.UNKNOWN_UNSTABLE
        }
    }

    fun topologyLabel(topology: SlotTopology): String = when (topology) {
        SlotTopology.AB -> "A/B"
        SlotTopology.SINGLE_SLOT -> "SINGLE_SLOT / без A/B"
        SlotTopology.UNKNOWN_UNSTABLE -> "UNKNOWN_UNSTABLE"
    }

    private fun slotTargetAllowed(slot: String, evidence: Evidence, layout: Layout): Boolean {
        return when (slot) {
            "a" -> evidence.slotAExists == true || layout == Layout.A_B
            "b" -> evidence.slotBExists == true || layout == Layout.A_B
            else -> false
        }
    }

    private fun normalizedCurrentSlot(raw: String?): String? =
        raw?.trim()?.removePrefix("_")?.lowercase(Locale.US)?.takeIf { it == "a" || it == "b" }

    private fun evidenceNotes(evidence: Evidence): List<String> = buildList {
        add("slot-count=${evidence.slotCount ?: "unknown"}")
        add("current-slot=${normalizedCurrentSlot(evidence.currentSlot) ?: "unknown"}")
        add("has-slot=${evidence.hasSlot ?: "unknown"}")
        add("base=${evidence.unsuffixedExists ?: "unknown"}")
        add("a=${evidence.slotAExists ?: "unknown"}")
        add("b=${evidence.slotBExists ?: "unknown"}")
        add("sessionBroken=${evidence.sessionBroken}")
    }
}
