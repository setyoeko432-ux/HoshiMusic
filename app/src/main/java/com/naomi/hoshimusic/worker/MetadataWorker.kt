package com.naomi.hoshimusic.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.core.content.edit
import com.naomi.hoshimusic.data.AudioMetadataReader
import com.naomi.hoshimusic.data.MediaMapper
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.naomi.hoshimusic.data.db.HoshiDatabase
import com.naomi.hoshimusic.data.metadata.ScanStatus
import com.naomi.hoshimusic.data.metadata.TagEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Orchestrator: Background deep scanning (Phase 5)
 *
 * Memproses lagu yang belum di-deep-scan secara bertahap (batch kecil),
 * dan menyerahkan sisa antrean ke run berikutnya bila waktu hampir habis.
 *
 * [A6] Retry semantics:
 *  - TRANSIENT failures (IO hiccup, sleeping storage) leave lastScannedMs
 *    untouched → the song stays queued and WorkManager backoff retries it.
 *  - PERMANENT failures are stamped done so corrupt files can never churn
 *    the queue or starve fresh additions.
 *  - Each invocation attempts every queued song at most once (in-memory
 *    `attempted` set), so a failing file cannot hot-loop inside a run.
 *
 * [D13] fileSize/duration are passed down so the reader can compute the
 * true average bitrate instead of trusting VBR container hints.
 */
class MetadataWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val database = HoshiDatabase.getDatabase(context)
    private val musicDao = database.musicDao()
    private val aiTagsDao = database.aiTagsDao()
    private val reader = AudioMetadataReader(context)
    private val classifierEngine = com.naomi.hoshimusic.data.metadata.AudioClassifierEngine(context)
    private val userPreferences = com.naomi.hoshimusic.data.UserPreferences(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // [V31]: Force metadata re-scan if TagEngine version increased
        val prefs = applicationContext.getSharedPreferences("hoshi_prefs", Context.MODE_PRIVATE)
        val lastTagVer = prefs.getInt("tag_engine_ver", 0)
        if (lastTagVer < TagEngine.VERSION) {
            musicDao.resetMetadata()
            prefs.edit { putInt("tag_engine_ver", TagEngine.VERSION) }
        }

        // [Fase 6] Cek migrasi romanizer
        val lastVer = prefs.getInt("romanizer_ver", 0)
        if (lastVer < com.naomi.hoshimusic.util.HoshiRomanizer.VERSION) {
            musicDao.resetRomanization()
            prefs.edit { putInt("romanizer_ver", com.naomi.hoshimusic.util.HoshiRomanizer.VERSION) }
        }

        if (runAttemptCount >= MAX_TOTAL_ATTEMPTS) {
            Log.w(TAG, "Menyerah setelah $runAttemptCount percobaan. Tandai selesai.")
            return@withContext Result.success()
        }

        val deadline = System.currentTimeMillis() + MAX_RUNTIME_MS
        val attempted = HashSet<String>()
        var transientFailures = false
        val semaphore = Semaphore(4) // Phase 2: Parallel limit

        while (System.currentTimeMillis() < deadline) {
            val batch = musicDao.getUnscannedSongs().filter { it.mediaId !in attempted }
            if (batch.isEmpty()) break

            val results = coroutineScope {
                batch.map { entity ->
                    attempted += entity.mediaId
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val deep = reader.readDeepMetadata(
                                    uri = MediaMapper.resolveContentUri(entity),
                                    mimeType = entity.mimeType,
                                    absolutePath = entity.absolutePath,
                                    fileSizeBytes = entity.fileSize.takeIf { it > 0 },
                                    durationMsHint = entity.durationMs.takeIf { it > 0 }
                                )

                                val domain = MediaMapper.mapToDomain(entity)
                                val updatedDomain = domain.copy(
                                    bitrate = deep.bitrate ?: domain.bitrate,
                                    sampleRate = deep.sampleRate ?: domain.sampleRate,
                                    bitDepth = deep.bitDepth ?: domain.bitDepth,
                                    channels = deep.channels ?: domain.channels,
                                    format = deep.format ?: domain.format,
                                    container = deep.container ?: domain.container,
                                    codec = deep.codec ?: domain.codec,
                                    detectionSource = deep.detectionSource,
                                    isFormatVerified = deep.isFormatVerified,
                                    metadataScanStatus = deep.metadataScanStatus,
                                    metadataFailureReason = deep.metadataFailureReason,
                                    metadataScannedAt = deep.metadataScannedAt,
                                    isrc = deep.isrc ?: domain.isrc,
                                    recordLabel = deep.recordLabel ?: domain.recordLabel,
                                    composer = deep.composer ?: domain.composer,
                                    year = deep.year ?: domain.year,
                                    bpm = deep.bpm ?: domain.bpm,
                                    embeddedLyrics = deep.lyrics ?: domain.embeddedLyrics,
                                    hasLyrics = deep.hasLyrics,
                                    lyricsSource = deep.lyricsSource,
                                    replayGainTrack = deep.replayGainTrack ?: domain.replayGainTrack,
                                    replayGainAlbum = deep.replayGainAlbum ?: domain.replayGainAlbum
                                )

                                // Phase 4: Deferred artwork (removed enqueue pre-cache)

                                var scanned = MediaMapper.mergeWithExisting(updatedDomain, entity)
                                scanned = MediaMapper.repairTexts(scanned)

                                if (entity.languageOverride == null) {
                                    scanned = MediaMapper.processMetadata(scanned)
                                }

                                // [P3]: Smart AI Tagging (Non-Realtime / Background scan)
                                try {
                                    val aiEnabled = userPreferences.aiTaggingEnabled.firstOrNull() ?: true
                                    if (aiEnabled && entity.absolutePath != null) {
                                        val file = java.io.File(entity.absolutePath)
                                        val aiRes = classifierEngine.classifyAudioFile(file)
                                        aiTagsDao.insertOrUpdateTags(
                                            com.naomi.hoshimusic.data.db.SongAiTags(
                                                songMediaId = entity.mediaId,
                                                predictedGenre = aiRes.predictedGenre,
                                                genreConfidence = aiRes.confidence,
                                                bpmDetected = aiRes.bpm,
                                                keyDetected = aiRes.key,
                                                analysisStatus = aiRes.status,
                                                analyzedAtMs = System.currentTimeMillis(),
                                                isOffline = true
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "AI tagging skipped for ${entity.mediaId}: ${e.message}")
                                }

                                when (deep.status) {
                                    ScanStatus.TRANSIENT_FAILURE -> {
                                        transientFailures = true
                                        null
                                    }
                                    else -> scanned.copy(lastScannedMs = System.currentTimeMillis())
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Parallel read failed for ${entity.mediaId}: ${e.message}")
                                transientFailures = true
                                null
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()

            if (results.isNotEmpty()) musicDao.insertSongs(results)

            if (batch.size < BATCH_SIZE) break
        }

        // Lanjutkan via WorkManager backoff hanya bila ada pekerjaan nyata:
        // item baru di luar percobaan ini, atau kegagalan sementara yang
        // layak dicoba ulang. Kegagalan permanen TIDAK memicu retry.
        val hasFreshQueued = musicDao.getUnscannedSongs().any { it.mediaId !in attempted }
        return@withContext when {
            hasFreshQueued -> Result.retry()
            transientFailures && runAttemptCount < MAX_TRANSIENT_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "HoshiMeta"
        const val BATCH_SIZE = 50 // samakan dengan LIMIT di getUnscannedSongs()

        // Margin aman dari batas eksekusi ~10 menit WorkManager
        val MAX_RUNTIME_MS = TimeUnit.MINUTES.toMillis(8)
        
        const val MAX_TRANSIENT_ATTEMPTS = 3
        const val MAX_TOTAL_ATTEMPTS = 8
    }
}
