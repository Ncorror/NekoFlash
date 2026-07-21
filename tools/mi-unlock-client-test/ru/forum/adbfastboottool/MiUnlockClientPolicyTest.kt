package ru.forum.adbfastboottool

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

private fun requirePolicy(condition: Boolean, message: String) {
    if (!condition) error(message)
}

fun main() {
    val ssecurity = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })
    val client = MiUnlockClient(
        host = "https://example.invalid",
        ssecurity = ssecurity,
        serviceCookies = "serviceToken=fake",
        userId = "1",
        deviceId = "test-device"
    )

    val retryMethod = MiUnlockClient::class.java.getDeclaredMethod(
        "isRetryableReadFailure",
        Exception::class.java
    ).apply { isAccessible = true }

    fun retryable(error: Exception): Boolean = retryMethod.invoke(client, error) as Boolean

    requirePolicy(retryable(SocketTimeoutException()), "SocketTimeoutException must be retryable")
    requirePolicy(retryable(ConnectException()), "ConnectException must be retryable")
    requirePolicy(retryable(UnknownHostException()), "UnknownHostException must be retryable")
    requirePolicy(retryable(NoRouteToHostException()), "NoRouteToHostException must be retryable")
    requirePolicy(!retryable(IOException("business/non-transient")), "Generic IOException must not be retryable")

    val methodNames = MiUnlockClient::class.java.declaredMethods.map { it.name }.toSet()
    requirePolicy("sendReadWithRetry" in methodNames, "Scoped read retry helper is missing")
    requirePolicy("sendWithRetry" !in methodNames, "Legacy blanket retry helper returned")

    println("MI UNLOCK CLIENT POLICY TESTS: OK")
}
