package app.photoindex.storage

import app.photoindex.core.BatchProvider
import app.photoindex.core.BatchRunControl
import app.photoindex.core.BatchState
import app.photoindex.core.QWEN_FAKE_API_KEY
import app.photoindex.core.QwenFakeBatchServer
import app.photoindex.core.QwenHttpException
import app.photoindex.core.RemoteBatch
import app.photoindex.core.RemoteBatchPhase
import app.photoindex.core.failedBatchResultLine
import app.photoindex.core.qwenBatchProviderForTests
import app.photoindex.core.successfulBatchResultLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 同一张恢复表，服务商换成走真 HTTP 形状的千问适配器。
 * 假服务器只绑定 127.0.0.1，适配器也不会接受公网地址。
 */
class 千问假HTTP上的批恢复 : 批状态机与中断恢复() {
    override fun createProvider(phase: RemoteBatchPhase, resultText: String): TrackedProvider {
        val server = QwenFakeBatchServer(
            status = phase.toHttpStatus(),
            resultText = resultText,
            outputFileId = if (phase == RemoteBatchPhase.RUNNING) null else "out-1",
        )
        return httpProvider(server)
    }

    @Test
    fun 已有任务ID时查询失败不会再次上传() {
        scenario { database, directory ->
            database.insertAlbum()
            database.insertAsset("p1")
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(phase = RemoteBatchPhase.RUNNING),
                compressor = FixedCompressor(),
                newBatchId = ids,
                control = BatchRunControl(stopAfterSubmit = true),
            ).start()
            val submitted = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.SUBMITTED, submitted.state)
            assertEquals("task-1", submitted.remoteBatchId)

            val server = QwenFakeBatchServer(status = "in_progress", outputFileId = null)
            server.failNext("query", 503, times = 5)
            val delays = mutableListOf<Long>()
            val provider = track(httpProvider(server, sleep = { delays += it }))
            assertFailsWith<QwenHttpException> {
                indexing(
                    database = database,
                    directory = directory,
                    provider = provider,
                    compressor = ThrowingCompressor(),
                    newBatchId = ids,
                ).resume()
            }
            assertEquals(listOf(200L, 400L), delays)
            assertEquals(3, server.calls.count { it.startsWith("GET /batches/") })
            assertTrue(server.calls.none { it.startsWith("POST ") })
            val stored = database.batchQueries.selectBatchById(submitted.id).executeAsOne()
            assertEquals(BatchState.SUBMITTED, stored.state)
            assertEquals("task-1", stored.remoteBatchId)
            assertEquals("file-1", stored.remoteFileId)
        }
    }

    @Test
    fun 错误文件里的失败行只标那一张() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val server = QwenFakeBatchServer(
                status = "completed",
                resultText = successfulBatchResultLine("p1", modelJson("风景"), 10, 4),
                errorText = failedBatchResultLine("p2", "看不清"),
                errorFileId = "err-1",
            )
            indexing(
                database = database,
                directory = directory,
                provider = track(httpProvider(server)),
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
            ).start()

            assertEquals("done", database.assetQueries.selectAssetById("p1").executeAsOne().status)
            val failed = database.assetQueries.selectAssetById("p2").executeAsOne()
            assertEquals("failed", failed.status)
            assertEquals("看不清", failed.lastError)
            assertTrue(server.calls.contains("GET /files/err-1/content"))
            assertTrue(server.calls.contains("DELETE /files/err-1"))
            assertTrue(server.calls.none { it.contains("dashscope") || it.contains(QWEN_FAKE_API_KEY) })
        }
    }

    private fun httpProvider(
        server: QwenFakeBatchServer,
        sleep: (Long) -> Unit = {},
    ): TrackedProvider {
        val delegate: BatchProvider = qwenBatchProviderForTests(server, sleep = sleep)
        val provider = object : TrackedProvider {
            private val callLog = mutableListOf<String>()
            private val uploadedPaths = mutableListOf<String>()
            private val createdFileIds = mutableListOf<String>()
            override val calls: List<String> get() = callLog
            override val uploaded: List<String> get() = uploadedPaths
            override val createdFrom: List<String> get() = createdFileIds

            override fun upload(localPath: String): String {
                callLog += "upload"
                uploadedPaths += localPath
                val id = delegate.upload(localPath)
                val sent = server.uploadedFileBytes
                check(sent.contentEquals(File(localPath).readBytes())) { "上传内容与本地批文件不一致" }
                val text = sent.decodeToString()
                check(text.contains("\"model\":\"qwen3-vl-flash\"")) { "上传的请求没有默认模型" }
                check(text.contains("\"enable_thinking\":false")) { "思考关闭时没有显式关掉" }
                check(text.contains("\"max_tokens\":600")) { "思考关闭时没有限制最大输出 token" }
                return id
            }

            override fun createTask(remoteFileId: String): String {
                callLog += "create"
                createdFileIds += remoteFileId
                return delegate.createTask(remoteFileId)
            }

            override fun query(remoteBatchId: String): RemoteBatch {
                callLog += "query"
                return delegate.query(remoteBatchId)
            }

            override fun download(outputFileId: String, destinationPath: String) {
                callLog += "download"
                delegate.download(outputFileId, destinationPath)
            }

            override fun cancel(remoteBatchId: String) {
                callLog += "cancel"
                delegate.cancel(remoteBatchId)
            }

            override fun deleteRemoteFile(remoteFileId: String) {
                callLog += "delete"
                delegate.deleteRemoteFile(remoteFileId)
            }

            override fun close() = server.close()
        }
        return provider
    }
}

private fun RemoteBatchPhase.toHttpStatus(): String = when (this) {
    RemoteBatchPhase.RUNNING -> "in_progress"
    RemoteBatchPhase.COMPLETED -> "completed"
    RemoteBatchPhase.FAILED -> "failed"
    RemoteBatchPhase.EXPIRED -> "expired"
    RemoteBatchPhase.CANCELLED -> "cancelled"
}
