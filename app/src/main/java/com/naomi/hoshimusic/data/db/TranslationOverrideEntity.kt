package com.naomi.hoshimusic.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_overrides", 
    primaryKeys = ["mediaId", "timestampMs"],
    indices = [Index(value = ["mediaId"])]
)
data class TranslationOverrideEntity(
    val mediaId: String,
    val timestampMs: Long,
    val correctedText: String,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)
