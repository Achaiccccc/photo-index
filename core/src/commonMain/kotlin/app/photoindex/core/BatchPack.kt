package app.photoindex.core

import kotlin.io.encoding.Base64

/**
 * 设计文档第 4.3 节的本地打包。
 * 调用方每次只提供下一张图片的 ID，打包器不接收已经全部放进内存的图片列表。
 * 压缩结果用完即关，同一时刻只向压缩器要一张。
 * 达到行数或体积上限就封口；没封口的半截文件不写入数据库。
 * 上传、查询和进程恢复由批状态机驱动。
 * 真机解码、去掉定位信息由 JpegCompressor 完成；打包测试仍使用这里的假压缩器。
 */
fun interface PendingImageSource {
    fun nextId(): String?
}

/** 压缩后的 JPEG。调用方必须在要下一张之前关掉这一张。 */
interface CompressedJpeg : AutoCloseable {
    val bytes: ByteArray
    val widthPx: Int
    val heightPx: Int
}

interface ImageCompressor {
    fun compress(assetId: String, jpegQuality: Int): CompressedJpeg
}

/** 正在写的那一个批文件。磁盘上同时只能有一个未关闭的实现。 */
interface BatchFileSink : AutoCloseable {
    val path: String
    val byteSize: Long
    fun appendLine(line: String)
}

fun interface BatchFileSinkFactory {
    fun open(batchId: String): BatchFileSink
}

interface BatchLedger {
    /**
     * 打开批文件时记下「还在写」。状态机靠这行在封口前被杀掉之后删掉半截文件。
     * 不覆盖时什么都不记，半截文件不进库。
     */
    fun beginPacking(batchId: String, configFingerprint: String, localPath: String) {}

    fun seal(batch: SealedBatchDraft)
    fun markLineTooLarge(assetId: String, reason: String)
}

data class PackedAsset(
    val id: String,
    val contentHash: String,
)

data class SealedBatchDraft(
    val id: String,
    val configFingerprint: String,
    val localPath: String,
    val lineCount: Int,
    val byteSize: Long,
    val assets: List<PackedAsset>,
)

data class SealedBatch(
    val id: String,
    val localPath: String,
    val lineCount: Int,
    val byteSize: Long,
    val assetIds: List<String>,
)

/** 输入已经读完、但还没凑满封口条件的文件。库里没有对应的批。 */
data class UnsealedBatch(
    val id: String,
    val configFingerprint: String,
    val localPath: String,
    val lineCount: Int,
    val byteSize: Long,
    val assets: List<PackedAsset>,
)

data class BatchPackOutcome(
    val sealed: List<SealedBatch>,
    val unsealed: UnsealedBatch?,
)

data class BatchPackLimits(
    val maxFileBytes: Long = DEFAULT_BATCH_FILE_BYTES,
    val maxLines: Int = DEFAULT_BATCH_FILE_LINES,
    val maxLineBytes: Long = DEFAULT_BATCH_LINE_BYTES,
) {
    init {
        require(maxFileBytes > 0L) { "批文件体积上限必须为正数" }
        require(maxLines in 1..10_000) { "批文件行数上限要在 1 到 10000 之间" }
        require(maxLineBytes + 1 <= maxFileBytes) { "单行上限加上换行后必须放得进一个空批文件" }
    }
}

data class BatchPackConfig(
    val model: String,
    val thinkingEnabled: Boolean,
    val thinkingTokenLimit: Int,
    val jpegQuality: Int,
    val detailLevel: DetailLevel,
    val configFingerprint: String,
    val limits: BatchPackLimits = BatchPackLimits(),
) {
    init {
        require(model.isNotEmpty()) { "模型名不能为空" }
        require(thinkingTokenLimit > 0) { "思考上限必须为正数" }
        require(jpegQuality in 60..90) { "JPEG 质量要在 60 到 90 之间" }
        require(configFingerprint.isNotEmpty()) { "配置指纹不能为空" }
    }
}

