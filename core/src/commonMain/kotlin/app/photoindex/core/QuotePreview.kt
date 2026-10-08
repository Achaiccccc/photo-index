package app.photoindex.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 确认页用的估价。还没有压缩后的真实宽高时，按长边的 4:3 估算。
 * 不压缩时原图宽高未知，暂按长边 [ASSUMED_UNCOMPRESSED_LONG_EDGE] 估算。
 * 合计等于单张乘以待处理张数。待处理为 0 时合计是 0。
 */
data class QuotePreview(
    val pendingCount: Int,
    val estimatedBatchCount: Int,
    val batchMaxLines: Int,
    val referenceSize: CompressedImageSize,
    val assumesAspectRatio: Boolean,
    val assumesUncompressedLongEdge: Boolean,
    val perImageRealtime: YuanRange,
    val perImageBatch: YuanRange,
    val totalRealtime: YuanRange,
    val totalBatch: YuanRange,
    val usesBatchPrice: Boolean,
    val showBatchAndRealtime: Boolean,
    val activePerImage: YuanRange,
    val activeTotal: YuanRange,
    val priceLabel: String,
    val repeatsAmountBesideButton: Boolean,
    val calibration: Calibration,
    val providerNote: String?,
)

/** 估价还没确认。调用上传或开始任务时抛出，上传函数不会执行。 */
class QuoteNotConfirmed : IllegalStateException("未确认估价，不能上传")

fun referenceCompressedSize(longEdge: Int?): CompressedImageSize {
    val edge = longEdge ?: ASSUMED_UNCOMPRESSED_LONG_EDGE
    val shortSide = (edge.toLong() * 3 / 4).toInt().coerceAtLeast(1)
    return CompressedImageSize(widthPx = edge, heightPx = shortSide)
}

fun estimatedBatchCount(pendingCount: Int, maxLines: Int): Int {
    require(pendingCount >= 0) { "待处理张数不能为负" }
    require(maxLines in 1..10_000) { "每批行数要在 1 到 10000 之间" }
    if (pendingCount == 0) return 0
    return (pendingCount + maxLines - 1) / maxLines
}

fun previewQuote(
    settings: IndexSettings,
    pendingCount: Int,
    samples: List<TokenUsageSample> = emptyList(),
): QuotePreview {
    validateIndexSettings(settings)
    require(pendingCount >= 0) { "待处理张数不能为负" }
    val size = referenceCompressedSize(settings.longEdge)
    val one = estimateRecognitionCost(
        settings = settings.toRecognitionSettings(),
        images = listOf(size),
        samples = samples,
    )
    val factor = pendingCount.toDouble()
    val totalRealtime = one.totalRealtime * factor
    val totalBatch = one.totalBatch * factor
    val provider = ProviderCatalog.require(settings.provider)
    val usesBatchPrice = settings.uploadMode == UPLOAD_MODE_BATCH_FILE && provider.batchFileReady
    val activePerImage = if (usesBatchPrice) one.perImageBatch else one.perImageRealtime
    val activeTotal = if (usesBatchPrice) totalBatch else totalRealtime
    val note = when {
        provider.note != null && settings.uploadMode == UPLOAD_MODE_BATCH_FILE && !provider.batchFileReady ->
            provider.note + "批量文件还不可用，合计按实时价。"
        else -> provider.note
    }
    return QuotePreview(
        pendingCount = pendingCount,
        estimatedBatchCount = estimatedBatchCount(pendingCount, settings.batchMaxLines),
        batchMaxLines = settings.batchMaxLines,
        referenceSize = size,
        assumesAspectRatio = true,
        assumesUncompressedLongEdge = settings.longEdge == null,
        perImageRealtime = one.perImageRealtime,
        perImageBatch = one.perImageBatch,
        totalRealtime = totalRealtime,
        totalBatch = totalBatch,
        usesBatchPrice = usesBatchPrice,
        showBatchAndRealtime = settings.uploadMode == UPLOAD_MODE_BATCH_FILE,
        activePerImage = activePerImage,
        activeTotal = activeTotal,
        priceLabel = if (usesBatchPrice) "批量价" else "实时价",
        repeatsAmountBesideButton = activeTotal.point > settings.amountAlertYuan,
        calibration = one.calibration,
        providerNote = note,
    )
}

/** 确认页上的金额。避免很小的数变成科学计数法。 */
fun formatYuan(value: Double): String {
    require(!value.isNaN() && !value.isInfinite()) { "金额必须是有限数字" }
    val negative = value < 0.0
    val micros = abs(value).times(1_000_000.0).roundToLong()
    val whole = micros / 1_000_000
    val trimmed = (micros % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    val fraction = if (trimmed.length >= 4) trimmed else trimmed.padEnd(4, '0')
    val text = "$whole.$fraction"
    return if (negative) "-$text" else text
}

fun formatYuanRange(range: YuanRange): String =
    "${formatYuan(range.low)}–${formatYuan(range.high)} 元（规划 ${formatYuan(range.point)} 元）"

/** 不压缩且还没有原图宽高时，确认页用这个长边做 4:3 估算。 */
const val ASSUMED_UNCOMPRESSED_LONG_EDGE = 4000
