package com.naomi.hoshimusic.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.naomi.hoshimusic.data.MusicRepository
import com.naomi.hoshimusic.data.UserPreferences
import com.naomi.hoshimusic.playback.util.LoudnessAnalyzer

class LoudnessScanWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository = MusicRepository(appContext)
    private val preferences = UserPreferences(appContext)

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.success()

        val songs = repository.getPendingAnalysisBatch(50)
        if (songs.isEmpty()) return Result.success()

        var success = 0
        var transient = 0
        var permanent = 0

        songs.forEach { song ->
            if (isStopped) return Result.retry()
            
            // Phase 3: Lazy loudness — sample only first 15 seconds for background scan
            val result = LoudnessAnalyzer.analyze(appContext, song.contentUri, song.mimeType, 15_000L)
            if (result != null) {
                val (gain, peak) = result
                repository.updateAnalysis(song.stableId, gain, peak, "SUCCESS")
                success++
            } else {
                repository.updateAnalysis(song.stableId, 0f, 0f, "PERMANENT_FAILURE")
                permanent++
            }
        }
        
        preferences.setLoudnessScanProgress(
            System.currentTimeMillis(),
            success,
            transient,
            permanent
        )

        return if (repository.getPendingAnalysisBatch(1).isNotEmpty()) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val TAG = "HoshiLoudness"
    }
}
