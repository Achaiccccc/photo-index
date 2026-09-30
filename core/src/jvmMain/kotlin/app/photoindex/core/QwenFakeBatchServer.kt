package app.photoindex.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** 测试里的假 Key。不是阿里云密钥，索引库和请求地址里也不该出现别的 Key。 */
const val QWEN_FAKE_API_KEY = "test-key"

/**
 * 在 127.0.0.1 上回放百炼批量文件接口。只给 JVM 测试用，不编进 Android。
 * 上传要求 purpose=batch，创建任务要求 completion_window 为 24h、endpoint 为聊天补全。
 */
class QwenFakeBatchServer(
    var status: String = "completed",
    var resultText: String = "",
    var errorText: String = "",
    var outputFileId: String? = "out-1",
    var errorFileId: String? = null,
    var taskErrorMessage: String? = null,
    private val apiKey: String = QWEN_FAKE_API_KEY,
) : AutoCloseable {
    private val lock = Any()
    private val recorded = mutableListOf<String>()
    private val failures = ArrayDeque<PlannedFailure>()
    private var fileSerial = 0
    private var taskSerial = 0
    private var lastUpload = ByteArray(0)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "qwen-fake").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    val calls: List<String>
        get() = synchronized(lock) { recorded.toList() }

    val uploadedFileBytes: ByteArray
        get() = synchronized(lock) { lastUpload.copyOf() }

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun failNext(kind: String, httpStatus: Int, times: Int = 1) {
        require(times > 0)
        synchronized(lock) {
            repeat(times) { failures.add(PlannedFailure(kind, httpStatus)) }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes()
            val kind = kindOf(method, path)
            synchronized(lock) { recorded += "$method $path" }
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            if (authorization != "Bearer $apiKey") {
                send(exchange, 401, """{"error":"unauthorized"}""".encodeToByteArray(), null)
                return
            }
            if (kind == "upload" && !validUpload(exchange, body)) return
            if (kind == "create" && !validCreate(exchange, body)) return
            val planned = synchronized(lock) {
                val index = failures.indexOfFirst { it.kind == kind }
                if (index < 0) null else failures.removeAt(index)
            }
            if (planned != null) {
                val location = if (planned.httpStatus == 302) {
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/batches/remote"
                } else {
                    null
                }
                send(exchange, planned.httpStatus, """{"error":"temporary"}""".encodeToByteArray(), location)
                return
            }
            when (kind) {
                "upload" -> {
                    val extracted = try {
                        extractFile(exchange.requestHeaders.getFirst("Content-Type").orEmpty(), body)
                    } catch (error: IllegalArgumentException) {
                        send(exchange, 400, """{"error":${quoteJson(error.message ?: "上传格式不对")}}""".encodeToByteArray(), null)
                        return
                    }
                    val id = synchronized(lock) {
                        lastUpload = extracted
                        fileSerial += 1
                        "file-$fileSerial"
                    }
                    send(exchange, 200, fileJson(id), null)
                }
                "create" -> {
                    val id = synchronized(lock) {
                        taskSerial += 1
                        "task-$taskSerial"
                    }
                    send(exchange, 200, batchJson(id, "validating"), null)
                }
                "query" -> {
                    val id = path.removePrefix("/batches/")
                    send(exchange, 200, batchJson(id, status), null)
                }
                "download" -> {
                    val fileId = path.removePrefix("/files/").removeSuffix("/content")
                    val text = if (fileId == errorFileId) errorText else resultText
                    send(exchange, 200, text.encodeToByteArray(), null)
                }
                "cancel" -> {
                    val id = path.removePrefix("/batches/").removeSuffix("/cancel")
                    send(exchange, 200, batchJson(id, "cancelling"), null)
                }
                "delete" -> {
                    val id = path.removePrefix("/files/")
                    send(exchange, 200, """{"id":${quoteJson(id)},"object":"file","deleted":true}""".encodeToByteArray(), null)
                }
                else -> send(exchange, 404, """{"error":"unknown"}""".encodeToByteArray(), null)
            }
        } catch (error: Exception) {
            send(exchange, 500, (error.message ?: "fake server").encodeToByteArray(), null)
        } finally {
            exchange.close()
        }
    }

    private fun validUpload(exchange: HttpExchange, body: ByteArray): Boolean {
        val text = body.decodeToString()
        val purpose = Regex("""name="purpose"\r\n\r\nbatch\r\n""")
        if (!purpose.containsMatchIn(text)) {
            send(exchange, 400, """{"error":"purpose 必须是 batch"}""".encodeToByteArray(), null)
            return false
        }
        return true
    }

    private fun validCreate(exchange: HttpExchange, body: ByteArray): Boolean {
        val root = readJsonValue(body.decodeToString()) as? JsonValue.Obj
        val window = (root?.fields?.get("completion_window") as? JsonValue.Str)?.value
        val endpoint = (root?.fields?.get("endpoint") as? JsonValue.Str)?.value
        val input = (root?.fields?.get("input_file_id") as? JsonValue.Str)?.value
        if (window != QWEN_COMPLETION_WINDOW || endpoint != QWEN_BATCH_ENDPOINT || input.isNullOrEmpty()) {
            send(exchange, 400, """{"error":"创建任务的窗口、endpoint 或文件 ID 不对"}""".encodeToByteArray(), null)
            return false
        }
        return true
    }

    private fun batchJson(id: String, batchStatus: String): ByteArray {
        val output = if (batchStatus == "validating" || batchStatus == "in_progress" || batchStatus == "finalizing" || batchStatus == "cancelling") {
            null
        } else {
            outputFileId
        }
        val errors = taskErrorMessage?.let { """{"message":${quoteJson(it)}}""" } ?: "null"
        return buildString {
            append("""{"id":${quoteJson(id)},"object":"batch","endpoint":"/v1/chat/completions","completion_window":"24h","status":${quoteJson(batchStatus)},"output_file_id":""")
            append(jsonOrNull(output))
            append(""","error_file_id":""")
            append(jsonOrNull(errorFileId))
            append(""","errors":""")
            append(errors)
            append("}")
        }.encodeToByteArray()
    }

    private fun fileJson(id: String): ByteArray =
        """{"id":${quoteJson(id)},"object":"file","purpose":"batch","status":"processed"}""".encodeToByteArray()

    private fun send(exchange: HttpExchange, status: Int, body: ByteArray, location: String?) {
        if (location != null) exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

private data class PlannedFailure(val kind: String, val httpStatus: Int)

private fun kindOf(method: String, path: String): String = when {
    method == "POST" && path == "/files" -> "upload"
    method == "POST" && path == "/batches" -> "create"
    method == "POST" && path.startsWith("/batches/") && path.endsWith("/cancel") -> "cancel"
    method == "GET" && path.startsWith("/batches/") -> "query"
    method == "GET" && path.startsWith("/files/") && path.endsWith("/content") -> "download"
    method == "DELETE" && path.startsWith("/files/") -> "delete"
    else -> "other"
}

private fun extractFile(contentType: String, body: ByteArray): ByteArray {
    val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
    require(boundary.isNotEmpty()) { "上传没有 boundary" }
    val marker = "name=\"file\"".encodeToByteArray()
    val headerAt = indexOf(body, marker)
    require(headerAt >= 0) { "上传没有文件字段" }
    val separator = "\r\n\r\n".encodeToByteArray()
    val headerEnd = indexOf(body, separator, headerAt)
    require(headerEnd >= 0) { "上传文件头不完整" }
    val dataStart = headerEnd + separator.size
    val end = indexOf(body, "\r\n--$boundary".encodeToByteArray(), dataStart)
    require(end >= 0) { "上传文件结尾不完整" }
    return body.copyOfRange(dataStart, end)
}

private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty() || from > data.size) return -1
    var index = from
    while (index + needle.size <= data.size) {
        var matched = true
        for (offset in needle.indices) {
            if (data[index + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return index
        index++
    }
    return -1
}

private fun jsonOrNull(value: String?): String = if (value == null) "null" else quoteJson(value)

private fun quoteJson(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(ch)
        }
    }
    append('"')
}

/** 测试用。地址不是本机时直接失败，不会发出去。 */
fun qwenBatchProviderForTests(
    server: QwenFakeBatchServer,
    sleep: (Long) -> Unit = {},
    maxAttempts: Int = QWEN_TRANSIENT_ATTEMPTS,
): QwenBatchProvider = QwenBatchProvider(
    apiKey = QWEN_FAKE_API_KEY,
    baseUrl = server.baseUrl,
    transport = rejectingNonLoopback(defaultHttpTransport()),
    files = defaultBinaryFiles(),
    sleep = sleep,
    maxAttempts = maxAttempts,
)

internal fun rejectingNonLoopback(delegate: HttpTransport): HttpTransport = object : HttpTransport {
    override fun exchange(request: OutboundHttp): InboundHttp {
        val host = request.url.substringAfter("://").substringBefore('/').substringBefore(':')
        check(host == "127.0.0.1" || host == "localhost") { "测试里的请求不能离开本机：$host" }
        return delegate.exchange(request)
    }
}