/** 与 setting 表的默认每批体积、行数一致。单行 5 MB 比服务商的 6 MB 更紧，不放进设置表。 */
const val DEFAULT_BATCH_FILE_BYTES = 400L * 1024 * 1024
const val DEFAULT_BATCH_FILE_LINES = 2000
const val DEFAULT_BATCH_LINE_BYTES = 5L * 1024 * 1024

/**
 * 单行超限时只再压一次。质量降 20，最低 40。
 * 设置里的 60–90 管的是用户选择，这一次补救可以低于 60。
 */
fun reducedJpegQuality(jpegQuality: Int): Int {
    require(jpegQuality in 60..90) { "JPEG 质量要在 60 到 90 之间" }
    return (jpegQuality - 20).coerceAtLeast(40)
}

fun lineTooLargeReason(maxLineBytes: Long): String =
    "单行超过 $maxLineBytes 字节，降低 JPEG 质量后仍放不下"

/**
 * 百炼 OpenAI 兼容批量文件里的一行。
 * custom_id 是图片 ID。图片是 JPEG 的 Base64 data URI，另有一条文本提示词。
 * 思考关闭时显式写 enable_thinking=false，并用详细度对应的规划值限制 max_tokens。
 * 思考打开时写 enable_thinking 和 thinking_budget。同一个文件里的模型名和思考开关由调用方保持一致。
 */
fun batchRequestLine(
    assetId: String,
    jpegBytes: ByteArray,
    model: String,
    thinkingEnabled: Boolean,
    thinkingTokenLimit: Int,
    detailLevel: DetailLevel,
): String {
    require(thinkingTokenLimit > 0) { "思考上限必须为正数" }
    val dataUri = "data:image/jpeg;base64," + Base64.encode(jpegBytes)
    val thinking = if (thinkingEnabled) {
        """"enable_thinking":true,"thinking_budget":$thinkingTokenLimit"""
    } else {
        """"enable_thinking":false,"max_tokens":${detailLevel.plannedOutputTokens}"""
    }
    return buildString {
        append("""{"custom_id":""")
        append(jsonString(assetId))
        append(""","method":"POST","url":"/v1/chat/completions","body":{"model":""")
        append(jsonString(model))
        append(',')
        append(thinking)
        append(""","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":""")
        append(jsonString(dataUri))
        append("""}},{"type":"text","text":""")
        append(jsonString(recognitionPrompt(detailLevel)))
        // 依次关掉 text 对象、content 数组、message 对象、messages 数组、body、根对象。
        append('}')
        append(']')
        append('}')
        append(']')
        append('}')
        append('}')
    }
}

fun packImageBatch(
    source: PendingImageSource,
    compressor: ImageCompressor,
    files: BatchFileSinkFactory,
    ledger: BatchLedger,
    newBatchId: () -> String,
    config: BatchPackConfig,
): BatchPackOutcome {
    val sealed = mutableListOf<SealedBatch>()
    var current: OpenBatch? = null
    try {
        while (true) {
            val assetId = source.nextId() ?: break
            val prepared = encodeAsset(assetId, compressor, ledger, config) ?: continue
            var open = current
            if (open != null && !open.fits(prepared.jsonByteSize, config.limits)) {
                sealed += closeAndSeal(open, ledger, config)
                open = null
            }
            if (open == null) {
                val batchId = newBatchId()
                open = OpenBatch(
                    id = batchId,
                    fingerprint = config.configFingerprint,
                    sink = files.open(batchId),
                )
                current = open
                ledger.beginPacking(batchId, config.configFingerprint, open.sink.path)
            }
            current = open
            open.append(prepared)
            if (open.isFull(config.limits)) {
                sealed += closeAndSeal(open, ledger, config)
                current = null
            }
        }
    } finally {
        current?.closeSink()
    }
    return BatchPackOutcome(
        sealed = sealed,
        unsealed = current?.toUnsealed(),
    )
}

private fun encodeAsset(
    assetId: String,
    compressor: ImageCompressor,
    ledger: BatchLedger,
    config: BatchPackConfig,
): PreparedLine? {
    val first = compressLine(assetId, config.jpegQuality, compressor, config)
    if (first != null) return first
    val second = compressLine(assetId, reducedJpegQuality(config.jpegQuality), compressor, config)
    if (second != null) return second
    ledger.markLineTooLarge(assetId, lineTooLargeReason(config.limits.maxLineBytes))
    return null
}

