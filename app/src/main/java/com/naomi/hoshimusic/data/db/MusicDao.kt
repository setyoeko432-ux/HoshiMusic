package com.naomi.hoshimusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsOnce(): List<SongEntity>

    /**
     * [A6] Queue predicate simplified to `lastScannedMs = 0`.
     *
     * The old `OR bitrate IS NULL` clause permanently re-selected every song
     * whose bitrate could honestly not be read, which (a) made
     * getUnscannedCount() permanently > 0 and drove endless Result.retry()
     * churn, and (b) let permanently-unreadable files crowd out fresh library
     * additions.
     * Re-scanning is still fully supported via the existing "scan ulang"
     * flow, which resets lastScannedMs = 0.
     */
    @Query("SELECT * FROM songs WHERE lastScannedMs = 0 LIMIT 50")
    suspend fun getUnscannedSongs(): List<SongEntity>

    @Query("UPDATE OR REPLACE songs SET mediaId = :newId WHERE mediaId = :oldId")
    suspend fun remapSongMediaId(oldId: String, newId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    /** Lagu yang belum dianalisis loudness-nya (tanpa hasil analyzer tersimpan). */
    @Query("SELECT * FROM songs WHERE analyzedGainDb IS NULL OR analysisStatus = 'PENDING' OR analysisStatus = 'TRANSIENT_FAILURE' LIMIT :limit")
    suspend fun getPendingAnalysisBatch(limit: Int): List<SongEntity>

    /** [Priority 5.7] Pagination support for full library scan. */
    @Query("SELECT * FROM songs WHERE mediaId > :lastId ORDER BY mediaId LIMIT :limit")
    suspend fun getAllSongsBatchCursor(lastId: String, limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs LIMIT :limit")
    suspend fun getAllSongsBatch(limit: Int): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs WHERE analyzedGainDb IS NULL OR analysisStatus = 'PENDING' OR analysisStatus = 'TRANSIENT_FAILURE'")
    suspend fun countPendingAnalysis(): Int

    @Query("UPDATE songs SET analyzedGainDb = :gainDb, analyzedPeakDb = :peak, analysisStatus = :status WHERE mediaId = :mediaId")
    suspend fun updateAnalysis(mediaId: String, gainDb: Float, peak: Float, status: String)

    @Query("UPDATE songs SET analysisStatus = :status WHERE mediaId = :mediaId")
    suspend fun updateAnalysisStatus(mediaId: String, status: String)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedAtMs = :timestamp WHERE mediaId = :mediaId")
    suspend fun recordPlay(mediaId: String, timestamp: Long)

    @Query("DELETE FROM songs WHERE mediaId IN (:staleIds)")
    suspend fun deleteSongsByIds(staleIds: List<String>)

    @Query("SELECT mediaId FROM songs")
    suspend fun getAllIds(): List<String>

    /** [Fase 6] Paksa rescan metadata untuk lagu non-Latin saat engine update */
    @Query("UPDATE songs SET lastScannedMs = 0, romanizedTitle = NULL, romanizedArtist = NULL WHERE detectedLanguage != 'LATIN'")
    suspend fun resetRomanization()

    /** [V31]: Paksa rescan total saat logika parser berubah */
    @Query("UPDATE songs SET lastScannedMs = 0")
    suspend fun resetMetadata()

    /**
     * Hapus lagu stale dengan pola IN batch untuk menghindari batas parameter SQLite.
     * Library besar (10.000+ lagu) akan dipecah menjadi beberapa query kecil.
     */
    @Transaction
    suspend fun deleteStaleBatched(activeIds: List<String>) {
        if (activeIds.isEmpty()) {
            // Hati-hati: activeIds kosong bisa berarti library tidak ditemukan
            // (storage sementara terpasang). JANGAN hapus semua.
            return
        }
        val existingIds = getAllIds()
        val existingSet = existingIds.toHashSet()
        val activeSet = activeIds.toHashSet()
        val staleIds = existingSet - activeSet
        
        if (existingSet.size >= 20 && activeSet.size < existingSet.size / 4) {
            android.util.Log.e(
                "HoshiDao",
                "Batal hapus stale: hanya ${activeSet.size} ID aktif terhadap " +
                "${existingSet.size} baris tersimpan. Dugaan scan gagal atau tabrakan ID."
            )
            return
        }

        if (staleIds.isEmpty()) return
        
        // Hapus dalam batch 500 ID per query untuk menghindari batas parameter SQLite
        staleIds.chunked(500).forEach { batch ->
            deleteSongsByIds(batch)
        }
    }
}
