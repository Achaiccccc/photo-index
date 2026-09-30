package app.photoindex.core

/**
 * 百炼 OpenAI 兼容模式，北京地域。调用方可以改地址，测试把地址指到本机。
 * 模型名的默认值在 [PresetPrices.qwenVlFlash]，不在这里重复。
 */
const val QWEN_COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

/** 设计文档第 5.4 节：任务窗口按文档最短填 24 小时。 */
const val QWEN_COMPLETION_WINDOW = "24h"

const val QWEN_BATCH_ENDPOINT = "/v1/chat/completions"

/** 创建、查询、下载各算第一次。暂时失败后再试两次，一共三次。 */
const val QWEN_TRANSIENT_ATTEMPTS = 3

/**
 * 第 1 次失败等 200 毫秒，第 2 次等 400 毫秒。次数再多也只加倍到第 5 档，避免一次睡太久。
 */
fun qwenBackoffMillis(failedAttempt: Int): Long {
    require(failedAttempt in 1..4) { "退避次数超出范围" }
    return 200L shl (failedAttempt - 1)
}

class QwenHttpException(message: String) : RuntimeException(message)

class HttpTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class OutboundHttp(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

data class InboundHttp(
    val status: Int,
    val body: ByteArray,
)

interface HttpTransport {
    fun exchange(request: OutboundHttp): InboundHttp
}

interface BinaryFiles {
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray)
}

expect fun defaultHttpTransport(): HttpTransport

expect fun defaultBinaryFiles(): BinaryFiles

expect fun defaultSleeper(): (Long) -> Unit

fun qwenBatchProvider(
    apiKey: String,
    baseUrl: String = QWEN_COMPATIBLE_BASE_URL,
): BatchProvider = QwenBatchProvider(
    apiKey = apiKey,
    baseUrl = baseUrl,
    transport = defaultHttpTransport(),
    files = defaultBinaryFiles(),
    sleep = defaultSleeper(),
)

/**
 * 百炼 OpenAI 兼容 Batch File。状态机只看见 [BatchProvider]。
 * 上传不重试，避免一次暂时失败变成两份文件。创建、查询、下载遇到暂时失败才退避。
 * 查询只打任务地址，不会因为失败改去上传。
 */
class QwenBatchProvider(
    private val apiKey: String,
    private val baseUrl: String = QWEN_COMPATIBLE_BASE_URL,
    private val transport: HttpTransport,
    private val files: BinaryFiles,
    private val sleep: (Long) -> Unit,
    private val maxAttempts: Int = QWEN_TRANSIENT_ATTEMPTS,
) : BatchProvider {
    init {
        require(apiKey.isNotEmpty()) { "API Key 不能为空" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "接口地址要以 http 开头" }
        require(maxAttempts >= 1) { "至少要请求一次" }
    }

    override fun upload(localPath: String): String {
        val bytes = files.read(localPath)
        val boundary = "photo-index-" + sha256Hex(bytes).take(16)
        val body = multipart(boundary, fileName(localPath), bytes)
        val response = exchange(
            method = "POST",
            path = "/files",
            body = body,
            contentType = "multipart/form-data; boundary=$boundary",
            retry = false,
        )
        return responseId(requireOk(response, "上传文件"), "上传文件")
    }

    override fun createTask(remoteFileId: String): String {
        val body = buildString {
            append("""{"input_file_id":""")
            append(quote(remoteFileId))
            append(""","endpoint":""")
            append(quote(QWEN_BATCH_ENDPOINT))
            append(""","completion_window":""")
            append(quote(QWEN_COMPLETION_WINDOW))
            append("}")
        }.encodeToByteArray()
        val response = exchange(
            method = "POST",
            path = "/batches",
            body = body,
            contentType = "application/json",
            retry = true,
        )
        return responseId(requireOk(response, "创建任务"), "创建任务")
    }

    override fun query(remoteBatchId: String): RemoteBatch {
        val response = exchange(
            method = "GET",
            path = "/batches/${segment(remoteBatchId)}",
            body = null,
            contentType = null,
            retry = true,
        )
        return parseRemoteBatch(requireOk(response, "查询任务").body)
    }

    override fun download(outputFileId: String, destinationPath: String) {
        val response = exchange(
            method = "GET",
            path = "/files/${segment(outputFileId)}/content",
            body = null,
            contentType = null,
            retry = true,
        )
        files.write(destinationPath, requireOk(response, "下载结果").body)
    }

    override fun cancel(remoteBatchId: String) {
        val response = exchange(
            method = "POST",
            path = "/batches/${segment(remoteBatchId)}/cancel",
            body = null,
            contentType = null,
            retry = false,
        )
        requireOk(response, "取消任务")
    }

    override fun deleteRemoteFile(remoteFileId: String) {
        val response = exchange(
            method = "DELETE",
            path = "/files/${segment(remoteFileId)}",
            body = null,
            contentType = null,
            retry = false,
        )
        if (response.status == 404) return
        requireOk(response, "删除文件")
    }

    private fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        retry: Boolean,
    ): InboundHttp {
        val headers = linkedMapOf("Authorization" to "Bearer $apiKey")
        if (contentType != null) headers["Content-Type"] = contentType
        val request = OutboundHttp(method, url(path), headers, body)
        var attempt = 1
        while (true) {
            val response = try {
                transport.exchange(request)
            } catch (error: HttpTransportException) {
                if (!retry || attempt >= maxAttempts) throw error
                sleep(qwenBackoffMillis(attempt))
                attempt++
                continue
            }
            if (retry && response.status.isTemporary() && attempt < maxAttempts) {
                sleep(qwenBackoffMillis(attempt))
                attempt++
                continue
            }
            return response
        }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun segment(id: String): String {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' || it == '?' || it == '#' || it.isWhitespace() }) {
            "远端 ID 不能包含路径分隔符"
        }
        return id
    }
}

