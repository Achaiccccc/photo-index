package app.photoindex.storage

import app.photoindex.core.BatchIndexing
import app.photoindex.core.BatchPackLimits
import app.photoindex.core.BatchProvider
import app.photoindex.core.CompressedJpeg
import app.photoindex.core.ImageCompressor
import app.photoindex.core.JobState
import app.photoindex.core.QuoteNotConfirmed
import app.photoindex.core.RemoteBatch
import app.photoindex.core.RemoteBatchPhase
import app.photoindex.core.defaultIndexSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class 设置与估价确认 {
    @Test
    fun 默认未确认保存设置后仍然未确认() {
        withStore { store ->
            assertFalse(store.confirmed())
            val changed = store.load().copy(model = "qwen3-vl-plus", thinkingEnabled = true, longEdge = 512)
            store.save(changed)
            assertEquals(changed, store.load())
            assertFalse(store.confirmed())
        }
    }

    @Test
    fun 确认只写入标记不改变待处理张数也不产生上传中的批() {
        withStore { store, database ->
            database.insertPending("p1")
            database.insertPending("p2")
            val pendingBefore = store.pendingCount()
            var uploads = 0
            store.confirm()

            assertTrue(store.confirmed())
            assertEquals(pendingBefore, store.pendingCount())
            assertEquals(JobState.IDLE, database.jobQueries.selectJob().executeAsOne().state)
            assertTrue(database.batchQueries.selectBatchesByState("uploading").executeAsList().isEmpty())
            assertTrue(database.batchQueries.selectAllBatches().executeAsList().isEmpty())
            store.runUpload { uploads += 1 }
            assertEquals(1, uploads)
        }
    }

    @Test
    fun 未确认时上传入口和状态机都不会调用上传() {
        withStore { store, database ->
            database.insertPending("p1")
            database.insertPending("p2")
            var uploads = 0
            assertFailsWith<QuoteNotConfirmed> { store.runUpload { uploads += 1 } }
            assertEquals(0, uploads)

            val calls = mutableListOf<String>()
            val directory = Files.createTempDirectory("t10-no-confirm").toFile()
            val indexing = BatchIndexing(
                catalog = PhotoIndexBatches(database),
                provider = RecordingProvider(calls),
                workspace = DirectoryBatchWorkspace(directory),
                compressor = UnusedCompressor(),
                files = DirectoryBatchFiles(directory),
                newBatchId = { "batch-1" },
                limits = BatchPackLimits(maxFileBytes = 1_000, maxLines = 10, maxLineBytes = 500),
            )
            assertFailsWith<QuoteNotConfirmed> { indexing.start() }

            assertTrue(calls.isEmpty())
            assertEquals(2, store.pendingCount())
            assertEquals(JobState.IDLE, database.jobQueries.selectJob().executeAsOne().state)
            assertTrue(database.batchQueries.selectAllBatches().executeAsList().isEmpty())
        }
    }

    @Test
    fun 改设置后确认作废() {
        withStore { store ->
            store.confirm()
            assertTrue(store.confirmed())
            store.save(store.load().copy(longEdge = 512, thinkingEnabled = true))
            assertFalse(store.confirmed())
            assertEquals(512, store.load().longEdge)
            assertTrue(store.load().thinkingEnabled)
        }
    }

    @Test
    fun 相同设置再保存不会清掉确认() {
        withStore { store ->
            store.confirm()
            store.save(store.load())
            assertTrue(store.confirmed())
        }
    }

    @Test
    fun 长边思考和批量合计能从库里的设置算出来() {
        withStore { store, database ->
            database.insertPending("p1")
            database.insertPending("p2")
            val original = store.preview()
            store.save(store.load().copy(longEdge = 512))
            val shorter = store.preview()
            store.save(store.load().copy(thinkingEnabled = true))
            val thinking = store.preview()

            assertTrue(shorter.activeTotal.point < original.activeTotal.point)
            assertTrue(thinking.activeTotal.point > shorter.activeTotal.point)
            assertTrue(original.showBatchAndRealtime)
            assertTrue(original.totalBatch.point < original.totalRealtime.point)
            assertEquals("批量价", original.priceLabel)
            assertFalse(original.repeatsAmountBesideButton)
        }
    }

    @Test
    fun 把提醒线降到零后合计会要求显示在确认按钮旁() {
        withStore { store, database ->
            database.insertPending("p1")
            store.save(store.load().copy(amountAlertYuan = 0.0))
            assertTrue(store.preview().repeatsAmountBesideButton)
        }
    }

    @Test
    fun 索引库文件里没有密钥明文() {
        val key = "t10-sentinel-key-6f0c9a7b"
        val directory = Files.createTempDirectory("t10-key")
        val databaseFile = directory.resolve("photo-index.db").toFile()
        openPhotoIndexDatabase(databaseFile.absolutePath).use { opened ->
            IndexSettingsStore(opened.database).save(
                defaultIndexSettings().copy(model = "qwen3-vl-plus", thinkingEnabled = true),
            )
        }
        val stored = databaseBytes(databaseFile)
        val text = stored.toString(Charsets.UTF_8)
        assertTrue(text.contains("qwen3-vl-plus"))
        assertFalse(text.contains(key))
    }

    @Test
    fun 已经打开过的库会补上未确认标记且原有数据还在() {
        val directory = Files.createTempDirectory("t10-old")
        val path = directory.resolve("photo-index.db").toString()
        openPhotoIndexDatabase(path).use { opened ->
            opened.database.sourceQueries.insertSource(
                id = "album-old",
                kind = "album",
                systemKey = "bucket-old",
                displayName = "旧相册",
                enabled = 1L,
            )
        }
        openBundledSqlDriver(path).use { driver ->
            driver.execute(null, "DROP TABLE quote_confirmation", 0, null)
        }
        openPhotoIndexDatabase(path).use { opened ->
            assertEquals("旧相册", opened.database.sourceQueries.selectSourceById("album-old").executeAsOne().displayName)
            assertEquals(0L, opened.database.quoteConfirmationQueries.selectConfirmed().executeAsOne())
        }
    }

    private fun withStore(block: (IndexSettingsStore) -> Unit) {
        openPhotoIndexDatabase().use { opened ->
            block(IndexSettingsStore(opened.database))
        }
    }

    private fun withStore(block: (IndexSettingsStore, PhotoIndexDatabase) -> Unit) {
        openPhotoIndexDatabase().use { opened ->
            block(IndexSettingsStore(opened.database), opened.database)
        }
    }

    private fun PhotoIndexDatabase.insertPending(id: String) {
        if (sourceQueries.selectSourceById("album-1").executeAsOneOrNull() == null) {
            sourceQueries.insertSource(
                id = "album-1",
                kind = "album",
                systemKey = "bucket-camera",
                displayName = "相机",
                enabled = 1L,
            )
        }
        assetQueries.insertAsset(
            id = id,
            sourceId = "album-1",
            systemId = "media-$id",
            displayName = "$id.jpg",
            relativePath = "Camera/$id.jpg",
            size = 10L,
            dateModified = 10L,
            dateTaken = null,
            contentHash = null,
            status = "pending",
            batchId = null,
            configFingerprint = "fp-1",
            attemptCount = 0L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
    }
}

private class RecordingProvider(
    private val calls: MutableList<String>,
) : BatchProvider {
    override fun upload(localPath: String): String {
        calls += "upload"
        return "file-1"
    }

    override fun createTask(remoteFileId: String): String {
        calls += "create"
        return "task-1"
    }

    override fun query(remoteBatchId: String): RemoteBatch =
        RemoteBatch(phase = RemoteBatchPhase.COMPLETED, outputFileId = null, error = null)

    override fun download(outputFileId: String, destinationPath: String) {
        calls += "download"
    }

    override fun cancel(remoteBatchId: String) {
        calls += "cancel"
    }

    override fun deleteRemoteFile(remoteFileId: String) {
        calls += "delete"
    }
}

private class UnusedCompressor : ImageCompressor {
    override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg {
        error("未确认时不应该压缩")
    }
}

private fun databaseBytes(databaseFile: java.io.File): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    listOf(
        databaseFile,
        java.io.File(databaseFile.path + "-wal"),
        java.io.File(databaseFile.path + "-shm"),
    ).filter { it.isFile }.forEach { output.write(it.readBytes()) }
    return output.toByteArray()
}
