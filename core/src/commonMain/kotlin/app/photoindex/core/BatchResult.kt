package app.photoindex.core

/**
 * 服务商侧一次批量任务的查询结果。下载用 [outputFileId]，不在这里带结果正文。
 * 已经有任务 ID 时，查询失败也不能当成「还没上传」。
 */
data class RemoteBatch(
    val phase: RemoteBatchPhase,
    val outputFileId: String?,
    val error: String?,
)

enum class RemoteBatchPhase {
    RUNNING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
}

sealed interface BatchResultLine {
    val customId: String

    data class Ok(
        override val customId: String,
        val content: String,
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : BatchResultLine

    data class Bad(
        override val customId: String,
        val reason: String,
    ) : BatchResultLine
}

/**
 * 读百炼 OpenAI 兼容批量结果里的一行。
 * 没有 custom_id 时返回 null，调用方不能把这一行安到某一张图上。
 * 服务商错误、非 200、正文不是合法识别 JSON，都只让这一张失败。
 */
fun parseBatchResultLine(line: String): BatchResultLine? {
    val root = readJsonValue(line) as? JsonValue.Obj ?: return null
    val customId = root.string("custom_id") ?: return null
    val error = root.fields["error"]
    if (error is JsonValue.Obj) {
        return BatchResultLine.Bad(customId, error.string("message") ?: "这一行失败")
    }
    val response = root.fields["response"] as? JsonValue.Obj
        ?: return BatchResultLine.Bad(customId, "没有结果")
    val status = response.long("status_code")
    if (status != null && status != 200L) {
        val message = response.obj("body")?.obj("error")?.string("message")
        return BatchResultLine.Bad(customId, message ?: "状态码 $status")
    }
    val content = response.obj("body")
        ?.array("choices")
        ?.firstOrNull()
        ?.asObj()
        ?.obj("message")
        ?.string("content")
        ?: return BatchResultLine.Bad(customId, "没有识别正文")
    val usage = response.obj("body")?.obj("usage")
    return BatchResultLine.Ok(
        customId = customId,
        content = content,
        inputTokens = usage?.long("prompt_tokens"),
        outputTokens = usage?.long("completion_tokens"),
    )
}

/** 测试和假服务商用来写一行成功结果。正文是模型 JSON 字符串。 */
fun successfulBatchResultLine(
    customId: String,
    modelJson: String,
    inputTokens: Long,
    outputTokens: Long,
): String = buildString {
    append("""{"custom_id":""")
    append(quote(customId))
    append(""","response":{"status_code":200,"body":{"choices":[{"message":{"content":""")
    append(quote(modelJson))
    append("""}}],"usage":{"prompt_tokens":""")
    append(inputTokens)
    append(""","completion_tokens":""")
    append(outputTokens)
    append("""}}},"error":null}""")
}

/** 这一行请求已经结束，但是失败了。同批其他行不受影响。 */
fun failedBatchResultLine(customId: String, message: String): String = buildString {
    append("""{"custom_id":""")
    append(quote(customId))
    append(""","response":null,"error":{"message":""")
    append(quote(message))
    append("}}")
}

private fun JsonValue.Obj.string(key: String): String? = (fields[key] as? JsonValue.Str)?.value

private fun JsonValue.Obj.long(key: String): Long? = (fields[key] as? JsonValue.Num)?.literal?.toLongOrNull()

private fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

private fun JsonValue.Obj.array(key: String): List<JsonValue>? = (fields[key] as? JsonValue.Arr)?.items

private fun JsonValue.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

private fun quote(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
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
