package com.naomi.hoshimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE mediaId = :mediaId")
    suspend fun getLyrics(mediaId: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE mediaId = :mediaId")
    suspend fun deleteLyrics(mediaId: String)
}
