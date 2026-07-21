package ru.forum.adbfastboottool

private fun assertTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val first = (1..600).map { "line-$it" }
    val initial = CompactLogRenderPolicy.decide(first, CompactLogRenderPolicy.State())
    assertTrue(!initial.reset && initial.startIndex == 0, "initial render must append all lines")

    val unchanged = CompactLogRenderPolicy.decide(first, initial.nextState)
    assertTrue(!unchanged.reset && unchanged.startIndex == 600, "unchanged buffer must append nothing")

    val shifted = (2..601).map { "line-$it" }
    val afterRotation = CompactLogRenderPolicy.decide(shifted, initial.nextState)
    assertTrue(afterRotation.reset && afterRotation.startIndex == 0, "rolling buffer must rebuild after eviction")

    val shiftedWithSameLast = (2..599).map { "line-$it" } + listOf("line-600", "line-600")
    val duplicateLastRotation = CompactLogRenderPolicy.decide(shiftedWithSameLast, initial.nextState)
    assertTrue(
        duplicateLastRotation.reset && duplicateLastRotation.startIndex == 0,
        "first-line anchor must detect rotation even when the new last line is identical"
    )

    val appended = first + "line-601"
    val afterAppend = CompactLogRenderPolicy.decide(appended, initial.nextState)
    assertTrue(!afterAppend.reset && afterAppend.startIndex == 600, "growing buffer must append only the tail")

    val cleared = CompactLogRenderPolicy.decide(emptyList(), afterAppend.nextState)
    assertTrue(cleared.reset && cleared.nextState.renderedCount == 0, "clear must reset state")
    println("CompactLogRenderPolicyTest: PASS")
}
