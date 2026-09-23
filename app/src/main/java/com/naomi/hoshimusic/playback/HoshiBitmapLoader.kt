package com.naomi.hoshimusic.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.naomi.hoshimusic.data.EmbeddedArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@UnstableApi
class HoshiBitmapLoader(context: Context) : BitmapLoader {
    private val loader = EmbeddedArtworkLoader(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            val bitmap = loader.loadArtwork(Uri.EMPTY, data)
            if (bitmap != null) future.set(bitmap)
            else future.setException(IllegalArgumentException("Failed to decode bitmap from bytes"))
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            // [D4] Try loading from URI, then fallback to embedded
            val bitmap = loader.loadArtwork(uri)
            if (bitmap != null) future.set(bitmap)
            else future.setException(IllegalArgumentException("Failed to load bitmap from $uri"))
        }
        return future
    }
}
