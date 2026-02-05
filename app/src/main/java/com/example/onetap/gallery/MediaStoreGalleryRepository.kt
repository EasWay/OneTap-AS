package com.example.onetap.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO
}

data class DownloadedMedia(
    val id: Long,
    val uri: Uri,
    val title: String,
    val kind: MediaKind,
    val dateAdded: Long
)

class MediaStoreGalleryRepository(private val context: Context) {

    fun loadOneTapMedia(limit: Int = 200): List<DownloadedMedia> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val selection = "(" +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" +
            ") AND (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" +
            ")"

        val selectionArgs = arrayOf(
            "OneTap_%",
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
        )

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT $limit"

        val externalContentUri = MediaStore.Files.getContentUri("external")
        val items = mutableListOf<DownloadedMedia>()

        context.contentResolver.query(
            externalContentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Untitled"
                val mediaType = cursor.getInt(typeCol)
                val dateAdded = cursor.getLong(dateCol)

                val kind = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaKind.IMAGE
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaKind.AUDIO
                    else -> continue
                }

                items += DownloadedMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(externalContentUri, id),
                    title = name,
                    kind = kind,
                    dateAdded = dateAdded
                )
            }
        }

        return items
    }
}