private fun requireOk(response: InboundHttp, action: String): InboundHttp {
    if (response.status in 200..299) return response
    val detail = response.body.decodeToString().take(180)
    throw QwenHttpException("$action 失败：HTTP ${response.status} $detail")
}

private fun responseId(response: InboundHttp, action: String): String {
    val root = readJsonValue(response.body.decodeToString()) as? JsonValue.Obj
        ?: throw QwenHttpException("$action 的响应不是 JSON")
    return root.string("id") ?: throw QwenHttpException("$action 的响应没有 id")
}

internal fun parseRemoteBatch(body: ByteArray): RemoteBatch {
    val root = readJsonValue(body.decodeToString()) as? JsonValue.Obj
        ?: throw QwenHttpException("查询响应不是 JSON")
    val status = root.string("status") ?: throw QwenHttpException("查询响应没有 status")
    val phase = when (status) {
        "validating", "in_progress", "finalizing", "cancelling" -> RemoteBatchPhase.RUNNING
        "completed" -> RemoteBatchPhase.COMPLETED
        "failed" -> RemoteBatchPhase.FAILED
        "expired" -> RemoteBatchPhase.EXPIRED
        "cancelled" -> RemoteBatchPhase.CANCELLED
        else -> throw QwenHttpException("不认识的批量任务状态：$status")
    }
    return RemoteBatch(
        phase = phase,
        outputFileId = root.string("output_file_id"),
        error = root.errorMessage(),
        errorFileId = root.string("error_file_id"),
    )
}

private fun JsonValue.Obj.errorMessage(): String? {
    val errors = fields["errors"] as? JsonValue.Obj ?: return null
    errors.string("message")?.let { return it }
    val first = (errors.fields["data"] as? JsonValue.Arr)?.items?.firstOrNull() as? JsonValue.Obj
    return first?.string("message")
}

private fun JsonValue.Obj.string(key: String): String? = (fields[key] as? JsonValue.Str)?.value

private fun Int.isTemporary(): Boolean = this == 408 || this == 429 || this in 500..599

private fun fileName(path: String): String {
    val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val name = if (slash >= 0) path.substring(slash + 1) else path
    return name.replace("\"", "").ifEmpty { "batch.jsonl" }
}

private fun multipart(boundary: String, filename: String, file: ByteArray): ByteArray {
    val head = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n")
        append("batch\r\n")
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n")
        append("Content-Type: application/octet-stream\r\n\r\n")
    }.encodeToByteArray()
    val tail = "\r\n--$boundary--\r\n".encodeToByteArray()
    return head + file + tail
}

private fun quote(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}
