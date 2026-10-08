package app.photoindex.platform

import android.content.Context
import app.photoindex.core.CompressedJpeg
import app.photoindex.core.ImageBytes
import app.photoindex.core.ImageCompressor
import app.photoindex.core.ImageUnreadable
import app.photoindex.core.JpegCompressResult
import app.photoindex.core.JpegCompressor
import app.photoindex.storage.PhotoIndexDatabase

/**
 * 按图片 ID 打开原图，压成一张 JPEG 交给打包器。
 * 一次只读一张。解不开时抛出 [ImageUnreadable]，打包器只把这一张标失败。
 */
class AssetJpegCompressor(
    context: Context,
    private val database: PhotoIndexDatabase,
    private val jpeg: JpegCompressor = AndroidJpegCompressor,
) : ImageCompressor {
    private val appContext = context.applicationContext

    override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg {
        val asset = database.assetQueries.selectAssetById(assetId).executeAsOne()
        val source = database.sourceQueries.selectSourceById(asset.sourceId).executeAsOne()
        val uri = thumbnailUri(source.kind, source.systemKey, asset.systemId)
            ?: throw ImageUnreadable("找不到这张图")
        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (error: Exception) {
            throw ImageUnreadable(error.message?.takeIf { it.isNotBlank() } ?: "打不开这张图")
        } ?: throw ImageUnreadable("打不开这张图")
        val longEdge = database.settingQueries.selectSetting().executeAsOne().longEdge?.toInt()
        return when (val result = jpeg.compress(ImageBytes { bytes }, longEdge, jpegQuality)) {
            is JpegCompressResult.Failed -> throw ImageUnreadable(result.reason)
            is JpegCompressResult.Ready -> object : CompressedJpeg {
                override val bytes: ByteArray = result.bytes
                override val widthPx: Int = result.widthPx
                override val heightPx: Int = result.heightPx
                override fun close() = Unit
            }
        }
    }
}
