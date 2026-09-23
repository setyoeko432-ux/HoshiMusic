package com.naomi.hoshimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationOverrideDao {
    @Query("SELECT * FROM translation_overrides WHERE mediaId = :mediaId")
    suspend fun getOverridesForSong(mediaId: String): List<TranslationOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOverride(override: TranslationOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOverrides(overrides: List<TranslationOverrideEntity>)

    @Query("DELETE FROM translation_overrides WHERE mediaId = :mediaId AND timestampMs = :timestampMs")
    suspend fun deleteOverride(mediaId: String, timestampMs: Long)

    @Query("DELETE FROM translation_overrides WHERE mediaId = :mediaId")
    suspend fun clearOverridesForSong(mediaId: String)
}
