package com.naomi.hoshimusic.data

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.naomi.hoshimusic.data.db.HoshiDatabase
import com.naomi.hoshimusic.data.repository.MediaStoreSongSource
import com.naomi.hoshimusic.worker.LoudnessScanWorker
import com.naomi.hoshimusic.worker.MetadataWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.util.concurrent.TimeUnit

class MusicRepository(private val context: Context) {

    private val database = HoshiDatabase.getDatabase(context)
    private val musicDao = database.musicDao()
    private val mediaStoreSource = MediaStoreSongSource(context)

    val allSongs: Flow<List<Song>> = musicDao.getAllSongs().map { entities ->
        entities.map(MediaMapper::mapToDomain)
    }

    suspend fun loadSongs(): LibraryScanResult = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) return@withContext LibraryScanResult.PermissionRequired
        val prefs = UserPreferences(context)
        val folderUriStr = prefs.musicFolderUri.firstOrNull()

        val result = runCatching {
            LibraryScanResult.Success(mediaStoreSource.queryMediaStore(folderUriStr), 0)
        }.getOrElse {
            LibraryScanResult.Error(it.message ?: "Gagal memindai koleksi musik.")
        }

        if (result is LibraryScanResult.Success) {
            val migrationVersion = prefs.mediaIdMigrationVersion.firstOrNull() ?: 0
            val needsMigration = migrationVersion < CURRENT_MEDIA_ID_VERSION
            var mapping: Map<String, String> = emptyMap()
            database.withTransaction {
                if (needsMigration) mapping = migrateLegacyMediaIds(result.songs)
                val songs = result.songs
                val existingById = musicDao.getAllSongsOnce().associateBy { it.mediaId }
                val entities = songs.map { song ->
                    MediaMapper.mergeWithExisting(song, existingById[song.stableId])
                }

                musicDao.insertSongs(entities)
                musicDao.deleteStaleBatched(entities.map { it.mediaId })
            }
            if (needsMigration) {
                if (mapping.isNotEmpty()) prefs.remapMediaIds(mapping)
                prefs.setMediaIdMigrationVersion(CURRENT_MEDIA_ID_VERSION)
            }
            scheduleDeepScan()
        }

        result
    }

    private suspend fun migrateLegacyMediaIds(fresh: List<Song>): Map<String, String> {
        val existing = musicDao.getAllSongsOnce()
        if (existing.isEmpty()) return emptyMap()
        val freshByPhysical = fresh.associateBy { it.displayName to it.fileSize }
        val mapping = mutableMapOf<String, String>()
        existing.forEach { old ->
            val match = freshByPhysical[old.displayName to old.fileSize] ?: return@forEach
            if (old.mediaId != match.stableId) {
                musicDao.remapSongMediaId(old.mediaId, match.stableId)
                mapping[old.mediaId] = match.stableId
            }
        }
        return mapping
    }

    private fun hasAudioPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    suspend fun recordPlay(mediaId: String) = withContext(Dispatchers.IO) {
        musicDao.recordPlay(mediaId, System.currentTimeMillis())
    }

    suspend fun getPendingAnalysisBatch(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        musicDao.getPendingAnalysisBatch(limit).map(MediaMapper::mapToDomain)
    }

    suspend fun updateAnalysis(mediaId: String, gainDb: Float, peak: Float, status: String) = withContext(Dispatchers.IO) {
        musicDao.updateAnalysis(mediaId, gainDb, peak, status)
    }

    private fun scheduleDeepScan() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val metaRequest = OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        val loudnessConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val loudnessRequest = OneTimeWorkRequestBuilder<LoudnessScanWorker>()
            .setConstraints(loudnessConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_DEEP_SCAN_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            metaRequest
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_LOUDNESS_SCAN_WORK,
            ExistingWorkPolicy.KEEP,
            loudnessRequest
        )
    }

    fun scheduleLoudnessScan() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<LoudnessScanWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_LOUDNESS_SCAN_WORK,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private companion object {
        const val UNIQUE_DEEP_SCAN_WORK = "metadata_deep_scan"
        const val UNIQUE_LOUDNESS_SCAN_WORK = "loudness_scan"
        const val CURRENT_MEDIA_ID_VERSION = 2
    }
}
