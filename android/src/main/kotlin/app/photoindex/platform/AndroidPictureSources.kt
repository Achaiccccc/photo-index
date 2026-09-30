package app.photoindex.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.photoindex.core.PictureFile
import app.photoindex.core.PictureSource
import app.photoindex.core.PictureSourceKind
import app.photoindex.core.PictureSources

/**
 * 相册来自 MediaStore 的桶，文件夹来自可持久化的文档树。
 * 查询只取标识、文件名、路径、大小和时间，不打开原图。
 */
class AndroidPictureSources(
    context: Context,
) : PictureSources {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun listAlbums(): List<PictureSource> {
        val resolver = appContext.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val counts = linkedMapOf<String, AlbumCount>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: "未命名相册"
                val current = counts.getOrPut(id) { AlbumCount(name) }
                current.count += 1
                if (current.name == "未命名相册" && name != "未命名相册") current.name = name
            }
        }
        return counts.map { (id, album) ->
            PictureSource(
                systemKey = id,
                kind = PictureSourceKind.Album,
                displayName = album.name,
                imageCount = album.count,
            )
        }.sortedBy { it.displayName }
    }

    override fun persistFolder(treeUri: String): PictureSource {
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri)) {
            throw IllegalArgumentException("不是文件夹授权")
        }
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val saved = preferences.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
        saved += uri.toString()
        preferences.edit().putStringSet(KEY_FOLDERS, saved).apply()
        return folderSource(uri)
    }

    override fun persistedFolders(): List<PictureSource> {
        val granted = appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val saved = preferences.getStringSet(KEY_FOLDERS, emptySet()).orEmpty()
        val readable = saved.intersect(granted)
        if (readable != saved) {
            preferences.edit().putStringSet(KEY_FOLDERS, readable).apply()
        }
        return readable.map { folderSource(Uri.parse(it)) }
    }

    override fun releaseFolder(systemKey: String) {
        val uri = Uri.parse(systemKey)
        val stillGranted = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val saved = preferences.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
        saved.remove(systemKey)
        preferences.edit().putStringSet(KEY_FOLDERS, saved).apply()
    }

    override fun listFiles(source: PictureSource): List<PictureFile> {
        return when (source.kind) {
            PictureSourceKind.Album -> listAlbumFiles(source.systemKey)
            PictureSourceKind.Folder -> listFolderFiles(Uri.parse(source.systemKey))
        }
    }

    private fun folderSource(uri: Uri): PictureSource {
        val files = listFolderFiles(uri)
        return PictureSource(
            systemKey = uri.toString(),
            kind = PictureSourceKind.Folder,
            displayName = folderName(uri),
            imageCount = files.size,
        )
    }

    private fun folderName(uri: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        appContext.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "文件夹"
    }

    private fun listAlbumFiles(bucketId: String): List<PictureFile> {
        val resolver = appContext.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relativeColumn = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            MediaStore.Images.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            relativeColumn,
        )
        val files = mutableListOf<PictureFile>()
        resolver.query(
            uri,
            projection,
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucketId),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val pathColumn = cursor.getColumnIndexOrThrow(relativeColumn)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: ""
                val pathValue = if (cursor.isNull(pathColumn)) "" else cursor.getString(pathColumn).orEmpty()
                val taken = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn)
                files += PictureFile(
                    systemId = cursor.getLong(idColumn).toString(),
                    fileName = name,
                    relativePath = if (Build.VERSION.SDK_INT >= 29) {
                        joinRelative(pathValue, name)
                    } else {
                        relativeFromAbsolute(pathValue, name)
                    },
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    dateModifiedMillis = cursor.getLong(modifiedColumn) * 1000L,
                    dateTakenMillis = taken?.takeIf { it > 0L },
                    sourceKey = bucketId,
                )
            }
        }
        return files
    }

    private fun listFolderFiles(treeUri: Uri): List<PictureFile> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val files = mutableListOf<PictureFile>()
        val pending = ArrayDeque<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        pending += rootId to ""
        while (pending.isNotEmpty()) {
            val (documentId, prefix) = pending.removeFirst()
            if (!seen.add(documentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            appContext.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: ""
                    val mime = cursor.getString(mimeColumn) ?: ""
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending += childId to path
                    } else if (mime.startsWith("image/")) {
                        val modified = if (cursor.isNull(modifiedColumn)) 0L else cursor.getLong(modifiedColumn)
                        val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                        files += PictureFile(
                            systemId = childId,
                            fileName = name,
                            relativePath = path,
                            sizeBytes = size.coerceAtLeast(0L),
                            dateModifiedMillis = modified.coerceAtLeast(0L),
                            dateTakenMillis = null,
                            sourceKey = treeUri.toString(),
                        )
                    }
                }
            }
        }
        return files
    }

    private class AlbumCount(var name: String) {
        var count: Int = 0
    }

    private companion object {
        const val PREFS = "picture-folders"
        const val KEY_FOLDERS = "uris"
    }
}

internal fun joinRelative(directory: String, fileName: String): String {
    if (fileName.isEmpty()) return directory.trimEnd('/')
    if (directory.isEmpty()) return fileName
    return directory.trimEnd('/') + "/" + fileName
}

internal fun relativeFromAbsolute(absolute: String, fileName: String): String {
    val marker = "/0/"
    val index = absolute.indexOf(marker)
    if (index >= 0) return absolute.substring(index + marker.length)
    return fileName.ifEmpty { absolute.substringAfterLast('/') }
}
