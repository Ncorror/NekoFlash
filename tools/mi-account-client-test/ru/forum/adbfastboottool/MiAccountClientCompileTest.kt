package ru.forum.adbfastboottool

private fun assertEquals(expected: Any?, actual: Any?, message: String) {
    if (expected != actual) error("$message: expected=$expected actual=$actual")
}

fun main() {
    assertEquals(
        "https://eu-unlock.update.intl.miui.com",
        MiAccountClient.hostForZone("Europe"),
        "Europe unlock host"
    )
    val auth = MiAccountClient.AuthResult(
        host = MiAccountClient.hostForZone("Singapore"),
        ssecurity = "s",
        serviceCookies = "serviceToken=t",
        serviceCookieNames = listOf("serviceToken"),
        region = "PL",
        dataCenterZone = "Singapore",
        zoneSource = "test",
        userId = "42",
        deviceId = "device"
    )
    val changed = MiAccountClient.withDataCenterZone(auth, "Europe")
    assertEquals("Europe", changed.dataCenterZone, "zone override")
    assertEquals("https://eu-unlock.update.intl.miui.com", changed.host, "zone host override")
    println("MiAccountClientCompileTest: PASS")
}
