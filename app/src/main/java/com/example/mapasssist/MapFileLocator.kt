package com.example.mapasssist

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Finds a manually added map whose filename is its exact DNC map number (for example C1.png). */
object MapFileLocator {
    fun find(context: Context, mapNo: String): Uri? {
        val identifier = MapNumber.identifier(mapNo)
        if (identifier.isEmpty()) return null
        val resolver = context.contentResolver
        val collections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        } else {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        return collections.firstNotNullOfOrNull { collection ->
            runCatching { findInCollection(resolver, collection, identifier) }.getOrNull()
        }
    }

    private fun findInCollection(resolver: ContentResolver, collection: Uri, identifier: String): Uri? {
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val pathArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Download/Map Assist/Maps/")
        } else {
            arrayOf("%/Download/Map Assist/Maps/%")
        }
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            pathSelection,
            pathArgs,
            null
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (MapNumber.identifier(cursor.getString(name).substringBeforeLast('.')) == identifier) {
                    return@use Uri.withAppendedPath(collection, cursor.getLong(id).toString())
                }
            }
            null
        }
    }
}
