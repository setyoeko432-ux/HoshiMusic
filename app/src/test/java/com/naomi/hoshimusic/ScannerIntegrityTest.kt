package com.naomi.hoshimusic

import android.net.Uri
import com.naomi.hoshimusic.util.UriUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.provider.DocumentsContract
import android.content.Context
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScannerIntegrityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `uri mapping - primary music folder`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val mapping = UriUtils.getMapping(context, uri)
        
        assertTrue(mapping is UriUtils.FolderMapping.MediaStoreCompatible)
        val compatible = mapping as UriUtils.FolderMapping.MediaStoreCompatible
        assertEquals("primary", compatible.volumeId)
        assertEquals("Music", compatible.relativePath)
    }

    @Test
    fun `uri mapping - sd card folder`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3AAudio%2FMySongs")
        val mapping = UriUtils.getMapping(context, uri)
        
        assertTrue(mapping is UriUtils.FolderMapping.MediaStoreCompatible)
        val compatible = mapping as UriUtils.FolderMapping.MediaStoreCompatible
        assertEquals("1234-5678", compatible.volumeId)
        assertEquals("Audio/MySongs", compatible.relativePath)
    }

    @Test
    fun `uri mapping - unsupported provider`() {
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/tree/abc")
        val mapping = UriUtils.getMapping(context, uri)
        
        assertEquals(UriUtils.FolderMapping.UnsupportedForMediaStore, mapping)
    }

    @Test
    fun `uri mapping - malformed tree uri`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic")
        // Not a tree URI (DocumentsContract.isTreeUri will be false)
        val mapping = UriUtils.getMapping(context, uri)
        assertEquals(UriUtils.FolderMapping.UnsupportedForMediaStore, mapping)
    }
}