private fun compressLine(
    assetId: String,
    jpegQuality: Int,
    compressor: ImageCompressor,
    config: BatchPackConfig,
): PreparedLine? {
    val image = compressor.compress(assetId, jpegQuality)
    try {
        require(image.widthPx > 0 && image.heightPx > 0) { "压缩结果的宽高必须是正像素" }
        val line = batchRequestLine(
            assetId = assetId,
            jpegBytes = image.bytes,
            model = config.model,
            thinkingEnabled = config.thinkingEnabled,
            thinkingTokenLimit = config.thinkingTokenLimit,
            detailLevel = config.detailLevel,
        )
        val jsonByteSize = line.encodeToByteArray().size.toLong()
        if (jsonByteSize > config.limits.maxLineBytes) return null
        return PreparedLine(
            assetId = assetId,
            jsonLine = line,
            contentHash = sha256Hex(image.bytes),
            jsonByteSize = jsonByteSize,
        )
    } finally {
        image.close()
    }
}

/** 输入读完后，把还没封口的最后一批补记进库。没调用之前，半截文件不算数。 */
fun sealOutstandingBatch(ledger: BatchLedger, batch: UnsealedBatch) {
    require(batch.assets.isNotEmpty()) { "空批不能封口" }
    require(batch.lineCount == batch.assets.size) { "封口行数要和图片数一致" }
    require(batch.byteSize > 0L) { "空文件不能封口" }
    ledger.seal(batch.toDraft())
}

private data class PreparedLine(
    val assetId: String,
    val jsonLine: String,
    val contentHash: String,
    val jsonByteSize: Long,
)

private class OpenBatch(
    val id: String,
    val fingerprint: String,
    val sink: BatchFileSink,
) {
    private val assets = mutableListOf<PackedAsset>()
    var lineCount: Int = 0
        private set
    private var closed = false

    fun fits(jsonByteSize: Long, limits: BatchPackLimits): Boolean {
        if (lineCount + 1 > limits.maxLines) return false
        return sink.byteSize + jsonByteSize + 1 <= limits.maxFileBytes
    }

    fun append(line: PreparedLine) {
        sink.appendLine(line.jsonLine)
        assets += PackedAsset(id = line.assetId, contentHash = line.contentHash)
        lineCount += 1
    }

    fun isFull(limits: BatchPackLimits): Boolean =
        lineCount >= limits.maxLines || sink.byteSize >= limits.maxFileBytes

    fun closeSink() {
        if (closed) return
        closed = true
        sink.close()
    }

    fun toUnsealed(): UnsealedBatch = UnsealedBatch(
        id = id,
        configFingerprint = fingerprint,
        localPath = sink.path,
        lineCount = lineCount,
        byteSize = sink.byteSize,
        assets = assets.toList(),
    )
}

private fun closeAndSeal(
    open: OpenBatch,
    ledger: BatchLedger,
    config: BatchPackConfig,
): SealedBatch {
    open.closeSink()
    val unsealed = open.toUnsealed()
    ledger.seal(
        SealedBatchDraft(
            id = unsealed.id,
            configFingerprint = config.configFingerprint,
            localPath = unsealed.localPath,
            lineCount = unsealed.lineCount,
            byteSize = unsealed.byteSize,
            assets = unsealed.assets,
        ),
    )
    return SealedBatch(
        id = unsealed.id,
        localPath = unsealed.localPath,
        lineCount = unsealed.lineCount,
        byteSize = unsealed.byteSize,
        assetIds = unsealed.assets.map { it.id },
    )
}

private fun UnsealedBatch.toDraft(): SealedBatchDraft = SealedBatchDraft(
    id = id,
    configFingerprint = configFingerprint,
    localPath = localPath,
    lineCount = lineCount,
    byteSize = byteSize,
    assets = assets,
)

private fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) {
                out.append("\\u")
                out.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}
