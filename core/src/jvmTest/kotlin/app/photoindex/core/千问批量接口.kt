package app.photoindex.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 假服务器只听 127.0.0.1。传输层会拒绝公网地址，所以这些测试不会打到阿里云。
 */
class 千问批量接口 {
    @Test
    fun 默认地址是百炼兼容模式且窗口是24小时() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", QWEN_COMPATIBLE_BASE_URL)
        assertEquals("24h", QWEN_COMPLETION_WINDOW)
        assertEquals("/v1/chat/completions", QWEN_BATCH_ENDPOINT)
        assertEquals("qwen3-vl-flash", PresetPrices.qwenVlFlash.model)
        assertEquals(200L, qwenBackoffMillis(1))
        assertEquals(400L, qwenBackoffMillis(2))
    }

    @Test
    fun 回环检查拒绝公网地址() {
        val transport = rejectingNonLoopback(object : HttpTransport {
            override fun exchange(request: OutboundHttp): InboundHttp = error("不应该发出去")
        })
        val error = assertFailsWith<IllegalStateException> {
            transport.exchange(
                OutboundHttp(
                    method = "GET",
                    url = "https://dashscope.aliyuncs.com/compatible-mode/v1/batches/x",
                    headers = emptyMap(),
                    body = null,
                ),
            )
        }
        assertTrue(error.message!!.contains("dashscope.aliyuncs.com"))
    }

    @Test
    fun 上传创建查询下载取消和删除都打到本机() {
        val result = "result-line"
        QwenFakeBatchServer(resultText = result).use { server ->
            val provider = qwenBatchProviderForTests(server)
            val file = Files.createTempFile("qwen-batch", ".jsonl").toFile()
            val saved = Files.createTempFile("qwen-result", ".jsonl").toFile()
            try {
                file.writeText(sampleLine())
                assertEquals("file-1", provider.upload(file.absolutePath))
                assertTrue(server.uploadedFileBytes.contentEquals(file.readBytes()))
                assertEquals("task-1", provider.createTask("file-1"))
                val remote = provider.query("task-1")
                assertEquals(RemoteBatchPhase.COMPLETED, remote.phase)
                assertEquals("out-1", remote.outputFileId)
                provider.download("out-1", saved.absolutePath)
                assertEquals(result, saved.readText())
                provider.cancel("task-1")
                provider.deleteRemoteFile("file-1")
                provider.deleteRemoteFile("out-1")
            } finally {
                file.delete()
                saved.delete()
            }
            assertEquals(
                listOf(
                    "POST /files",
                    "POST /batches",
                    "GET /batches/task-1",
                    "GET /files/out-1/content",
                    "POST /batches/task-1/cancel",
                    "DELETE /files/file-1",
                    "DELETE /files/out-1",
                ),
                server.calls,
            )
            assertTrue(server.calls.none { it.contains(QWEN_FAKE_API_KEY) || it.contains("dashscope") })
        }
    }

    @Test
    fun 上传的JSONL带默认模型并显式关闭思考() {
        QwenFakeBatchServer().use { server ->
            val provider = qwenBatchProviderForTests(server)
            val file = Files.createTempFile("qwen-line", ".jsonl").toFile()
            try {
                file.writeText(sampleLine())
                provider.upload(file.absolutePath)
            } finally {
                file.delete()
            }
            val text = server.uploadedFileBytes.decodeToString()
            assertTrue(text.contains("\"model\":\"qwen3-vl-flash\""))
            assertTrue(text.contains("\"enable_thinking\":false"))
            assertTrue(text.contains("\"max_tokens\":600"))
            assertTrue(text.contains("\"custom_id\":\"p1\""))
        }
    }

    @Test
    fun 创建遇到503会退避且不会再上传一次() {
        QwenFakeBatchServer().use { server ->
            server.failNext("create", 503, times = 2)
            val delays = mutableListOf<Long>()
            val provider = qwenBatchProviderForTests(server, sleep = { delays += it })
            val file = Files.createTempFile("qwen-retry", ".jsonl").toFile()
            try {
                file.writeText(sampleLine())
                provider.upload(file.absolutePath)
                assertEquals("task-1", provider.createTask("file-1"))
            } finally {
                file.delete()
            }
            assertEquals(1, server.calls.count { it == "POST /files" })
            assertEquals(3, server.calls.count { it == "POST /batches" })
            assertEquals(listOf(200L, 400L), delays)
        }
    }

    @Test
    fun 查询三次都失败后停止且没有上传() {
        QwenFakeBatchServer().use { server ->
            server.failNext("query", 503, times = 5)
            val delays = mutableListOf<Long>()
            val provider = qwenBatchProviderForTests(server, sleep = { delays += it })
            val error = assertFailsWith<QwenHttpException> { provider.query("task-9") }
            assertTrue(error.message!!.contains("503"))
            assertEquals(listOf(200L, 400L), delays)
            assertEquals(listOf("GET /batches/task-9", "GET /batches/task-9", "GET /batches/task-9"), server.calls)
        }
    }

    @Test
    fun 下载暂时失败后仍写回完整结果() {
        QwenFakeBatchServer(resultText = "hello-result").use { server ->
            server.failNext("download", 503, times = 1)
            val provider = qwenBatchProviderForTests(server, sleep = { })
            val saved = Files.createTempFile("qwen-download", ".jsonl").toFile()
            try {
                provider.download("out-1", saved.absolutePath)
                assertEquals("hello-result", saved.readText())
            } finally {
                saved.delete()
            }
            assertEquals(2, server.calls.count { it == "GET /files/out-1/content" })
        }
    }

    @Test
    fun 客户端错误和重定向都不退避() {
        QwenFakeBatchServer().use { server ->
            server.failNext("create", 400)
            val delays = mutableListOf<Long>()
            val provider = qwenBatchProviderForTests(server, sleep = { delays += it })
            assertFailsWith<QwenHttpException> { provider.createTask("file-1") }
            assertTrue(delays.isEmpty())
            assertEquals(listOf("POST /batches"), server.calls)
        }
        QwenFakeBatchServer().use { server ->
            server.failNext("query", 302)
            val provider = qwenBatchProviderForTests(server)
            val error = assertFailsWith<QwenHttpException> { provider.query("task-1") }
            assertTrue(error.message!!.contains("302"))
            assertEquals(listOf("GET /batches/task-1"), server.calls)
        }
        QwenFakeBatchServer(apiKey = "other").use { server ->
            val delays = mutableListOf<Long>()
            val provider = qwenBatchProviderForTests(server, sleep = { delays += it })
            assertFailsWith<QwenHttpException> { provider.query("task-1") }
            assertTrue(delays.isEmpty())
            assertEquals(1, server.calls.size)
        }
    }

    @Test
    fun 删除已经不存在的文件算成功() {
        QwenFakeBatchServer().use { server ->
            server.failNext("delete", 404)
            val provider = qwenBatchProviderForTests(server)
            provider.deleteRemoteFile("file-missing")
            assertEquals(listOf("DELETE /files/file-missing"), server.calls)
        }
    }

    @Test
    fun 任务状态按百炼的取值映射() {
        val cases = listOf(
            "validating" to RemoteBatchPhase.RUNNING,
            "in_progress" to RemoteBatchPhase.RUNNING,
            "finalizing" to RemoteBatchPhase.RUNNING,
            "cancelling" to RemoteBatchPhase.RUNNING,
            "completed" to RemoteBatchPhase.COMPLETED,
            "failed" to RemoteBatchPhase.FAILED,
            "expired" to RemoteBatchPhase.EXPIRED,
            "cancelled" to RemoteBatchPhase.CANCELLED,
        )
        QwenFakeBatchServer(errorFileId = "err-1", taskErrorMessage = "任务失败").use { server ->
            val provider = qwenBatchProviderForTests(server)
            cases.forEach { (status, phase) ->
                server.status = status
                val remote = provider.query("task-1")
                assertEquals(phase, remote.phase, status)
                if (phase == RemoteBatchPhase.RUNNING) {
                    assertNull(remote.outputFileId)
                } else {
                    assertEquals("out-1", remote.outputFileId)
                    assertEquals("err-1", remote.errorFileId)
                }
                if (phase == RemoteBatchPhase.FAILED) assertEquals("任务失败", remote.error)
            }
        }
    }

    @Test
    fun 不认识的状态不会当成还在跑() {
        QwenFakeBatchServer(status = "nope").use { server ->
            val provider = qwenBatchProviderForTests(server)
            assertFailsWith<QwenHttpException> { provider.query("task-1") }
            assertEquals(1, server.calls.size)
        }
    }

    @Test
    fun 失败原因能从错误列表里读出来() {
        val body = """{"status":"failed","output_file_id":null,"error_file_id":"err-1","errors":{"data":[{"message":"文件损坏"}]}}"""
        val remote = parseRemoteBatch(body.encodeToByteArray())
        assertEquals(RemoteBatchPhase.FAILED, remote.phase)
        assertEquals("文件损坏", remote.error)
        assertEquals("err-1", remote.errorFileId)
        assertNull(remote.outputFileId)
    }

    private fun sampleLine(): String = batchRequestLine(
        assetId = "p1",
        jpegBytes = byteArrayOf(1, 2, 3),
        model = PresetPrices.qwenVlFlash.model,
        thinkingEnabled = false,
        thinkingTokenLimit = 1024,
        detailLevel = DetailLevel.DETAILED,
    )
}
