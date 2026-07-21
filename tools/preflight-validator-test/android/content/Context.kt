package android.content

open class Context {
    open fun getSystemService(name: String): Any? = null
    open fun registerReceiver(receiver: Any?, filter: IntentFilter): Intent? = null

    companion object {
        const val BATTERY_SERVICE: String = "battery"
    }
}
