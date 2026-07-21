package org.json

class JSONObject {
    private val values = linkedMapOf<String, Any?>()

    constructor()

    constructor(@Suppress("UNUSED_PARAMETER") raw: String)

    fun put(key: String, value: Any?): JSONObject {
        values[key] = value
        return this
    }

    fun optString(key: String): String = optString(key, "")

    fun optString(key: String, fallback: String): String =
        values[key]?.toString() ?: fallback

    fun optInt(key: String, fallback: Int): Int = when (val value = values[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    fun has(key: String): Boolean = values.containsKey(key)

    override fun toString(): String = values.toString()
}
