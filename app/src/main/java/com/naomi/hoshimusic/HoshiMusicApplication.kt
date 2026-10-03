package com.naomi.hoshimusic

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.naomi.hoshimusic.data.EmbeddedArtworkLoader
import com.naomi.hoshimusic.util.DiagLog
import okio.Buffer
import java.io.ByteArrayOutputStream

class HoshiMusicApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // WAJIB paling awal: logd diblokir oleh sebagian OEM untuk UID app
        // pihak ketiga, sehingga android.util.Log tidak bisa dipakai sebagai
        // sumber diagnosis. Lihat DiagLog untuk cara membacanya.
        DiagLog.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(AudioArtworkFetcher.Factory(this@HoshiMusicApplication))
            }
            .build()
    }
}

class AudioArtworkFetcher(
    private val data: Uri,
    private val context: Context
) : Fetcher {
    private val loader = EmbeddedArtworkLoader(context)

    override suspend fun fetch(): FetchResult? {
        val bitmap = loader.loadArtwork(data) ?: return null
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        
        return SourceResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                context = context
            ),
            mimeType = "image/png",
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val mimeType = context.contentResolver.getType(data)
            val isAudio = mimeType?.startsWith("audio/") == true ||
                    data.path?.let { path ->
                        listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".alac", ".wma", ".dsf", ".dff")
                            .any { path.endsWith(it, ignoreCase = true) }
                    } == true
            
            return if (isAudio) AudioArtworkFetcher(data, context) else null
        }
    }
}
