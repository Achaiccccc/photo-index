package app.photoindex.storage

import app.photoindex.core.BatchIndexing
import app.photoindex.core.BatchPackLimits
import app.photoindex.core.BatchProvider
import app.photoindex.core.BatchRunControl
import app.photoindex.core.BatchState
import app.photoindex.core.CompressedJpeg
import app.photoindex.core.ImageCompressor
import app.photoindex.core.JobState
import app.photoindex.core.ModelRecord
import app.photoindex.core.RemoteBatch
import app.photoindex.core.RemoteBatchPhase
import app.photoindex.core.defaultRecognitionSettings
import app.photoindex.core.failedBatchResultLine
import app.photoindex.core.successfulBatchResultLine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 每个场景都先跑一个状态机，再对同一个库新建第二个实例。
 * 恢复要靠库里的行，不能靠上一个对象还活着。
 * 子类把 [createProvider] 换成千问适配器加本机假 HTTP，再跑同一张恢复表。
 */
open class 批状态机与中断恢复 {
    private val opened = mutableListOf<AutoCloseable>()

    protected open fun createProvider(
        phase: RemoteBatchPhase = RemoteBatchPhase.COMPLETED,
        resultText: String = "",
    ): TrackedProvider = MemoryProvider(phase = phase, resultText = resultText)

    protected fun openProvider(
        phase: RemoteBatchPhase = RemoteBatchPhase.COMPLETED,
        resultText: String = "",
    ): TrackedProvider {
        val provider = createProvider(phase, resultText)
        opened += provider
        return provider
    }

