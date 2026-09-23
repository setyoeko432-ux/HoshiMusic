package com.naomi.hoshimusic.util

actual object PlatformUtils {
    actual fun requestAudioPermission(): Boolean = true
    actual fun requestBluetoothPermission(): Boolean = true
    actual fun pickMusicFolder(): String? = null
    actual fun createDocument(mimeType: String): String? = null
    actual fun enableEdgeToEdge() {}
    actual fun showNotification(title: String, content: String) {}
    actual fun vibrate() {}
}
