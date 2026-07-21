package android.os

object Build {
    @JvmField var MANUFACTURER: String = "TestHost"
    @JvmField var MODEL: String = "TestModel"
    @JvmField var DEVICE: String = "test_device"

    object VERSION {
        @JvmField var SDK_INT: Int = 34
        @JvmField var RELEASE: String = "14"
    }

    object VERSION_CODES {
        const val P: Int = 28
    }
}
