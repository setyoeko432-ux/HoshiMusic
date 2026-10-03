package com.naomi.hoshimusic.ui

import com.naomi.hoshimusic.playback.route.RouteType

internal object TechnicalInfoFormatters {

    /**
     * Kata bawaan Android yang tidak membawa informasi nama perangkat, misalnya
     * "USB Audio", "Wired Headset", atau "Internal Speaker". Kalau seluruh sisa
     * nama hanya berisi kata-kata ini, device dianggap tidak teridentifikasi dan
     * label route dipakai sebagai gantinya.
     */
    private val GENERIC_TOKENS = setOf(
        "audio", "output", "device", "android", "unknown", "default",
        "wired", "accessory", "usb", "dac", "line", "analog", "dongle",
        "headset", "headphones", "headphone", "earphone", "earphones",
        "handset", "bt", "bluetooth", "ble", "speaker", "loudspeaker", "internal"
    )

    private val WHITESPACE = Regex("\\s+")

    private fun routeFallback(routeType: RouteType, original: String): String = when (routeType) {
        RouteType.HEADPHONES -> "Jack 3.5mm"
        RouteType.SPEAKER -> "Speaker Internal"
        RouteType.BLUETOOTH -> "Perangkat Bluetooth"
        RouteType.USB -> "External DAC"
        else -> original
    }

    fun cleanDeviceName(name: String, routeType: RouteType): String {
        var cleaned = name

        if (routeType == RouteType.BLUETOOTH) {
            cleaned = cleaned.replace("Bluetooth", "", ignoreCase = true)
        } else if (routeType == RouteType.USB) {
            cleaned = cleaned.replace("USB", "", ignoreCase = true)
                .replace("DAC", "", ignoreCase = true)
        }

        // Nama bawaan Android penuh separator ("Bluetooth - FiiO FW5",
        // "WH-1000XM4 Bluetooth Audio"). Bullet dan titik dua jadi spasi,
        // hyphen dihapus supaya nomor model tetap satu token, lalu spasi dirapikan
        cleaned = cleaned.replace("•", " ")
            .replace(":", " ")
            .replace("-", "")
            .replace(WHITESPACE, " ")
            .trim()

        // Kalau tidak ada satu pun kata yang khas, device tidak terbaca
        // ("USB Audio" -> "Audio", "Internal Speaker" -> tidak ada). Pakai label route.
        val hasIdentifiableWord = cleaned.split(' ')
            .any { it.isNotBlank() && it.lowercase() !in GENERIC_TOKENS }
        if (!hasIdentifiableWord) return routeFallback(routeType, name)

        // Buang kata generik di depan selama masih menyisakan isi.
        while (true) {
            val firstSpace = cleaned.indexOf(' ')
            val head = if (firstSpace == -1) cleaned else cleaned.substring(0, firstSpace)
            val tail = if (firstSpace == -1) "" else cleaned.substring(firstSpace + 1).trim()
            if (head.lowercase() !in GENERIC_TOKENS || tail.isEmpty()) break
            cleaned = tail
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
