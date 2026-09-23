package com.naomi.hoshimusic.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val mediaId: String,
    val linesJson: String, // Simpan baris lirik sebagai JSON string
    val plainText: String,
    val sourceName: String?,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)
