package app.photoindex.core

/**
 * 设计文档第 5.3 节的列表价，单位是元 / 百万 token，不含活动折扣。
 * 表中的千问、豆包价格是输入不超过 32k 的那一档。调用方可以覆盖单价。
 * 豆包的模型参数是用户自己的接入点 ID，预设里留空。
 */
data class ModelPreset(
    val id: String,
    val provider: String,
    val label: String,
    val model: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val thinkingEnabledByDefault: Boolean = false,
) {
    fun prices(
        inputOverride: Double? = null,
        outputOverride: Double? = null,
    ): PricePerMillion = PricePerMillion(
        inputPerMillionYuan = inputOverride ?: inputPricePerMillion,
        outputPerMillionYuan = outputOverride ?: outputPricePerMillion,
    )
}

data class PricePerMillion(
    val inputPerMillionYuan: Double,
    val outputPerMillionYuan: Double,
)

object PresetPrices {
    val qwenVlFlash = ModelPreset(
        id = "qwen3-vl-flash",
        provider = "qwen",
        label = "千问3-VL-Flash",
        model = "qwen3-vl-flash",
        inputPricePerMillion = 0.15,
        outputPricePerMillion = 1.5,
    )
    val qwenVlPlus = ModelPreset(
        id = "qwen3-vl-plus",
        provider = "qwen",
        label = "千问3-VL-Plus",
        model = "qwen3-vl-plus",
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 10.0,
    )
    val doubaoSeedMini = ModelPreset(
        id = "doubao-seed-2.0-mini",
        provider = "doubao",
        label = "豆包 Seed 2.0 Mini",
        model = "",
        inputPricePerMillion = 0.2,
        outputPricePerMillion = 2.0,
    )
    val doubaoSeedLite = ModelPreset(
        id = "doubao-seed-2.0-lite",
        provider = "doubao",
        label = "豆包 Seed 2.0 Lite",
        model = "",
        inputPricePerMillion = 0.6,
        outputPricePerMillion = 3.6,
    )
    val zhipuGlm45v = ModelPreset(
        id = "glm-4.5v",
        provider = "zhipu",
        label = "智谱 GLM-4.5V",
        model = "glm-4.5v",
        inputPricePerMillion = 2.0,
        outputPricePerMillion = 6.0,
    )
    val gemini25Flash = ModelPreset(
        id = "gemini-2.5-flash",
        provider = "gemini",
        label = "Gemini 2.5 Flash",
        model = "gemini-2.5-flash",
        inputPricePerMillion = 2.16,
        outputPricePerMillion = 18.0,
    )

    val all: List<ModelPreset> = listOf(
        qwenVlFlash,
        qwenVlPlus,
        doubaoSeedMini,
        doubaoSeedLite,
        zhipuGlm45v,
        gemini25Flash,
    )

    /** 设计文档第 5.1 节的默认服务商。 */
    val defaultPreset: ModelPreset = qwenVlFlash
}

/**
 * 除 API Key 以外、估价和指纹会读到的默认设置。单价来自 [PresetPrices.defaultPreset]，
 * 与 storage 里打开库时写入的默认行一致。
 */
data class RecognitionSettings(
    val provider: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val thinkingTokenLimit: Int,
    val longEdge: Int?,
    val jpegQuality: Int,
    val detailLevel: DetailLevel,
    val promptVersion: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val matchMode: String,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
) {
    fun fingerprintInput(): ConfigFingerprintInput = ConfigFingerprintInput(
        provider = provider,
        model = model,
        thinkingEnabled = thinkingEnabled,
        thinkingTokenLimit = thinkingTokenLimit,
        longEdge = longEdge,
        jpegQuality = jpegQuality,
        detailLevel = detailLevel,
        promptVersion = promptVersion,
    )

    fun fingerprint(): String = configFingerprint(fingerprintInput())

    fun prices(): PricePerMillion = PricePerMillion(inputPricePerMillion, outputPricePerMillion)

    fun overridingPrices(
        inputPerMillionYuan: Double = inputPricePerMillion,
        outputPerMillionYuan: Double = outputPricePerMillion,
    ): RecognitionSettings = copy(
        inputPricePerMillion = inputPerMillionYuan,
        outputPricePerMillion = outputPerMillionYuan,
    )
}

fun defaultRecognitionSettings(
    preset: ModelPreset = PresetPrices.defaultPreset,
    inputPricePerMillion: Double = preset.inputPricePerMillion,
    outputPricePerMillion: Double = preset.outputPricePerMillion,
): RecognitionSettings = RecognitionSettings(
    provider = preset.provider,
    model = preset.model,
    thinkingEnabled = preset.thinkingEnabledByDefault,
    thinkingTokenLimit = DEFAULT_THINKING_TOKEN_LIMIT,
    longEdge = DEFAULT_LONG_EDGE,
    jpegQuality = DEFAULT_JPEG_QUALITY,
    detailLevel = DetailLevel.DETAILED,
    promptVersion = PROMPT_VERSION,
    inputPricePerMillion = inputPricePerMillion,
    outputPricePerMillion = outputPricePerMillion,
    matchMode = "any",
    wifiOnly = true,
    chargingOnly = true,
)

private const val DEFAULT_THINKING_TOKEN_LIMIT = 1024
private const val DEFAULT_LONG_EDGE = 1280
private const val DEFAULT_JPEG_QUALITY = 80