    protected fun track(provider: TrackedProvider): TrackedProvider {
        opened += provider
        return provider
    }
    @Test
    fun 封口前被杀() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val firstProvider = openProvider()
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = firstProvider,
                compressor = FixedCompressor(),
                newBatchId = ids,
                control = BatchRunControl(stopBeforeSeal = true),
            ).start()

            assertEquals(1, database.batchQueries.selectBatchesByState(BatchState.PACKING).executeAsList().size)
            assertEquals(1, jsonlFiles(directory).size)
            assertTrue(firstProvider.calls.isEmpty())

            val secondProvider = openProvider()
            indexing(
                database = database,
                directory = directory,
                provider = secondProvider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).recover()

            assertTrue(secondProvider.calls.isEmpty())
            assertTrue(jsonlFiles(directory).isEmpty())
            assertEquals(0, database.batchQueries.selectAllBatches().executeAsList().size)
            listOf("p1", "p2").forEach { id ->
                val asset = database.assetQueries.selectAssetById(id).executeAsOne()
                assertEquals("pending", asset.status)
                assertNull(asset.batchId)
            }
        }
    }

    @Test
    fun 已封口还没有文件ID() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(phase = RemoteBatchPhase.RUNNING),
                compressor = FixedCompressor(),
                newBatchId = ids,
                control = BatchRunControl(stopAfterSeal = true),
            ).start()

            val packed = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.PACKED, packed.state)
            assertNull(packed.remoteFileId)
            assertNull(packed.remoteBatchId)
            val localPath = packed.localPath!!
            assertTrue(File(localPath).isFile)
            listOf("p1", "p2").forEach { id ->
                assertEquals("in_batch", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }

            val provider = openProvider(phase = RemoteBatchPhase.RUNNING)
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertEquals(listOf("upload", "create", "query"), provider.calls)
            assertEquals(listOf(localPath), provider.uploaded)
            val stored = database.batchQueries.selectBatchById(packed.id).executeAsOne()
            assertEquals("file-1", stored.remoteFileId)
            assertEquals("task-1", stored.remoteBatchId)
            assertEquals(BatchState.RUNNING, stored.state)
            assertNull(stored.localPath)
            assertFalse(File(localPath).exists())
        }
    }

    @Test
    fun 已有文件ID还没有任务ID() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(phase = RemoteBatchPhase.RUNNING),
                compressor = FixedCompressor(),
                newBatchId = ids,
                control = BatchRunControl(stopAfterUpload = true),
            ).start()

            val uploaded = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.UPLOADED, uploaded.state)
            assertEquals("file-1", uploaded.remoteFileId)
            assertNull(uploaded.remoteBatchId)

            val provider = openProvider(phase = RemoteBatchPhase.RUNNING)
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertEquals(listOf("create", "query"), provider.calls)
            assertEquals(listOf("file-1"), provider.createdFrom)
            val stored = database.batchQueries.selectBatchById(uploaded.id).executeAsOne()
            assertEquals("file-1", stored.remoteFileId)
            assertEquals("task-1", stored.remoteBatchId)
            assertEquals(BatchState.RUNNING, stored.state)
        }
    }

    @Test
    fun 已有任务ID() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
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

            val provider = openProvider(
                phase = RemoteBatchPhase.COMPLETED,
                resultText = resultLines("p1", "p2"),
            )
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertEquals(listOf("query", "download", "delete", "delete"), provider.calls)
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectBatchById(submitted.id).executeAsOne().state)
            listOf("p1", "p2").forEach { id ->
                assertEquals("done", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }
            assertTrue(jsonlFiles(directory).isEmpty())
        }
    }

    @Test
    fun 入库到一半被杀() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2", "p3", "p4").forEach { database.insertAsset(it) }
            val ids = batchIds()
            val results = resultLines("p1", "p2", "p3", "p4")
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(resultText = results),
                compressor = FixedCompressor(),
                newBatchId = ids,
                control = BatchRunControl(commitChunkSize = 2, stopAfterCommitChunks = 1),
            ).start()

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.COMMITTING, batch.state)
            assertEquals(2L, batch.committedCount)
            assertTrue(File(batch.localPath!!).isFile)
            listOf("p1", "p2").forEach { id ->
                assertEquals("done", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }
            listOf("p3", "p4").forEach { id ->
                assertEquals("in_batch", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }
            listOf("p1", "p2").forEach { id ->
                database.recognitionQueries.replaceRecognition(
                    promptVersion = "1",
                    modelId = "qwen3-vl-flash",
                    json = "SENTINEL",
                    searchText = "sentinel",
                    assetId = id,
                )
            }

            val provider = openProvider(resultText = results)
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertTrue(provider.calls.none { it == "upload" || it == "create" })
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectBatchById(batch.id).executeAsOne().state)
            assertEquals("SENTINEL", database.recognitionQueries.selectRecognitionByAssetId("p1").executeAsOne().json)
            assertEquals("SENTINEL", database.recognitionQueries.selectRecognitionByAssetId("p2").executeAsOne().json)
            listOf("p3", "p4").forEach { id ->
                val asset = database.assetQueries.selectAssetById(id).executeAsOne()
                assertEquals("done", asset.status)
                assertTrue(database.recognitionQueries.selectRecognitionByAssetId(id).executeAsOne().json.contains("风景"))
            }
            assertTrue(jsonlFiles(directory).isEmpty())
        }
    }

    @Test
    fun 结果里某一行失败() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2", "p3").forEach { database.insertAsset(it) }
            val provider = openProvider(
                resultText = listOf(
                    successfulBatchResultLine("p1", modelJson("风景"), 10, 4),
                    failedBatchResultLine("p2", "看不清"),
                    successfulBatchResultLine("p3", modelJson("街道"), 11, 5),
                ).joinToString("\n"),
            )
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
            ).start()

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.COMPLETED, batch.state)
            assertEquals("done", database.assetQueries.selectAssetById("p1").executeAsOne().status)
            assertEquals("done", database.assetQueries.selectAssetById("p3").executeAsOne().status)
            val failed = database.assetQueries.selectAssetById("p2").executeAsOne()
            assertEquals("failed", failed.status)
            assertEquals(batch.id, failed.batchId)
            assertEquals("看不清", failed.lastError)
            assertEquals(listOf("p1", "p2", "p3"), database.assetQueries.selectAssetsByBatchId(batch.id).executeAsList().map { it.id })
            assertTrue(provider.calls.contains("delete"))
            assertTrue(jsonlFiles(directory).isEmpty())
        }
    }

    @Test
    fun 暂停() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(phase = RemoteBatchPhase.RUNNING),
                compressor = FixedCompressor(),
                newBatchId = ids,
            ).start()

            assertEquals(BatchState.RUNNING, database.batchQueries.selectAllBatches().executeAsOne().state)
            listOf("p3", "p4").forEach { database.insertAsset(it) }

            val provider = openProvider(resultText = resultLines("p1", "p2"))
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).pause()

            assertEquals(JobState.PAUSED, database.jobQueries.selectJob().executeAsOne().state)
            assertEquals(1, database.batchQueries.selectAllBatches().executeAsList().size)
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectAllBatches().executeAsOne().state)
            listOf("p1", "p2").forEach { id ->
                assertEquals("done", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }
            listOf("p3", "p4").forEach { id ->
                val asset = database.assetQueries.selectAssetById(id).executeAsOne()
                assertEquals("pending", asset.status)
                assertNull(asset.batchId)
            }
            assertTrue(provider.calls.none { it == "upload" || it == "create" })
        }
    }

    @Test
    fun 明确取消() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertAsset(it) }
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = openProvider(phase = RemoteBatchPhase.RUNNING),
                compressor = FixedCompressor(),
                newBatchId = ids,
            ).start()

            val provider = openProvider(
                phase = RemoteBatchPhase.CANCELLED,
                resultText = successfulBatchResultLine("p1", modelJson("已经跑完"), 8, 3),
            )
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).cancel()

            assertEquals(listOf("cancel", "query", "download", "delete", "delete"), provider.calls)
            assertEquals(JobState.IDLE, database.jobQueries.selectJob().executeAsOne().state)
            assertEquals(BatchState.CANCELLED, database.batchQueries.selectAllBatches().executeAsOne().state)
            assertEquals("done", database.assetQueries.selectAssetById("p1").executeAsOne().status)
            assertTrue(database.recognitionQueries.selectRecognitionByAssetId("p1").executeAsOne().json.contains("已经跑完"))
            val unfinished = database.assetQueries.selectAssetById("p2").executeAsOne()
            assertEquals("pending", unfinished.status)
            assertNull(unfinished.batchId)
            assertEquals(0L, unfinished.attemptCount)

            val again = openProvider()
            indexing(
                database = database,
                directory = directory,
                provider = again,
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertTrue(again.calls.isEmpty())
            assertEquals("pending", database.assetQueries.selectAssetById("p2").executeAsOne().status)
            assertEquals(1, database.batchQueries.selectAllBatches().executeAsList().size)
        }
    }

    @Test
    fun 指纹相同且已完成不进入新批() {
        scenario { database, directory ->
            database.insertAlbum()
            val fingerprint = defaultRecognitionSettings().fingerprint()
            database.insertAsset(id = "already", status = "done", fingerprint = fingerprint)
            database.insertAsset(id = "fresh")
            val provider = openProvider(resultText = resultLines("fresh"))
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
            ).start()

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(listOf("fresh"), database.assetQueries.selectAssetsByBatchId(batch.id).executeAsList().map { it.id })
            val done = database.assetQueries.selectAssetById("already").executeAsOne()
            assertEquals("done", done.status)
            assertNull(done.batchId)
            assertEquals(fingerprint, done.configFingerprint)
            assertEquals(fingerprint, batch.configFingerprint)
        }
    }

    @Test
    fun 只重试失败图() {
        scenario { database, directory ->
            database.insertAlbum()
            val fingerprint = defaultRecognitionSettings().fingerprint()
            database.insertAsset(id = "already", status = "done", fingerprint = fingerprint)
            database.insertAsset(id = "bad-1", status = "failed")
            database.insertAsset(id = "bad-2", status = "failed")
            val provider = openProvider(resultText = resultLines("bad-1", "bad-2"))
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
            ).retryFailed()

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(
                listOf("bad-1", "bad-2"),
                database.assetQueries.selectAssetsByBatchId(batch.id).executeAsList().map { it.id },
            )
            val done = database.assetQueries.selectAssetById("already").executeAsOne()
            assertEquals("done", done.status)
            assertNull(done.batchId)
            listOf("bad-1", "bad-2").forEach { id ->
                assertEquals("done", database.assetQueries.selectAssetById(id).executeAsOne().status)
            }
        }
    }

    @Test
    fun 入库后用户追加词仍在() {
        scenario { database, directory ->
            database.insertAlbum()
            database.insertAsset(id = "p1", displayName = "咖啡.jpg")
            database.recognitionQueries.insertRecognition(
                assetId = "p1",
                promptVersion = "1",
                modelId = "old-model",
                json = """{"summary":"旧描述"}""",
                searchText = "旧描述",
            )
            database.userEditQueries.insertUserEdit(
                assetId = "p1",
                addedTerms = "手冲",
                suppressedTerms = "错误作者",
            )
            val provider = openProvider(
                resultText = successfulBatchResultLine(
                    customId = "p1",
                    modelJson = ModelRecord(summary = "风景 错误作者").toStableJson(),
                    inputTokens = 12,
                    outputTokens = 6,
                ),
            )
            indexing(
                database = database,
                directory = directory,
                provider = provider,
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
            ).start()

            val asset = database.assetQueries.selectAssetById("p1").executeAsOne()
            assertEquals("done", asset.status)
            assertEquals(12L, asset.actualInputTokens)
            assertEquals(6L, asset.actualOutputTokens)
            val recognition = database.recognitionQueries.selectRecognitionByAssetId("p1").executeAsOne()
            assertEquals(ModelRecord(summary = "风景 错误作者").toStableJson(), recognition.json)
            assertTrue(recognition.searchText.contains("手冲"))
            assertTrue(recognition.searchText.contains("风景"))
            assertFalse(recognition.searchText.contains("错误作者"))
            assertFalse(recognition.json.contains("旧描述"))
            val edit = database.userEditQueries.selectUserEditByAssetId("p1").executeAsOne()
            assertEquals("手冲", edit.addedTerms)
            assertEquals("错误作者", edit.suppressedTerms)
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectAllBatches().executeAsOne().state)
            assertTrue(provider.calls.contains("delete"))
            assertTrue(jsonlFiles(directory).isEmpty())
        }
    }

    protected fun scenario(block: (PhotoIndexDatabase, File) -> Unit) {
        val directory = Files.createTempDirectory("photo-index-t6").toFile()
        val openedDatabase = openPhotoIndexDatabase()
        try {
            block(openedDatabase.database, directory)
        } finally {
            try {
                opened.forEach { it.close() }
            } finally {
                opened.clear()
                openedDatabase.close()
                directory.deleteRecursively()
            }
        }
    }

    protected fun indexing(
        database: PhotoIndexDatabase,
        directory: File,
        provider: BatchProvider,
        compressor: ImageCompressor,
        newBatchId: () -> String,
        control: BatchRunControl = BatchRunControl(),
        confirmed: Boolean = true,
        limits: BatchPackLimits = BatchPackLimits(
            maxFileBytes = 1_000_000,
            maxLines = 10,
            maxLineBytes = 200_000,
        ),
        uploadAllowed: () -> Boolean = { true },
    ): BatchIndexing {
        if (confirmed) database.quoteConfirmationQueries.markConfirmed()
        else database.quoteConfirmationQueries.clearConfirmed()
        return BatchIndexing(
            catalog = PhotoIndexBatches(database),
            provider = provider,
            workspace = DirectoryBatchWorkspace(directory),
            compressor = compressor,
            files = DirectoryBatchFiles(directory),
            newBatchId = newBatchId,
            limits = limits,
            control = control,
            uploadAllowed = uploadAllowed,
        )
    }

    protected fun batchIds(): () -> String {
        var number = 0
        return { "batch-${++number}" }
    }

    protected fun resultLines(vararg assetIds: String): String = assetIds.joinToString("\n") { id ->
        successfulBatchResultLine(id, modelJson("风景"), 9, 4)
    }

    protected fun modelJson(summary: String): String = ModelRecord(summary = summary).toStableJson()

    private fun jsonlFiles(directory: File): List<File> =
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }.orEmpty()

    protected fun PhotoIndexDatabase.insertAlbum() {
        sourceQueries.insertSource(
            id = "album-1",
            kind = "album",
            systemKey = "bucket-camera",
            displayName = "相机",
            enabled = 1L,
        )
    }

    protected fun PhotoIndexDatabase.insertAsset(
        id: String,
        status: String = "pending",
        fingerprint: String = "",
        displayName: String = "$id.jpg",
    ) {
        assetQueries.insertAsset(
            id = id,
            sourceId = "album-1",
            systemId = "media-$id",
            displayName = displayName,
            relativePath = "Camera/$id.jpg",
            size = 100L,
            dateModified = 10L,
            dateTaken = null,
            contentHash = null,
            status = status,
            batchId = null,
            configFingerprint = fingerprint,
            attemptCount = 0L,
            lastError = if (status == "failed") "上次失败" else null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
    }
}

