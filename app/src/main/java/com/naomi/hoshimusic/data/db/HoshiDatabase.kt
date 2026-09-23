package com.naomi.hoshimusic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, LyricsCacheEntity::class, TranslationOverrideEntity::class], 
    version = 12, 
    exportSchema = true
)
abstract class HoshiDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun translationOverrideDao(): TranslationOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: HoshiDatabase? = null

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN metadataScanStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE songs ADD COLUMN metadataFailureReason TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN metadataScannedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Bridge the gap from V10 (Standard Player) to V11 (Workbench support)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `translation_overrides` (
                        `mediaId` TEXT NOT NULL, 
                        `timestampMs` INTEGER NOT NULL, 
                        `correctedText` TEXT NOT NULL, 
                        `lastUpdatedMs` INTEGER NOT NULL, 
                        PRIMARY KEY(`mediaId`, `timestampMs`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN container TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN codec TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN detectionSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE songs ADD COLUMN isFormatVerified INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN detectedLanguage TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE songs ADD COLUMN romanizedTitle TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN romanizedArtist TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN languageOverride TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN analyzedGainDb REAL")
                db.execSQL("ALTER TABLE songs ADD COLUMN analyzedPeakDb REAL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tambah kolom status analisis loudness dengan default 'PENDING'
                db.execSQL("ALTER TABLE songs ADD COLUMN analysisStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add indices for performance optimization
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_overrides_mediaId ON translation_overrides(mediaId)")
            }
        }

        fun getDatabase(context: Context): HoshiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HoshiDatabase::class.java,
                    "hoshi_music_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                // Hapus destructive migration untuk melindungi statistik pengguna
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
