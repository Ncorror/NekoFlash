package android.content

open class Intent(private val extras: Map<String, Int> = emptyMap()) {
    open fun getIntExtra(name: String, defaultValue: Int): Int = extras[name] ?: defaultValue

    companion object {
        const val ACTION_BATTERY_CHANGED: String = "android.intent.action.BATTERY_CHANGED"
    }
}
