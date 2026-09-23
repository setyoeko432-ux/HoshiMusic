package com.naomi.hoshimusic.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.util.UnstableApi
import com.naomi.hoshimusic.data.UserPreferences
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class HoshiAudioSinkTest {

    @Test
    fun testAudioSinkInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = UserPreferences(context)

        val dspProcessor = if (HoshiDspProcessor.libraryLoaded) HoshiDspProcessor() else null
        
        val sink = HoshiAudioSink(context, preferences, dspProcessor)
        assertNotNull(sink)
        
        sink.release()
        dspProcessor?.release()
    }
}
