# Keep only the reflection/JNI surfaces that are known to require stable names.
# AndroidX and Material publish consumer rules; keeping their entire namespaces
# defeats R8 minification and resource shrinking.

# USB framework classes are referenced by Android and some vendor stacks.
-keep class android.hardware.usb.** { *; }

# Canonical JNI protection plus an explicit guard for our native backend.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class ru.forum.adbfastboottool.NativeUsbfsBackend {
    native <methods>;
}

# Preserve class names in stack traces for transport/safety code without
# keeping all method bodies from shrinking.
-keepnames class ru.forum.adbfastboottool.**
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
