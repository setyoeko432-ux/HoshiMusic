package com.naomi.hoshimusic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * [D1] Centralized component for extracting artwork from audio files or image URIs.
 */
class EmbeddedArtworkLoader(private val context: Context) {

    private companion object {
        const val TAG = "HoshiArt"
        const val MAX_DIMENSION = 1024
    }

    suspend fun loadArtwork(uri: Uri, artworkData: ByteArray? = null): Bitmap? = withContext(Dispatchers.IO) {
        // 1. Try raw data if provided
        if (artworkData != null) {
            val bitmap = decodeSampledBitmap(artworkData)
            if (bitmap != null) return@withContext bitmap
        }

        // 2. Try as image URI first
        val fromImageUri = runCatching {
            context.contentResolver.openInputStream(uri)?.use { decodeSampledBitmap(it) }
        }.getOrNull()
        if (fromImageUri != null) return@withContext fromImageUri

        // 3. Fallback to embedded picture in audio
        return@withContext extractEmbedded(uri)
    }

    private fun extractEmbedded(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val picture = retriever.embeddedPicture ?: return null
            decodeSampledBitmap(picture)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract embedded art from $uri: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampledBitmap(data: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        options.inSampleSize = calculateInSampleSize(options, MAX_DIMENSION, MAX_DIMENSION)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun decodeSampledBitmap(inputStream: InputStream): Bitmap? {
        return try {
            val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
            buffered.mark(10 * 1024 * 1024) // 10MB limit for marking
            
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(buffered, null, options)
            
            buffered.reset()
            options.inSampleSize = calculateInSampleSize(options, MAX_DIMENSION, MAX_DIMENSION)
            options.inJustDecodeBounds = false
            
            BitmapFactory.decodeStream(buffered, null, options)
        } catch (e: Exception) {
            Log.e(TAG, "decodeStream failed: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
