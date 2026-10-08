package app.photoindex.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import app.photoindex.core.decodeSampleSize
import app.photoindex.core.fittedSize
import java.io.ByteArrayInputStream

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

/** 详情页展示原图时的长边。按这个尺寸采样，不把整张原图像素留在内存里。 */
const val DISPLAY_LONG_EDGE = 1600

/**
 * 从原图文件解码一张用于详情页的位图。
 * 必须在后台线程调用。相册和文件夹都读文件本身，不走 256 像素的缩略图缓存。
 */
fun loadDisplayBitmap(
    context: Context,
    kind: String,
    systemKey: String,
    systemId: String,
): Bitmap? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "原图解码不能在主线程" }
    val uri = thumbnailUri(kind, systemKey, systemId) ?: return null
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = readOrientation(bytes)
    val orientedWidth = if (swapsAxes(orientation)) bounds.outHeight else bounds.outWidth
    val orientedHeight = if (swapsAxes(orientation)) bounds.outWidth else bounds.outHeight
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = decodeSampleSize(orientedWidth, orientedHeight, DISPLAY_LONG_EDGE)
        },
    ) ?: return null
    val oriented = orient(decoded, orientation)
    if (oriented !== decoded && !decoded.isRecycled) decoded.recycle()
    val target = fittedSize(orientedWidth, orientedHeight, DISPLAY_LONG_EDGE)
    if (oriented.width == target.first && oriented.height == target.second) return oriented
    val scaled = Bitmap.createScaledBitmap(oriented, target.first, target.second, true)
    if (scaled !== oriented && !oriented.isRecycled) oriented.recycle()
    return scaled
}

private fun readOrientation(bytes: ByteArray): Int {
    return try {
        ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
}

private fun swapsAxes(orientation: Int): Boolean {
    return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
}

private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.preRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.preRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.preRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.preRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.preRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
