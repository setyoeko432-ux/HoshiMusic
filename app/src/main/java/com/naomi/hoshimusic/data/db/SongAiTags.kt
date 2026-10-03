package com.naomi.hoshimusic.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [P3]: Room entity untuk menyimpan hasil klasifikasi AI/TFLite non-real-time (Genre, BPM, Key).
 * Terhubung ke SongEntity via foreign key.
 */
@Entity(
    tableName = "song_ai_tags",
    indices = [Index(value = ["songMediaId"])],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["songMediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SongAiTags(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songMediaId: String,
    val predictedGenre: String? = null,
    val genreConfidence: Float? = null,
    val bpmDetected: Int? = null,
    val keyDetected: String? = null,
    val modelVersion: String? = "1.0.0",
    val analysisStatus: String = "PENDING", // PENDING, SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE
    val analyzedAtMs: Long = 0L,
    val isOffline: Boolean = true
)
