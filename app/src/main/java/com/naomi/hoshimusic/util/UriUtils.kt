package com.naomi.hoshimusic.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

object UriUtils {
    sealed interface FolderMapping {
        data class MediaStoreCompatible(
            val volumeId: String,
            val relativePath: String,
            val physicalPath: String
        ) : FolderMapping
        
        data object UnsupportedForMediaStore : FolderMapping
    }

    fun getMapping(context: Context, uri: Uri): FolderMapping {
        if (!DocumentsContract.isTreeUri(uri)) return FolderMapping.UnsupportedForMediaStore
        
        val authority = uri.authority
        if (authority != "com.android.externalstorage.documents") {
            return FolderMapping.UnsupportedForMediaStore
        }

        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = documentId.split(":")
        if (parts.size < 2) return FolderMapping.UnsupportedForMediaStore

        val volumeId = parts[0]
        val relPath = parts[1].removePrefix("/")
        
        val physicalPath = if ("primary".equals(volumeId, ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath + "/" + relPath
        } else {
            "/storage/" + volumeId + "/" + relPath
        }
        
        return FolderMapping.MediaStoreCompatible(volumeId, relPath, physicalPath)
    }

    // Compatibility for existing calls
    data class FolderInfo(
        val volumeId: String,
        val relativePath: String,
        val physicalPath: String
    )

    fun getFolderInfo(context: Context, uri: Uri): FolderInfo? {
        val mapping = getMapping(context, uri)
        return if (mapping is FolderMapping.MediaStoreCompatible) {
            FolderInfo(mapping.volumeId, mapping.relativePath, mapping.physicalPath)
        } else null
    }

    fun getPhysicalPath(context: Context, uri: Uri): String? {
        return getFolderInfo(context, uri)?.physicalPath
    }
}
