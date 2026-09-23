package com.naomi.hoshimusic.playback.route

/**
 * Pure function yang menentukan apakah BT Clarity Guard harus aktif.
 */
object BtClarityPolicy {
    fun shouldEngage(
        isSpeakerLike: Boolean,
        isSbcOrUnknown: Boolean
    ): Boolean {
        // Berdasarkan verifikasi: SBC merusak koherensi 200-500 Hz pada speaker bluetooth.
        return isSpeakerLike && isSbcOrUnknown
    }
}
