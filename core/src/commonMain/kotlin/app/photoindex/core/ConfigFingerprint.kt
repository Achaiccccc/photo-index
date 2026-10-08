package app.photoindex.core

/**
 * 提示词正文在 [recognitionPrompt]。改提示词时递增这个版本，已识别的图才会因指纹变化而需要重跑。
 */
const val PROMPT_VERSION = "2"

enum class DetailLevel(
    val storageValue: String,
    val promptTokens: Long,
    val plannedOutputTokens: Long,
    val outputLowFactor: Double,
    val outputHighFactor: Double,
) {
    BRIEF("brief", 150, 80, 0.5, 1.5),
    DETAILED("detailed", 400, 600, 0.4, 2.5),
    ;

    companion object {
        fun fromStorage(value: String): DetailLevel =
            entries.first { it.storageValue == value }
    }
}

/**
 * 参与配置指纹的字段。单价、匹配方式、仅 Wi-Fi、仅充电不在这里，
 * 避免只改估价或运行条件就把已识别的图标成需要重跑。
 */
data class ConfigFingerprintInput(
    val provider: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val thinkingTokenLimit: Int,
    val longEdge: Int?,
    val jpegQuality: Int,
    val detailLevel: DetailLevel,
    val promptVersion: String,
)

/**
 * 稳定、可入库的配置指纹。字段顺序和分隔符属于指纹格式，改了会使旧指纹全部失效。
 */
fun configFingerprint(input: ConfigFingerprintInput): String =
    listOf(
        FINGERPRINT_VERSION,
        input.provider,
        input.model,
        if (input.thinkingEnabled) "1" else "0",
        input.thinkingTokenLimit.toString(),
        input.longEdge?.toString().orEmpty(),
        input.jpegQuality.toString(),
        input.detailLevel.storageValue,
        input.promptVersion,
    ).joinToString(FINGERPRINT_SEPARATOR)

private const val FINGERPRINT_VERSION = "1"
private const val FINGERPRINT_SEPARATOR = "\u001F"
