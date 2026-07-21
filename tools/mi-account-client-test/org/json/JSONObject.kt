package org.json

class JSONObject {
    private val values = linkedMapOf<String, Any?>()

    constructor()
    constructor(@Suppress("UNUSED_PARAMETER") raw: String)

    fun put(key: String, value: Any?): JSONObject {
        values[key] = value
        return this
    }

    fun optString(key: String, fallback: String = ""): String = values[key]?.toString() ?: fallback

    fun optInt(key: String, fallback: Int): Int = when (val value = values[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    fun optLong(key: String, fallback: Long): Long = when (val value = values[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }

    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    fun keys(): Iterator<String> = values.keys.iterator()
}

class JSONArray {
    private val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values += value; return this }
    fun length(): Int = values.size
    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
}
