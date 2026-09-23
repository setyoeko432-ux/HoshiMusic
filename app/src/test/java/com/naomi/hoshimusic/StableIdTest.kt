package com.naomi.hoshimusic

import android.net.Uri
import com.naomi.hoshimusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StableIdTest {

    private fun createSong(
        id: Long,
        relativePath: String,
        displayName: String,
        fileSize: Long,
        volumeName: String = "external_primary"
    ) = Song(
        id = id,
        albumId = 0L,
        title = "",
        artist = "",
        albumArtist = "",
        album = "",
        genre = "",
        durationMs = 0L,
        contentUri = Uri.parse("content://media/external/audio/media/$id"),
        artworkUri = null,
        folder = "",
        relativePath = relativePath,
        absolutePath = null,
        displayName = displayName,
        volumeName = volumeName,
        trackNumber = 0,
        dateAddedMs = 0L,
        mimeType = "audio/mpeg",
        bitrate = null,
        sampleRate = null,
        bitDepth = null,
        format = null,
        fileSize = fileSize
    )

    @Test
    fun `stableId unik untuk file berbeda di folder yang sama`() {
        // Dua Song dengan relativePath "Download/1DMP/" yang sama
        // namun displayName berbeda harus menghasilkan stableId berbeda.
        val song1 = createSong(1L, "Download/1DMP/", "pinKing - HOYO-MiX, Sān-Z.flac", 40610515L)
        val song2 = createSong(2L, "Download/1DMP/", "other.flac", 1000L)
        
        assertNotEquals(song1.stableId, song2.stableId)
    }

    @Test
    fun `stableId cocok dengan data perangkat nyata`() {
        // relativePath "Download/1DMP/", displayName "pinKing - HOYO-MiX, Sān-Z.flac",
        // volumeName "external_primary"
        // harus menghasilkan
        // "external_primary:Download/1DMP/pinKing - HOYO-MiX, Sān-Z.flac"
        val song = createSong(1L, "Download/1DMP/", "pinKing - HOYO-MiX, Sān-Z.flac", 40610515L)
        
        assertEquals("external_primary:Download/1DMP/pinKing - HOYO-MiX, Sān-Z.flac", song.stableId)
    }
}
