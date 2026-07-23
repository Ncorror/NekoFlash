package ru.forum.adbfastboottool

import java.io.IOException
import java.net.URI
import java.net.URL

private fun assertTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

private fun assertFalse(value: Boolean, message: String) = assertTrue(!value, message)

private fun assertEquals(expected: Any?, actual: Any?, message: String) {
    if (expected != actual) error("$message: expected=$expected actual=$actual")
}

private inline fun assertThrows(message: String, block: () -> Unit) {
    try {
        block()
    } catch (_: IOException) {
        return
    }
    error(message)
}

private fun url(raw: String): URL = URI(raw).toURL()

fun main() {
    assertTrue(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://account.xiaomi.com/pass/serviceLogin"),
        "primary Xiaomi Account endpoint must be allowed"
    )
    assertTrue(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://api.account.xiaomi.com/pass/configuration"),
        "API Xiaomi Account subdomain must be allowed"
    )
    assertTrue(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://eu.account.xiaomi.com/pass/forgetPassword"),
        "regional Xiaomi Account subdomain must be allowed"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedAccountUrl("http://account.xiaomi.com/pass/serviceLogin"),
        "cleartext Xiaomi Account endpoint must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://account.xiaomi.com.evil.example/pass"),
        "suffix-confusion host must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://xiaomi.com/pass"),
        "generic Xiaomi site is not an account endpoint"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://user@account.xiaomi.com/pass"),
        "userinfo URLs must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedAccountUrl("https://account.xiaomi.com:444/pass"),
        "unexpected HTTPS port must be blocked"
    )

    assertTrue(
        MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl("https://unlock.update.miui.com/sts?d=token"),
        "exact official Mi Unlock completion callback must be recognized"
    )
    assertFalse(
        MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl("http://unlock.update.miui.com/sts"),
        "cleartext completion callback must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl("https://unlock.update.miui.com/other"),
        "unexpected callback path must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl("https://evil.unlock.update.miui.com/sts"),
        "callback subdomain confusion must be blocked"
    )

    val officialServiceUrls = listOf(
        "https://unlock.update.miui.com/sts?d=cn",
        "https://unlock.update.intl.miui.com/sts?d=sg",
        "https://in-unlock.update.intl.miui.com/sts?d=in",
        "https://ru-unlock.update.intl.miui.com/sts?d=ru",
        "https://eu-unlock.update.intl.miui.com/sts?d=eu"
    )
    officialServiceUrls.forEach { serviceUrl ->
        assertTrue(
            MiAccountSecurityPolicy.isAllowedUnlockServiceUrl(serviceUrl),
            "official regional unlockApi /sts URL must be allowed: $serviceUrl"
        )
    }
    assertFalse(
        MiAccountSecurityPolicy.isAllowedUnlockServiceUrl("https://unlock.update.miui.com/other"),
        "unexpected unlock service path must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedUnlockServiceUrl("https://evil.unlock.update.miui.com/sts"),
        "unlock service subdomain confusion must be blocked"
    )
    assertFalse(
        MiAccountSecurityPolicy.isAllowedUnlockServiceUrl("https://eu-unlock.update.intl.miui.com:444/sts"),
        "unexpected unlock service port must be blocked"
    )

    val base = url("https://account.xiaomi.com/pass/serviceLogin")
    assertEquals(
        "https://account.xiaomi.com/pass/next",
        MiAccountSecurityPolicy.resolveAllowedRedirect(base, "next").toString(),
        "relative redirects must resolve inside the allowlist"
    )
    assertThrows("redirect to an unrelated host must fail") {
        MiAccountSecurityPolicy.resolveAllowedRedirect(base, "https://evil.example/steal")
    }
    assertThrows("redirect downgrade to HTTP must fail") {
        MiAccountSecurityPolicy.resolveAllowedRedirect(base, "http://account.xiaomi.com/pass")
    }

    val authRedirect = MiAccountSecurityPolicy.resolveAllowedAuthRedirect(
        base,
        "https://unlock.update.miui.com/sts?d=token"
    )
    assertEquals(
        "https://unlock.update.miui.com/sts?d=token",
        authRedirect.toString(),
        "bounded account-to-unlockApi redirect must be allowed"
    )
    assertThrows("unexpected unlockApi redirect path must fail") {
        MiAccountSecurityPolicy.resolveAllowedAuthRedirect(
            base,
            "https://unlock.update.miui.com/not-sts"
        )
    }

    val signed = MiAccountSecurityPolicy.appendQueryParameter(
        "https://account.xiaomi.com/pass/auth?sid=unlockApi",
        "clientSign",
        "a+b/c="
    )
    assertTrue(signed.contains("clientSign=a%2Bb%2Fc%3D"), "clientSign must be encoded")
    assertThrows("signed service location outside allowlist must fail") {
        MiAccountSecurityPolicy.appendQueryParameter(
            "https://evil.example/pass/auth",
            "clientSign",
            "secret"
        )
    }

    val signedUnlockService = MiAccountSecurityPolicy.appendQueryParameter(
        "https://eu-unlock.update.intl.miui.com/sts?d=token",
        "clientSign",
        "a+b/c="
    )
    assertTrue(
        signedUnlockService.contains("&clientSign=a%2Bb%2Fc%3D"),
        "official unlockApi service location must accept encoded clientSign"
    )
    assertThrows("unexpected unlockApi service path must fail") {
        MiAccountSecurityPolicy.appendQueryParameter(
            "https://unlock.update.miui.com/other?d=token",
            "clientSign",
            "secret"
        )
    }

    val jar = MiAccountCookieJar(
        url("https://account.xiaomi.com/pass/serviceLogin"),
        linkedMapOf("passToken" to "initial-token", "userId" to "42")
    )
    val initialHeader = jar.headerFor(url("https://account.xiaomi.com/pass/serviceLogin"))
    assertTrue(initialHeader.contains("passToken=initial-token"), "initial host cookie must be sent")
    assertFalse(
        jar.headerFor(url("https://api.account.xiaomi.com/pass/configuration")).contains("passToken="),
        "host-only initial cookie must not leak to another subdomain"
    )

    val unlockUrl = url("https://unlock.update.miui.com/sts?d=token")
    assertFalse(
        jar.headerFor(unlockUrl).contains("passToken="),
        "account passToken must never be sent to unlock.update.miui.com"
    )
    jar.capture(
        unlockUrl,
        mapOf(
            "Set-Cookie" to listOf(
                "serviceToken=unlock-secret; Domain=.miui.com; Path=/; Secure; HttpOnly",
                "unlockApi_slh=slh-value; Domain=.miui.com; Path=/; Secure",
                "unlockApi_ph=ph-value; Path=/; Secure",
                "userId=42; Domain=.miui.com; Path=/; Secure",
                "unexpected=broad; Domain=.miui.com; Path=/; Secure",
                "evil=bad; Domain=.example.com; Path=/; Secure"
            )
        )
    )
    val unlockHeader = jar.headerFor(unlockUrl)
    assertTrue(unlockHeader.contains("serviceToken=unlock-secret"), "serviceToken must follow official /sts scope")
    assertTrue(unlockHeader.contains("unlockApi_slh=slh-value"), "unlockApi_slh must follow official /sts scope")
    assertTrue(unlockHeader.contains("unlockApi_ph=ph-value"), "host-only unlockApi_ph must be retained")
    assertFalse(unlockHeader.contains("unexpected="), "unknown broad .miui.com cookie must be rejected")
    assertFalse(unlockHeader.contains("evil="), "unrelated unlock cookie domain must be rejected")

    val serviceEntries = jar.serviceEntries().toMap()
    assertEquals("unlock-secret", serviceEntries["serviceToken"], "serviceToken must be exported")
    assertEquals("slh-value", serviceEntries["unlockApi_slh"], "unlockApi_slh must be exported")
    assertEquals("ph-value", serviceEntries["unlockApi_ph"], "unlockApi_ph must be exported")
    assertEquals("42", serviceEntries["userId"], "userId must be exported")
    assertFalse(serviceEntries.containsKey("passToken"), "passToken must not be exported as a service cookie")

    jar.capture(
        url("https://account.xiaomi.com/pass/serviceLogin"),
        mapOf(
            "Set-Cookie" to listOf(
                "serviceToken=service-secret; Domain=.xiaomi.com; Path=/; Secure; HttpOnly",
                "unlockApi_session=abc; Domain=account.xiaomi.com; Path=/pass; Secure",
                "ignored=bad; Domain=evil.example; Path=/; Secure",
                "newline=bad\nvalue; Domain=.xiaomi.com; Path=/; Secure"
            )
        )
    )
    val apiHeader = jar.headerFor(url("https://api.account.xiaomi.com/pass/configuration"))
    assertTrue(apiHeader.contains("serviceToken=service-secret"), "valid parent-domain cookie must follow scope")
    assertFalse(apiHeader.contains("ignored="), "unrelated Domain cookie must be ignored")
    assertFalse(apiHeader.contains("newline="), "cookie header injection must be ignored")

    val passHeader = jar.headerFor(url("https://account.xiaomi.com/pass/step"))
    assertTrue(passHeader.contains("unlockApi_session=abc"), "matching cookie path must be sent")
    val otherHeader = jar.headerFor(url("https://account.xiaomi.com/other"))
    assertFalse(otherHeader.contains("unlockApi_session="), "non-matching cookie path must be withheld")

    jar.capture(
        url("https://account.xiaomi.com/pass/serviceLogin"),
        mapOf("set-cookie" to listOf("serviceToken=gone; Domain=.xiaomi.com; Path=/; Max-Age=0; Secure"))
    )
    assertFalse(
        jar.headerFor(url("https://api.account.xiaomi.com/pass/configuration")).contains("serviceToken="),
        "Max-Age=0 must delete the scoped cookie"
    )

    println("MiAccountSecurityPolicyTest: PASS")
}
