package com.naomi.hoshimusic

import android.net.Uri
import com.naomi.hoshimusic.data.AudioFormatInfo
import com.naomi.hoshimusic.data.DetectionSource
import com.naomi.hoshimusic.data.MediaMapper
import com.naomi.hoshimusic.data.Song
import com.naomi.hoshimusic.data.db.SongEntity
import com.naomi.hoshimusic.data.metadata.MagicSniffer
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.util.QualityResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MetadataAccuracyTest {

    @Test
    fun testMagicSniffer_MP3() {
        val mp3Header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x44.toByte())
        val result = MagicSniffer.detect(mp3Header, mp3Header.size)
        assertNotNull(result)
        assertEquals("MP3", result?.container)
        assertEquals("MP3", result?.codec)
    }

    @Test
    fun testMagicSniffer_AAC() {
        val adtsHeader = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte())
        val result = MagicSniffer.detect(adtsHeader, adtsHeader.size)
        assertNotNull(result)
        assertEquals("AAC", result?.container)
    }

    @Test
    fun testMagicSniffer_FLAC() {
        val flacHeader = "fLaC".toByteArray()
        val result = MagicSniffer.detect(flacHeader, flacHeader.size)
        assertEquals("FLAC", result?.container)
    }

    @Test
    fun testMagicSniffer_WAV_RF64() {
        val rf64Header = ByteArray(12)
        System.arraycopy("RF64".toByteArray(), 0, rf64Header, 0, 4)
        System.arraycopy("WAVE".toByteArray(), 0, rf64Header, 8, 4)
        val result = MagicSniffer.detect(rf64Header, rf64Header.size)
        assertEquals("WAV", result?.container)
    }

    @Test
    fun testQualityResolver_HiRes() {
        val song = createBaseSong().copy(
            isFormatVerified = true,
            container = "FLAC",
            codec = "FLAC",
            sampleRate = 96000,
            bitDepth = 24,
            metadataScanStatus = ScanStatus.SUCCESS
        )
        assertEquals(QualityResolver.AudioQuality.HI_RES, QualityResolver.resolve(song))
    }

    @Test
    fun testQualityResolver_LosslessStandard() {
        val song = createBaseSong().copy(
            isFormatVerified = true,
            container = "FLAC",
            codec = "FLAC",
            sampleRate = 44100,
            bitDepth = 16,
            metadataScanStatus = ScanStatus.SUCCESS
        )
        assertEquals(QualityResolver.AudioQuality.LOSSLESS, QualityResolver.resolve(song))
    }

    @Test
    fun testMediaMapper_MergeVerified() {
        val existing = createBaseEntity().copy(
            isFormatVerified = false,
            format = "MPEG"
        )
        val fresh = createBaseSong().copy(
            isFormatVerified = true,
            container = "MP3",
            codec = "MP3",
            detectionSource = DetectionSource.MAGIC_BYTES
        )
        
        val merged = MediaMapper.mergeWithExisting(fresh, existing)
        assertTrue(merged.isFormatVerified)
        assertEquals("MP3", merged.codec)
    }

    private fun createBaseSong() = Song(
        id = 1L, albumId = 1L, title = "Title", artist = "Artist", albumArtist = "Artist",
        album = "Album", genre = "Genre", durationMs = 1000L, contentUri = Uri.EMPTY,
        artworkUri = null, folder = "folder", relativePath = "path", absolutePath = null,
        displayName = "file.mp3", volumeName = "external", trackNumber = 1,
        dateAddedMs = 0L, mimeType = "audio/mpeg", bitrate = 128, sampleRate = 44100,
        bitDepth = 16, format = "MP3", fileSize = 1024L
    )

    private fun createBaseEntity() = SongEntity(
        mediaId = "uri", mediaStoreId = 1L, volumeName = "external", title = "Title",
        artist = "Artist", album = "Album", durationMs = 1000L, relativePath = "path",
        absolutePath = null, albumId = 1L, artworkUri = null, albumArtist = "Artist",
        genre = "Genre", displayName = "file.mp3", trackNumber = 1, dateAddedMs = 0L,
        fileSize = 1024L, mimeType = "audio/mpeg"
    )
}
