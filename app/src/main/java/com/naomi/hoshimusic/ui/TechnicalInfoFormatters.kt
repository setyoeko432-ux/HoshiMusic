package com.naomi.hoshimusic.ui

import com.naomi.hoshimusic.playback.route.RouteType

internal object TechnicalInfoFormatters {

    fun cleanDeviceName(name: String, routeType: RouteType): String {
        val prefixes = listOf("audio", "headset", "headphone", "speaker", "wired", "accessory", "output")
        
        var cleaned = name
        
        if (routeType == RouteType.BLUETOOTH) {
            cleaned = cleaned.replace("Bluetooth", "", ignoreCase = true)
        } else if (routeType == RouteType.USB) {
            cleaned = cleaned.replace("USB", "", ignoreCase = true)
                .replace("DAC", "", ignoreCase = true)
        }
        
        cleaned = cleaned.replace("•", "").replace(":", "").replace("-", "").trim()
        
        prefixes.forEach { prefix ->
            if (cleaned.lowercase().startsWith(prefix) && cleaned.length > prefix.length + 2) {
                cleaned = cleaned.substring(prefix.length).trim().removePrefix("-").removePrefix(":").trim()
            }
        }

        return when {
            routeType == RouteType.HEADPHONES -> cleaned.ifBlank { "Jack 3.5mm" }
            routeType == RouteType.SPEAKER -> cleaned.ifBlank { "Speaker Internal" }
            routeType == RouteType.BLUETOOTH -> cleaned.ifBlank { "Perangkat Bluetooth" }
            routeType == RouteType.USB -> cleaned.ifBlank { "External DAC" }
            else -> cleaned.ifBlank { name }
        }
    }

    fun formatKhz(hz: Int): String {
        val khz = hz / 1000f
        val formatted = if (khz % 1 == 0f) khz.toInt().toString() else khz.toString().replace(".", ",")
        return "$formatted kHz"
    }

    fun formatResolution(bitDepth: Int, sampleRate: Int): String {
        val bitStr = if (bitDepth > 0) "$bitDepth-bit" else "Belum tersedia"
        val khzStr = if (sampleRate > 0) formatKhz(sampleRate) else "Belum tersedia"
        
        return if (bitDepth > 0 && sampleRate > 0) "$bitStr / $khzStr" 
        else if (bitDepth > 0) bitStr 
        else khzStr
    }

    fun formatBitrate(bitrate: Int): String {
        return if (bitrate > 0) {
            val value = if (bitrate >= 1000) bitrate / 1000 else bitrate
            val unit = if (bitrate >= 1000) "kbps" else "bps"
            "$value $unit"
        } else "Belum tersedia"
    }

    fun formatRouteType(type: RouteType): String {
        return when (type) {
            RouteType.BLUETOOTH -> "Bluetooth"
            RouteType.USB -> "USB"
            RouteType.HEADPHONES -> "Kabel (Jack)"
            RouteType.SPEAKER -> "Internal"
            else -> "Tidak diketahui"
        }
    }

    fun formatMime(mime: String): String {
        if (mime.isBlank() || mime == "unknown") return "DATA"
        return mime.substringAfterLast("/").uppercase()
    }
}
