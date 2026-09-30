package app.photoindex.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size

/** 缩略图边长。只向系统要这个尺寸，不解码原图。 */
const val THUMBNAIL_EDGE = 256

/**
 * 读取系统缩略图。
 * 相册在 Android 10 及以上走 [ContentResolver.loadThumbnail]，更早的系统走相册缩略图缓存。
 * 文件夹走文档提供者的缩略图接口。
 */
fun loadSystemThumbnail(
    context: Context,
    kind: String,
    systemKey: String,
    systemId: String,
    signal: CancellationSignal,
): Bitmap? {
    val uri = thumbnailUri(kind, systemKey, systemId) ?: return null
    val resolver = context.contentResolver
    val raw = when {
        kind == "folder" -> DocumentsContract.getDocumentThumbnail(
            resolver,
            uri,
            Point(THUMBNAIL_EDGE, THUMBNAIL_EDGE),
            signal,
        )
        Build.VERSION.SDK_INT >= 29 -> resolver.loadThumbnail(
            uri,
            Size(THUMBNAIL_EDGE, THUMBNAIL_EDGE),
            signal,
        )
        else -> {
            val id = systemId.toLongOrNull() ?: return null
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
    } ?: return null
    return limitEdge(raw, THUMBNAIL_EDGE)
}

internal fun thumbnailUri(kind: String, systemKey: String, systemId: String): Uri? {
    return when (kind) {
        "album" -> {
            val id = systemId.toLongOrNull() ?: return null
            Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        }
        "folder" -> {
            val tree = Uri.parse(systemKey)
            if (!DocumentsContract.isTreeUri(tree)) return null
            DocumentsContract.buildDocumentUriUsingTree(tree, systemId)
        }
        else -> null
    }
}

private fun limitEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val edge = maxOf(bitmap.width, bitmap.height)
    if (edge <= maxEdge || edge == 0) return bitmap
    val scale = maxEdge.toFloat() / edge.toFloat()
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return scaled
}
