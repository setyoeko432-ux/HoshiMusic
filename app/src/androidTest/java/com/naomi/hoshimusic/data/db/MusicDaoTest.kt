package com.naomi.hoshimusic.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicDaoTest {
    private lateinit var database: HoshiDatabase
    private lateinit var dao: MusicDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HoshiDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.musicDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createSong(id: String) = SongEntity(
        mediaId = id,
        mediaStoreId = id.hashCode().toLong(),
        volumeName = "external",
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        durationMs = 180000,
        relativePath = "Music/",
        absolutePath = "/storage/emulated/0/Music/$id.mp3",
        albumId = 1L,
        artworkUri = null,
        albumArtist = "Artist",
        genre = "Pop",
        displayName = "$id.mp3",
        trackNumber = 1,
        dateAddedMs = System.currentTimeMillis(),
        fileSize = 5000000L,
        mimeType = "audio/mpeg"
    )

    @Test
    fun deleteSongsByIds_removesOnlyTargetedSongs() = runBlocking {
        val songs = listOf(createSong("1"), createSong("2"), createSong("3"))
        dao.insertSongs(songs)

        dao.deleteSongsByIds(listOf("1", "2"))

        val remaining = dao.getAllSongsOnce()
        assertEquals(1, remaining.size)
        assertEquals("3", remaining[0].mediaId)
    }

    @Test
    fun deleteStaleBatched_removesOnlyStaleSongs() = runBlocking {
        val songs = (1..10).map { createSong(it.toString()) }
        dao.insertSongs(songs)

        // "1" to "5" are active, "6" to "10" are stale
        val activeIds = (1..5).map { it.toString() }
        dao.deleteStaleBatched(activeIds)

        val remaining = dao.getAllSongsOnce()
        assertEquals(5, remaining.size)
        val remainingIds = remaining.map { it.mediaId }.toSet()
        activeIds.forEach { id ->
            assertTrue("Song $id should still exist", remainingIds.contains(id))
        }
    }

    @Test
    fun deleteStaleBatched_withEmptyList_doesNothing() = runBlocking {
        val songs = listOf(createSong("1"), createSong("2"))
        dao.insertSongs(songs)

        dao.deleteStaleBatched(emptyList())

        val remaining = dao.getAllSongsOnce()
        assertEquals(2, remaining.size)
    }

    @Test
    fun deleteStaleBatched_handlesLargeBatch() = runBlocking {
        val count = 1200
        val songs = (1..count).map { createSong(it.toString()) }
        dao.insertSongs(songs)

        // Keep 200, delete 1000 (2 batches of 500)
        val activeIds = (1..200).map { it.toString() }
        dao.deleteStaleBatched(activeIds)

        val remaining = dao.getAllSongsOnce()
        assertEquals(200, remaining.size)
        val remainingIds = remaining.map { it.mediaId }.toSet()
        activeIds.forEach { id ->
            assertTrue("Song $id should still exist", remainingIds.contains(id))
        }
    }
}