private class MemoryProvider(
    var phase: RemoteBatchPhase = RemoteBatchPhase.COMPLETED,
    var resultText: String = "",
    private val fileId: String = "file-1",
    private val taskId: String = "task-1",
    private val outputFileId: String? = "out-1",
) : TrackedProvider {
    private val callLog = mutableListOf<String>()
    private val uploadedPaths = mutableListOf<String>()
    private val createdFileIds = mutableListOf<String>()
    override val calls: List<String> get() = callLog
    override val uploaded: List<String> get() = uploadedPaths
    override val createdFrom: List<String> get() = createdFileIds

    override fun upload(localPath: String): String {
        callLog += "upload"
        uploadedPaths += localPath
        return fileId
    }

    override fun createTask(remoteFileId: String): String {
        callLog += "create"
        createdFileIds += remoteFileId
        return taskId
    }

    override fun query(remoteBatchId: String): RemoteBatch {
        callLog += "query"
        return RemoteBatch(phase = phase, outputFileId = outputFileId, error = null)
    }

    override fun download(outputFileId: String, destinationPath: String) {
        callLog += "download"
        File(destinationPath).writeText(resultText)
    }

    override fun cancel(remoteBatchId: String) {
        callLog += "cancel"
    }

    override fun deleteRemoteFile(remoteFileId: String) {
        callLog += "delete"
    }
}

interface TrackedProvider : BatchProvider, AutoCloseable {
    val calls: List<String>
    val uploaded: List<String>
    val createdFrom: List<String>
    override fun close() = Unit
}

internal class FixedCompressor : ImageCompressor {
    override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg = object : CompressedJpeg {
        override val bytes: ByteArray = byteArrayOf(1, 2, 3)
        override val widthPx: Int = 32
        override val heightPx: Int = 24
        override fun close() = Unit
    }
}

internal class ThrowingCompressor : ImageCompressor {
    override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg {
        error("恢复时不应该重新压缩")
    }
}
