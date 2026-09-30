package app.photoindex.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import app.photoindex.core.ImageBytes
import app.photoindex.core.JpegCompressResult
import app.photoindex.core.JpegCompressor
import app.photoindex.core.decodeSampleSize
import app.photoindex.core.fittedSize
import app.photoindex.core.jpegHasGpsExif
import app.photoindex.core.jpegWithoutLocation
import app.photoindex.core.looksLikeHeic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 用 Android 位图解码压成 JPEG。长边和质量控制尺寸与质量。
 * 位图只存在这次调用的局部变量里，返回前回收，不留给下一张。
 * 解码失败、包括 HEIC 解不开，都返回原因。
 */
object AndroidJpegCompressor : JpegCompressor {
    override fun compress(image: ImageBytes, longEdge: Int?, jpegQuality: Int): JpegCompressResult {
        if (jpegQuality !in 1..100) {
            return JpegCompressResult.Failed("JPEG 质量要在 1 到 100 之间")
        }
        if (longEdge != null && longEdge <= 0) {
            return JpegCompressResult.Failed("长边必须是正像素")
        }
        val input = image.read()
        if (input.isEmpty()) return JpegCompressResult.Failed("图片是空的")
        val held = ArrayList<Bitmap>(3)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return JpegCompressResult.Failed(undecodable(input))
            }
            val orientation = readOrientation(input)
            val orientedWidth = if (swapsAxes(orientation)) bounds.outHeight else bounds.outWidth
            val orientedHeight = if (swapsAxes(orientation)) bounds.outWidth else bounds.outHeight
            val sample = decodeSampleSize(orientedWidth, orientedHeight, longEdge)
            val decoded = BitmapFactory.decodeByteArray(
                input,
                0,
                input.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return JpegCompressResult.Failed(undecodable(input))
            held += decoded
            val oriented = orient(decoded, orientation)
            if (oriented !== decoded) held += oriented
            val target = fittedSize(orientedWidth, orientedHeight, longEdge)
            val scaled = if (oriented.width == target.first && oriented.height == target.second) {
                oriented
            } else {
                Bitmap.createScaledBitmap(oriented, target.first, target.second, true).also { created ->
                    if (created !== oriented) held += created
                }
            }
            val encoded = ByteArrayOutputStream()
            val wrote = scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, encoded)
            if (!wrote) return JpegCompressResult.Failed("JPEG 编码失败")
            val stripped = jpegWithoutLocation(encoded.toByteArray())
            if (jpegHasGpsExif(stripped)) {
                return JpegCompressResult.Failed("压缩结果仍带有定位信息")
            }
            JpegCompressResult.Ready(
                bytes = stripped,
                widthPx = scaled.width,
                heightPx = scaled.height,
            )
        } catch (error: OutOfMemoryError) {
            JpegCompressResult.Failed(
                if (looksLikeHeic(input)) "HEIC 解不开" else "内存不够，解不开这张图",
            )
        } catch (error: Exception) {
            JpegCompressResult.Failed(
                if (looksLikeHeic(input)) {
                    "HEIC 解不开"
                } else {
                    error.message?.takeIf { it.isNotBlank() } ?: "图片解不开"
                },
            )
        } finally {
            held.distinct().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            held.clear()
        }
    }

    private fun undecodable(input: ByteArray): String {
        return if (looksLikeHeic(input)) "HEIC 解不开" else "图片解不开"
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
}
