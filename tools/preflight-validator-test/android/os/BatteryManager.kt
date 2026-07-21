package android.os

open class BatteryManager(private val capacity: Int = Int.MIN_VALUE) {
    open fun getIntProperty(id: Int): Int = capacity

    companion object {
        const val BATTERY_PROPERTY_CAPACITY: Int = 4
        const val EXTRA_LEVEL: String = "level"
        const val EXTRA_SCALE: String = "scale"
    }
}
