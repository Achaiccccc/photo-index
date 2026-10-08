package app.photoindex.core

/**
 * 设计文档第 5.1 节里除 API Key 以外的设置。
 * 密钥不进这个对象，也不进索引库。
 */
data class IndexSettings(
    val provider: String,
    val endpoint: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val thinkingTokenLimit: Int,
    val longEdge: Int?,
    val jpegQuality: Int,
    val detailLevel: DetailLevel,
    val uploadMode: String,
    val batchMaxBytes: Long,
    val batchMaxLines: Int,
    val concurrentBatches: Int,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val matchMode: String,
    val amountAlertYuan: Double,
    val synonyms: String,
    val deleteResultsOutOfScope: Boolean,
) {
    fun toRecognitionSettings(): RecognitionSettings = RecognitionSettings(
        provider = provider,
        model = model,
        thinkingEnabled = thinkingEnabled,
        thinkingTokenLimit = thinkingTokenLimit,
        longEdge = longEdge,
        jpegQuality = jpegQuality,
        detailLevel = detailLevel,
        promptVersion = PROMPT_VERSION,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        matchMode = matchMode,
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
    )

    fun applyingProvider(providerId: String): IndexSettings {
        val option = ProviderCatalog.require(providerId)
        val preset = ProviderCatalog.presetsFor(providerId).firstOrNull()
        val switched = copy(
            provider = option.id,
            endpoint = option.endpoint ?: endpoint,
        )
        return if (preset == null) switched else switched.applyingPreset(preset)
    }

    fun applyingPreset(preset: ModelPreset): IndexSettings = copy(
        provider = preset.provider,
        model = preset.model,
        inputPricePerMillion = preset.inputPricePerMillion,
        outputPricePerMillion = preset.outputPricePerMillion,
        thinkingEnabled = preset.thinkingEnabledByDefault,
    )
}

fun defaultIndexSettings(): IndexSettings = IndexSettings(
    provider = "qwen",
    endpoint = QWEN_COMPATIBLE_BASE_URL,
    model = PresetPrices.qwenVlFlash.model,
    thinkingEnabled = false,
    thinkingTokenLimit = 1024,
    longEdge = 1280,
    jpegQuality = 80,
    detailLevel = DetailLevel.DETAILED,
    uploadMode = UPLOAD_MODE_BATCH_FILE,
    batchMaxBytes = DEFAULT_BATCH_MAX_BYTES,
    batchMaxLines = 2000,
    concurrentBatches = 1,
    wifiOnly = true,
    chargingOnly = true,
    inputPricePerMillion = PresetPrices.qwenVlFlash.inputPricePerMillion,
    outputPricePerMillion = PresetPrices.qwenVlFlash.outputPricePerMillion,
    matchMode = "any",
    amountAlertYuan = DEFAULT_AMOUNT_ALERT_YUAN,
    synonyms = DEFAULT_SYNONYM_TABLE,
    deleteResultsOutOfScope = false,
)

fun validateIndexSettings(settings: IndexSettings) {
    require(settings.provider in ProviderCatalog.ids) { "服务商不在预置列表里" }
    require(settings.endpoint.isNotBlank()) { "接口地址不能为空" }
    require(settings.thinkingTokenLimit > 0) { "思考上限必须是正数" }
    require(settings.longEdge == null || settings.longEdge in LONG_EDGE_CHOICES) { "长边只能是 512、768、1280、1600 或不压缩" }
    require(settings.jpegQuality in 60..90) { "JPEG 质量要在 60 到 90 之间" }
    require(settings.uploadMode == UPLOAD_MODE_BATCH_FILE || settings.uploadMode == UPLOAD_MODE_REALTIME) {
        "上传方式不正确"
    }
    require(settings.batchMaxBytes > 0L) { "每批体积必须是正数" }
    require(settings.batchMaxLines in 1..10_000) { "每批行数要在 1 到 10000 之间" }
    require(settings.concurrentBatches in 1..2) { "同时批次数只能是 1 或 2" }
    require(settings.inputPricePerMillion >= 0.0 && settings.inputPricePerMillion.isUsableNumber()) { "输入单价不能为负" }
    require(settings.outputPricePerMillion >= 0.0 && settings.outputPricePerMillion.isUsableNumber()) { "输出单价不能为负" }
    require(settings.matchMode == "any" || settings.matchMode == "all") { "匹配方式不正确" }
    require(settings.amountAlertYuan >= 0.0 && settings.amountAlertYuan.isUsableNumber()) { "金额提醒线不能为负" }
}

private fun Double.isUsableNumber(): Boolean = !isNaN() && !isInfinite()

/** 预置服务商只负责填地址、模型和单价。除千问外，这一版选中后标明尚未接入。 */
data class ProviderOption(
    val id: String,
    val label: String,
    val endpoint: String?,
    val batchFileReady: Boolean,
    val note: String?,
)

object ProviderCatalog {
    val qwen = ProviderOption(
        id = "qwen",
        label = "千问",
        endpoint = QWEN_COMPATIBLE_BASE_URL,
        batchFileReady = true,
        note = null,
    )
    val doubao = ProviderOption(
        id = "doubao",
        label = "豆包",
        endpoint = "https://ark.cn-beijing.volces.com/api/v3",
        batchFileReady = false,
        note = "尚未接入。确认不会上传。",
    )
    val zhipu = ProviderOption(
        id = "zhipu",
        label = "智谱",
        endpoint = "https://open.bigmodel.cn/api/paas/v4",
        batchFileReady = false,
        note = "尚未接入。确认不会上传。",
    )
    val gemini = ProviderOption(
        id = "gemini",
        label = "Gemini",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/openai",
        batchFileReady = false,
        note = "尚未接入。确认不会上传。",
    )
    val custom = ProviderOption(
        id = "custom",
        label = "自定义",
        endpoint = null,
        batchFileReady = false,
        note = "尚未接入。打开深度思考时，思考字段还不能确认，这一版不会发起请求。",
    )

    val options: List<ProviderOption> = listOf(qwen, doubao, zhipu, gemini, custom)
    val ids: Set<String> = options.map { it.id }.toSet()

    fun require(id: String): ProviderOption = options.first { it.id == id }

    fun presetsFor(providerId: String): List<ModelPreset> =
        PresetPrices.all.filter { it.provider == providerId }
}

const val UPLOAD_MODE_BATCH_FILE = "batch_file"
const val UPLOAD_MODE_REALTIME = "realtime"
const val DEFAULT_AMOUNT_ALERT_YUAN = 5.0
const val DEFAULT_BATCH_MAX_BYTES = 400L * 1024L * 1024L

val LONG_EDGE_CHOICES: Set<Int> = setOf(512, 768, 1280, 1600)
