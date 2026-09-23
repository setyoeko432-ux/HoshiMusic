package com.naomi.hoshimusic.util

expect object PlatformUtils {
    fun requestAudioPermission(): Boolean
    fun requestBluetoothPermission(): Boolean
    fun pickMusicFolder(): String?
    fun createDocument(mimeType: String): String?
    fun enableEdgeToEdge()
    fun showNotification(title: String, content: String)
    fun vibrate()
}
