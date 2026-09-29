package app.photoindex.core

/**
 * 设计文档第 6.1 节的模型记录。缺省字段在解析时补成空字符串或空数组，再序列化成稳定 JSON。
 */
data class ModelRecord(
    val summary: String,
    val objects: List<String> = emptyList(),
    val scene: List<String> = emptyList(),
    val platform: String = "",
    val author: String = "",
    val publishedAt: String = "",
    val ocrText: String = "",
    val tags: List<String> = emptyList(),
) {
    /** 键序固定、缺字段已补齐的 JSON。同一份记录每次写出的文本相同。 */
    fun toStableJson(): String = buildString {
        append('{')
        appendStringField(SUMMARY, summary)
        append(',')
        appendArrayField(OBJECTS, objects)
        append(',')
        appendArrayField(SCENE, scene)
        append(',')
        appendStringField(PLATFORM, platform)
        append(',')
        appendStringField(AUTHOR, author)
        append(',')
        appendStringField(PUBLISHED_AT, publishedAt)
        append(',')
        appendStringField(OCR_TEXT, ocrText)
        append(',')
        appendArrayField(TAGS, tags)
        append('}')
    }

    companion object {
        const val SUMMARY = "summary"
        const val OBJECTS = "objects"
        const val SCENE = "scene"
        const val PLATFORM = "platform"
        const val AUTHOR = "author"
        const val PUBLISHED_AT = "publishedAt"
        const val OCR_TEXT = "ocrText"
        const val TAGS = "tags"
    }
}

sealed interface ModelParseResult {
    data class Accepted(val record: ModelRecord) : ModelParseResult

    /** 这一张失败。这里不保留模型原文，避免半截文字被当成结果。 */
    data class Rejected(val reason: String) : ModelParseResult
}

const val PARSE_NOT_JSON = "不是 JSON"
const val PARSE_MISSING_SUMMARY = "缺少 summary"
const val PARSE_WRONG_FIELD_TYPE = "字段类型不对"

/**
 * 把模型输出解析成 [ModelRecord]。
 * 缺字段、空数组、空字符串都合法。整段不是 JSON，或没有字符串形式的 summary，则这一张失败。
 */
fun parseModelOutput(text: String): ModelParseResult {
    val root = readJsonValue(text) as? JsonValue.Obj ?: return ModelParseResult.Rejected(PARSE_NOT_JSON)
    if (ModelRecord.SUMMARY !in root.fields) {
        return ModelParseResult.Rejected(PARSE_MISSING_SUMMARY)
    }
    val summary = (root.fields[ModelRecord.SUMMARY] as? JsonValue.Str)?.value
        ?: return ModelParseResult.Rejected(PARSE_MISSING_SUMMARY)
    val objects = root.stringList(ModelRecord.OBJECTS) ?: return wrongType()
    val scene = root.stringList(ModelRecord.SCENE) ?: return wrongType()
    val platform = root.stringOrEmpty(ModelRecord.PLATFORM) ?: return wrongType()
    val author = root.stringOrEmpty(ModelRecord.AUTHOR) ?: return wrongType()
    val publishedAt = root.stringOrEmpty(ModelRecord.PUBLISHED_AT) ?: return wrongType()
    val ocrText = root.stringOrEmpty(ModelRecord.OCR_TEXT) ?: return wrongType()
    val tags = root.stringList(ModelRecord.TAGS) ?: return wrongType()
    return ModelParseResult.Accepted(
        ModelRecord(
            summary = summary,
            objects = objects,
            scene = scene,
            platform = platform,
            author = author,
            publishedAt = publishedAt,
            ocrText = ocrText,
            tags = tags,
        ),
    )
}

private fun wrongType(): ModelParseResult = ModelParseResult.Rejected(PARSE_WRONG_FIELD_TYPE)

private fun JsonValue.Obj.stringOrEmpty(key: String): String? {
    val value = fields[key] ?: return ""
    return (value as? JsonValue.Str)?.value
}

private fun JsonValue.Obj.stringList(key: String): List<String>? {
    val value = fields[key] ?: return emptyList()
    val array = value as? JsonValue.Arr ?: return null
    return array.items.map { item -> (item as? JsonValue.Str)?.value ?: return null }
}

private fun StringBuilder.appendStringField(key: String, value: String) {
    append('"')
    append(key)
    append("\":")
    append(escapeJsonString(value))
}

private fun StringBuilder.appendArrayField(key: String, values: List<String>) {
    append('"')
    append(key)
    append("\":[")
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        append(escapeJsonString(value))
    }
    append(']')
}

private fun escapeJsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}
