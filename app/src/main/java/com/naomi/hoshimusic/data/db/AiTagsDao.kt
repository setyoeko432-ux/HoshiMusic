package com.naomi.hoshimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AiTagsDao {
    @Query("SELECT * FROM song_ai_tags WHERE songMediaId = :mediaId LIMIT 1")
    suspend fun getTagsForSong(mediaId: String): SongAiTags?

    @Query("SELECT * FROM song_ai_tags WHERE analysisStatus = 'PENDING' OR analysisStatus = 'TRANSIENT_FAILURE' LIMIT :limit")
    suspend fun getPendingAiAnalysisBatch(limit: Int): List<SongAiTags>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTags(tags: SongAiTags)

    @Query("UPDATE song_ai_tags SET analysisStatus = :status WHERE songMediaId = :mediaId")
    suspend fun updateStatus(mediaId: String, status: String)

    @Query("DELETE FROM song_ai_tags WHERE songMediaId = :mediaId")
    suspend fun deleteTagsForSong(mediaId: String)
}
