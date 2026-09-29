package app.photoindex.core

/**
 * 压缩后的真实像素。图像 token 用这个尺寸，不用固定的 4:3 假设。
 */
data class CompressedImageSize(
    val widthPx: Int,
    val heightPx: Int,
)

data class PlannedTokens(
    val imageTokens: Long,
    val promptTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
)

data class YuanRange(
    val low: Double,
    val point: Double,
    val high: Double,
) {
    operator fun plus(other: YuanRange): YuanRange =
        YuanRange(low + other.low, point + other.point, high + other.high)

    operator fun times(factor: Double): YuanRange =
        YuanRange(low * factor, point * factor, high * factor)
}

/**
 * 满 [CALIBRATION_MIN_SAMPLES] 张后才用实际用量修正后续估价。
 * 不足时 [applied] 为 false，两个系数保持 1，不改变金额。
 */
data class Calibration(
    val sampleCount: Int,
    val applied: Boolean,
    val inputFactor: Double,
    val outputFactor: Double,
)

/** 一张已经返回用量的图。估算值是当时用千问公式算出的 token，不是账单原文。 */
data class TokenUsageSample(
    val estimatedInputTokens: Long,
    val actualInputTokens: Long,
    val estimatedOutputTokens: Long,
    val actualOutputTokens: Long,
)

data class RecognitionCostEstimate(
    val imageCount: Int,
    /** 各张的算术平均。没有图片时为零。 */
    val perImageRealtime: YuanRange,
    val perImageBatch: YuanRange,
    val totalRealtime: YuanRange,
    val totalBatch: YuanRange,
    val calibration: Calibration,
)

/**
 * 千问 3 代视觉模型的图像 token：高 × 宽 / (32 × 32) + 2。
 * 豆包、Gemini 的专用公式留到 T15，本节点不实现。
 */
fun qwenImageTokens(widthPx: Int, heightPx: Int): Long {
    require(widthPx > 0 && heightPx > 0) { "宽高必须是压缩后的正像素" }
    return widthPx.toLong() * heightPx.toLong() / QWEN_PATCH_AREA + QWEN_IMAGE_TOKEN_OFFSET
}

fun plannedTokens(
    image: CompressedImageSize,
    detailLevel: DetailLevel,
    thinkingEnabled: Boolean,
    thinkingTokenLimit: Int,
): PlannedTokens {
    require(thinkingTokenLimit > 0) { "思考上限必须为正数" }
    val imageTokens = qwenImageTokens(image.widthPx, image.heightPx)
    val promptTokens = detailLevel.promptTokens
    val outputTokens = detailLevel.plannedOutputTokens + thinkingTokens(thinkingEnabled, thinkingTokenLimit)
    return PlannedTokens(
        imageTokens = imageTokens,
        promptTokens = promptTokens,
        inputTokens = imageTokens + promptTokens,
        outputTokens = outputTokens,
    )
}

/**
 * 按设计文档第 5.2 节估价。单价是实时列表价；批量文件按 [BATCH_PRICE_RATIO] 半价。
 * 区间只缩放输出规划值，输入 token 和思考上限不跟着放大。
 */
fun estimateRecognitionCost(
    settings: RecognitionSettings,
    images: List<CompressedImageSize>,
    samples: List<TokenUsageSample> = emptyList(),
): RecognitionCostEstimate {
    val prices = settings.prices()
    require(prices.inputPerMillionYuan >= 0.0 && prices.outputPerMillionYuan >= 0.0) {
        "单价不能为负"
    }
    val calibration = calibrate(samples)
    val totalRealtime = images.fold(YuanRange(0.0, 0.0, 0.0)) { sum, image ->
        sum + imageYuan(settings, image, prices, calibration)
    }
    val totalBatch = totalRealtime * BATCH_PRICE_RATIO
    val perImageRealtime = average(totalRealtime, images.size)
    val perImageBatch = average(totalBatch, images.size)
    return RecognitionCostEstimate(
        imageCount = images.size,
        perImageRealtime = perImageRealtime,
        perImageBatch = perImageBatch,
        totalRealtime = totalRealtime,
        totalBatch = totalBatch,
        calibration = calibration,
    )
}

fun calibrate(samples: List<TokenUsageSample>): Calibration {
    if (samples.size < CALIBRATION_MIN_SAMPLES) {
        return Calibration(
            sampleCount = samples.size,
            applied = false,
            inputFactor = 1.0,
            outputFactor = 1.0,
        )
    }
    samples.forEach { sample ->
        require(sample.estimatedInputTokens > 0L && sample.estimatedOutputTokens > 0L) {
            "估算 token 必须为正数才能算校准系数"
        }
    }
    val inputFactor = samples
        .map { it.actualInputTokens.toDouble() / it.estimatedInputTokens.toDouble() }
        .average()
    val outputFactor = samples
        .map { it.actualOutputTokens.toDouble() / it.estimatedOutputTokens.toDouble() }
        .average()
    return Calibration(
        sampleCount = samples.size,
        applied = true,
        inputFactor = inputFactor,
        outputFactor = outputFactor,
    )
}

private fun imageYuan(
    settings: RecognitionSettings,
    image: CompressedImageSize,
    prices: PricePerMillion,
    calibration: Calibration,
): YuanRange {
    val planned = plannedTokens(
        image = image,
        detailLevel = settings.detailLevel,
        thinkingEnabled = settings.thinkingEnabled,
        thinkingTokenLimit = settings.thinkingTokenLimit,
    )
    val thinking = thinkingTokens(settings.thinkingEnabled, settings.thinkingTokenLimit).toDouble()
    val outputLow = settings.detailLevel.plannedOutputTokens * settings.detailLevel.outputLowFactor + thinking
    val outputHigh = settings.detailLevel.plannedOutputTokens * settings.detailLevel.outputHighFactor + thinking
    val inputTokens = planned.inputTokens.toDouble() * calibration.inputFactor
    val outputFactor = calibration.outputFactor
    return YuanRange(
        low = yuan(inputTokens, prices.inputPerMillionYuan) +
            yuan(outputLow * outputFactor, prices.outputPerMillionYuan),
        point = yuan(inputTokens, prices.inputPerMillionYuan) +
            yuan(planned.outputTokens.toDouble() * outputFactor, prices.outputPerMillionYuan),
        high = yuan(inputTokens, prices.inputPerMillionYuan) +
            yuan(outputHigh * outputFactor, prices.outputPerMillionYuan),
    )
}

private fun thinkingTokens(thinkingEnabled: Boolean, thinkingTokenLimit: Int): Long =
    if (thinkingEnabled) thinkingTokenLimit.toLong() else 0L

private fun yuan(tokens: Double, pricePerMillion: Double): Double =
    tokens * pricePerMillion / TOKENS_PER_PRICE_UNIT

private fun average(total: YuanRange, count: Int): YuanRange =
    if (count == 0) {
        YuanRange(0.0, 0.0, 0.0)
    } else {
        YuanRange(total.low / count, total.point / count, total.high / count)
    }

/** 百炼批量文件为实时价的 50%。 */
const val BATCH_PRICE_RATIO = 0.5

const val CALIBRATION_MIN_SAMPLES = 20

private const val QWEN_PATCH_AREA = 32L * 32L
private const val QWEN_IMAGE_TOKEN_OFFSET = 2L
private const val TOKENS_PER_PRICE_UNIT = 1_000_000.0
