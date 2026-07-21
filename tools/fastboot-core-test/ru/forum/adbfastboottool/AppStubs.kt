@file:Suppress("UNUSED_PARAMETER")

package ru.forum.adbfastboottool
import java.io.File
object HashUtils { fun verifyFileWithSidecars(file:File, onLog:(String)->Unit):Boolean=true }
object UsbDeviceInspector { fun summarizeDevice(device: android.hardware.usb.UsbDevice):String="fake" }
