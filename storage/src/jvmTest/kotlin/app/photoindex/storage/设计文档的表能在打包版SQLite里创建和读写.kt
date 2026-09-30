package app.photoindex.storage

import app.photoindex.core.DEFAULT_SYNONYM_TABLE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class 设计文档的表能在打包版SQLite里创建和读写 {
    @Test
    fun 建表走的是打包版SQLite() = databaseTest {
        val version = schemaQueries.sqliteVersion().executeAsOne()
        assertTrue(version.isNotEmpty(), "sqlite_version() 应返回非空版本")
        assertTrue(version.first().isDigit(), "版本号应以数字开头，实际为 $version")
    }

    @Test
    fun 来源插入后能读回相册和文件夹() = databaseTest {
        sourceQueries.insertSource(
            id = "album-1",
            kind = "album",
            systemKey = "bucket-camera",
            displayName = "相机",
            enabled = 1L,
        )
        sourceQueries.insertSource(
            id = "folder-1",
            kind = "folder",
            systemKey = "content://tree/travel",
            displayName = "旅行",
            enabled = 0L,
        )

        val album = sourceQueries.selectSourceById("album-1").executeAsOne()
        assertEquals("album", album.kind)
        assertEquals("bucket-camera", album.systemKey)
        assertEquals("相机", album.displayName)
        assertEquals(1L, album.enabled)

        val folder = sourceQueries.selectSourceById("folder-1").executeAsOne()
        assertEquals("folder", folder.kind)
        assertEquals("content://tree/travel", folder.systemKey)
        assertEquals("旅行", folder.displayName)
        assertEquals(0L, folder.enabled)

        assertWriteFails {
            sourceQueries.insertSource(
                id = "bad-kind",
                kind = "gallery",
                systemKey = "x",
                displayName = "不合法",
                enabled = 1L,
            )
        }
        assertNull(sourceQueries.selectSourceById("bad-kind").executeAsOneOrNull())
    }

    @Test
    fun 图片插入后能读回全部字段() = databaseTest {
        insertAlbum()
        batchQueries.insertBatch(
            id = "batch-1",
            configFingerprint = "fp-1",
            state = "packed",
            localPath = "/cache/batch-1.jsonl",
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 1L,
            byteSize = 128L,
            committedCount = 0L,
        )

        assetQueries.insertAsset(
            id = "photo-full",
            sourceId = "album-1",
            systemId = "media-100",
            displayName = "周末咖啡.jpg",
            relativePath = "Camera/周末咖啡.jpg",
            size = 2_048_000L,
            dateModified = 1_700_000_000_000L,
            dateTaken = 1_699_000_000_000L,
            contentHash = "abc123",
            status = "done",
            batchId = "batch-1",
            configFingerprint = "fp-1",
            attemptCount = 2L,
            lastError = null,
            actualInputTokens = 1602L,
            actualOutputTokens = 600L,
        )
        assetQueries.insertAsset(
            id = "photo-empty",
            sourceId = "album-1",
            systemId = "media-101",
            displayName = "未处理.jpg",
            relativePath = "Camera/未处理.jpg",
            size = 100L,
            dateModified = 1_700_000_000_100L,
            dateTaken = null,
            contentHash = null,
            status = "pending",
            batchId = null,
            configFingerprint = "",
            attemptCount = 0L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )

        val full = assetQueries.selectAssetById("photo-full").executeAsOne()
        assertEquals("album-1", full.sourceId)
        assertEquals("media-100", full.systemId)
        assertEquals("周末咖啡.jpg", full.displayName)
        assertEquals("Camera/周末咖啡.jpg", full.relativePath)
        assertEquals(2_048_000L, full.size)
        assertEquals(1_700_000_000_000L, full.dateModified)
        assertEquals(1_699_000_000_000L, full.dateTaken)
        assertEquals("abc123", full.contentHash)
        assertEquals("done", full.status)
        assertEquals("batch-1", full.batchId)
        assertEquals("fp-1", full.configFingerprint)
        assertEquals(2L, full.attemptCount)
        assertNull(full.lastError)
        assertEquals(1602L, full.actualInputTokens)
        assertEquals(600L, full.actualOutputTokens)

        val empty = assetQueries.selectAssetById("photo-empty").executeAsOne()
        assertNull(empty.dateTaken)
        assertNull(empty.contentHash)
        assertEquals("pending", empty.status)
        assertNull(empty.batchId)
        assertEquals("", empty.configFingerprint)
        assertEquals(0L, empty.attemptCount)
        assertNull(empty.lastError)
        assertNull(empty.actualInputTokens)
        assertNull(empty.actualOutputTokens)

        assertWriteFails {
            assetQueries.insertAsset(
                id = "photo-missing-source",
                sourceId = "missing",
                systemId = "media-404",
                displayName = "不存在的来源.jpg",
                relativePath = "x",
                size = 1L,
                dateModified = 1L,
                dateTaken = null,
                contentHash = null,
                status = "pending",
                batchId = null,
                configFingerprint = "",
                attemptCount = 0L,
                lastError = null,
                actualInputTokens = null,
                actualOutputTokens = null,
            )
        }
        assertNull(assetQueries.selectAssetById("photo-missing-source").executeAsOneOrNull())
    }

    @Test
    fun 批插入后能读回全部字段() = databaseTest {
        batchQueries.insertBatch(
            id = "batch-open",
            configFingerprint = "fp-1",
            state = "packed",
            localPath = "/cache/batch-open.jsonl",
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 3L,
            byteSize = 4096L,
            committedCount = 0L,
        )
        batchQueries.insertBatch(
            id = "batch-done",
            configFingerprint = "fp-1",
            state = "completed",
            localPath = null,
            remoteFileId = "file-9",
            remoteBatchId = "task-9",
            outputFileId = "out-9",
            lineCount = 3L,
            byteSize = 4096L,
            committedCount = 3L,
        )

        val open = batchQueries.selectBatchById("batch-open").executeAsOne()
        assertEquals("fp-1", open.configFingerprint)
        assertEquals("packed", open.state)
        assertEquals("/cache/batch-open.jsonl", open.localPath)
        assertNull(open.remoteFileId)
        assertNull(open.remoteBatchId)
        assertNull(open.outputFileId)
        assertEquals(3L, open.lineCount)
        assertEquals(4096L, open.byteSize)
        assertEquals(0L, open.committedCount)

        val done = batchQueries.selectBatchById("batch-done").executeAsOne()
        assertEquals("completed", done.state)
        assertNull(done.localPath)
        assertEquals("file-9", done.remoteFileId)
        assertEquals("task-9", done.remoteBatchId)
        assertEquals("out-9", done.outputFileId)
        assertEquals(3L, done.committedCount)

        val packed = batchQueries.selectBatchesByState("packed").executeAsList()
        assertEquals(listOf("batch-open"), packed.map { it.id })

        assertWriteFails {
            batchQueries.insertBatch(
                id = "batch-bad",
                configFingerprint = "fp-1",
                state = "queued",
                localPath = null,
                remoteFileId = null,
                remoteBatchId = null,
                outputFileId = null,
                lineCount = 0L,
                byteSize = 0L,
                committedCount = 0L,
            )
        }
        assertNull(batchQueries.selectBatchById("batch-bad").executeAsOneOrNull())
    }

    @Test
    fun 识别结果和用户编辑能按图片ID插入并读回() = databaseTest {
        insertAlbum()
        assetQueries.insertAsset(
            id = "photo-1",
            sourceId = "album-1",
            systemId = "media-1",
            displayName = "截图.jpg",
            relativePath = "Camera/截图.jpg",
            size = 10L,
            dateModified = 10L,
            dateTaken = null,
            contentHash = null,
            status = "done",
            batchId = null,
            configFingerprint = "fp-1",
            attemptCount = 1L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )

        recognitionQueries.insertRecognition(
            assetId = "photo-1",
            promptVersion = "1",
            modelId = "qwen3-vl-flash",
            json = """{"summary":"一张截图"}""",
            searchText = "一张截图 截图.jpg",
        )
        userEditQueries.insertUserEdit(
            assetId = "photo-1",
            addedTerms = "周末 咖啡",
            suppressedTerms = "错误作者",
        )

        val recognition = recognitionQueries.selectRecognitionByAssetId("photo-1").executeAsOne()
        assertEquals("1", recognition.promptVersion)
        assertEquals("qwen3-vl-flash", recognition.modelId)
        assertEquals("""{"summary":"一张截图"}""", recognition.json)
        assertEquals("一张截图 截图.jpg", recognition.searchText)

        val edit = userEditQueries.selectUserEditByAssetId("photo-1").executeAsOne()
        assertEquals("周末 咖啡", edit.addedTerms)
        assertEquals("错误作者", edit.suppressedTerms)
    }

    @Test
    fun 设置表读回设计文档里的默认值且只能有一行() = databaseTest {
        val setting = settingQueries.selectSetting().executeAsOne()
        assertEquals(1L, setting.id)
        assertEquals("qwen", setting.provider)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", setting.endpoint)
        assertEquals("qwen3-vl-flash", setting.model)
        assertEquals(0L, setting.thinkingEnabled)
        assertEquals(1024L, setting.thinkingTokenLimit)
        assertEquals(1280L, setting.longEdge)
        assertEquals(80L, setting.jpegQuality)
        assertEquals("detailed", setting.detailLevel)
        assertEquals("batch_file", setting.uploadMode)
        assertEquals(419430400L, setting.batchMaxBytes)
        assertEquals(2000L, setting.batchMaxLines)
        assertEquals(1L, setting.concurrentBatches)
        assertEquals(1L, setting.wifiOnly)
        assertEquals(1L, setting.chargingOnly)
        assertClose(0.15, setting.inputPricePerMillion)
        assertClose(1.5, setting.outputPricePerMillion)
        assertEquals("any", setting.matchMode)
        assertClose(5.0, setting.amountAlertYuan)
        assertEquals(DEFAULT_SYNONYM_TABLE, setting.synonyms)
        assertEquals(0L, setting.deleteResultsOutOfScope)
        assertEquals(1L, settingQueries.countSetting().executeAsOne())

        assertWriteFails {
            settingQueries.insertSetting(
                id = 2L,
                provider = "qwen",
                endpoint = setting.endpoint,
                model = setting.model,
                thinkingEnabled = 0L,
                thinkingTokenLimit = 1024L,
                longEdge = 1280L,
                jpegQuality = 80L,
                detailLevel = "detailed",
                uploadMode = "batch_file",
                batchMaxBytes = 419430400L,
                batchMaxLines = 2000L,
                concurrentBatches = 1L,
                wifiOnly = 1L,
                chargingOnly = 1L,
                inputPricePerMillion = 0.15,
                outputPricePerMillion = 1.5,
                matchMode = "any",
                amountAlertYuan = 5.0,
                synonyms = DEFAULT_SYNONYM_TABLE,
            )
        }
        assertEquals(1L, settingQueries.countSetting().executeAsOne())
    }

    @Test
    fun 改设置后能读回新值() = databaseTest {
        settingQueries.updateSetting(
            provider = "doubao",
            endpoint = "https://example.invalid/v1",
            model = "ep-custom",
            thinkingEnabled = 1L,
            thinkingTokenLimit = 2048L,
            longEdge = null,
            jpegQuality = 60L,
            detailLevel = "brief",
            uploadMode = "realtime",
            batchMaxBytes = 1024L,
            batchMaxLines = 10L,
            concurrentBatches = 2L,
            wifiOnly = 0L,
            chargingOnly = 0L,
            inputPricePerMillion = 0.2,
            outputPricePerMillion = 2.0,
            matchMode = "all",
            amountAlertYuan = 12.5,
            synonyms = "咖啡 / 拿铁",
        )

        val setting = settingQueries.selectSetting().executeAsOne()
        assertEquals("doubao", setting.provider)
        assertEquals("https://example.invalid/v1", setting.endpoint)
        assertEquals("ep-custom", setting.model)
        assertEquals(1L, setting.thinkingEnabled)
        assertEquals(2048L, setting.thinkingTokenLimit)
        assertNull(setting.longEdge)
        assertEquals(60L, setting.jpegQuality)
        assertEquals("brief", setting.detailLevel)
        assertEquals("realtime", setting.uploadMode)
        assertEquals(1024L, setting.batchMaxBytes)
        assertEquals(10L, setting.batchMaxLines)
        assertEquals(2L, setting.concurrentBatches)
        assertEquals(0L, setting.wifiOnly)
        assertEquals(0L, setting.chargingOnly)
        assertClose(0.2, setting.inputPricePerMillion)
        assertClose(2.0, setting.outputPricePerMillion)
        assertEquals("all", setting.matchMode)
        assertClose(12.5, setting.amountAlertYuan)
        assertEquals("咖啡 / 拿铁", setting.synonyms)
        assertEquals(0L, setting.deleteResultsOutOfScope)
    }

    @Test
    fun 图片状态能写成pending和in_batch和done和failed和out_of_scope() = databaseTest {
        insertAlbum()
        batchQueries.insertBatch(
            id = "batch-1",
            configFingerprint = "fp-1",
            state = "running",
            localPath = null,
            remoteFileId = "file-1",
            remoteBatchId = "task-1",
            outputFileId = null,
            lineCount = 2L,
            byteSize = 20L,
            committedCount = 0L,
        )

        insertAsset(id = "a-pending", status = "pending", batchId = null, lastError = null)
        insertAsset(id = "a-in-batch", status = "in_batch", batchId = "batch-1", lastError = null)
        insertAsset(id = "a-done", status = "done", batchId = "batch-1", lastError = null)
        insertAsset(id = "a-failed", status = "failed", batchId = "batch-1", lastError = "这一行解析失败")
        insertAsset(id = "a-out", status = "out_of_scope", batchId = null, lastError = null)

        assertEquals("pending", assetQueries.selectAssetById("a-pending").executeAsOne().status)
        assertEquals("in_batch", assetQueries.selectAssetById("a-in-batch").executeAsOne().status)
        assertEquals("done", assetQueries.selectAssetById("a-done").executeAsOne().status)
        val failed = assetQueries.selectAssetById("a-failed").executeAsOne()
        assertEquals("failed", failed.status)
        assertEquals("这一行解析失败", failed.lastError)
        assertEquals("out_of_scope", assetQueries.selectAssetById("a-out").executeAsOne().status)

        assertWriteFails {
            insertAsset(id = "a-bad", status = "uploaded", batchId = null, lastError = null)
        }
        assertNull(assetQueries.selectAssetById("a-bad").executeAsOneOrNull())
    }

    @Test
    fun 能按图片ID批ID和状态查到对应的图() = databaseTest {
        insertAlbum()
        batchQueries.insertBatch(
            id = "batch-1",
            configFingerprint = "fp-1",
            state = "packed",
            localPath = "/cache/batch-1.jsonl",
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 2L,
            byteSize = 20L,
            committedCount = 0L,
        )
        batchQueries.insertBatch(
            id = "batch-2",
            configFingerprint = "fp-1",
            state = "packed",
            localPath = "/cache/batch-2.jsonl",
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 1L,
            byteSize = 10L,
            committedCount = 0L,
        )

        insertAsset(id = "a-pending", status = "pending", batchId = null, lastError = null)
        insertAsset(id = "b-in-batch", status = "in_batch", batchId = "batch-1", lastError = null)
        insertAsset(id = "c-done", status = "done", batchId = "batch-1", lastError = null)
        insertAsset(id = "d-other", status = "in_batch", batchId = "batch-2", lastError = null)

        assertEquals("c-done", assetQueries.selectAssetById("c-done").executeAsOne().id)
        assertEquals(
            listOf("b-in-batch", "c-done"),
            assetQueries.selectAssetsByBatchId("batch-1").executeAsList().map { it.id },
        )
        assertEquals(
            listOf("d-other"),
            assetQueries.selectAssetsByBatchId("batch-2").executeAsList().map { it.id },
        )
        assertEquals(
            listOf("a-pending"),
            assetQueries.selectAssetsByStatus("pending").executeAsList().map { it.id },
        )
        assertEquals(
            listOf("b-in-batch", "d-other"),
            assetQueries.selectAssetsByStatus("in_batch").executeAsList().map { it.id },
        )
        assertTrue(assetQueries.selectAssetsByStatus("failed").executeAsList().isEmpty())
    }

    @Test
    fun job只有一行且状态只能是idle和running和paused() = databaseTest {
        val seeded = jobQueries.selectJob().executeAsOne()
        assertEquals(1L, seeded.id)
        assertEquals("idle", seeded.state)
        assertEquals(1L, seeded.wifiOnly)
        assertEquals(1L, seeded.chargingOnly)
        assertEquals(1L, jobQueries.countJob().executeAsOne())

        jobQueries.updateJob(state = "running", wifiOnly = 1L, chargingOnly = 0L)
        assertEquals("running", jobQueries.selectJob().executeAsOne().state)
        assertEquals(0L, jobQueries.selectJob().executeAsOne().chargingOnly)

        jobQueries.updateJob(state = "paused", wifiOnly = 0L, chargingOnly = 1L)
        val paused = jobQueries.selectJob().executeAsOne()
        assertEquals("paused", paused.state)
        assertEquals(0L, paused.wifiOnly)
        assertEquals(1L, paused.chargingOnly)

        assertWriteFails {
            jobQueries.updateJob(state = "cancelled", wifiOnly = 1L, chargingOnly = 1L)
        }
        assertEquals("paused", jobQueries.selectJob().executeAsOne().state)

        assertWriteFails {
            jobQueries.insertJob(id = 2L, state = "idle", wifiOnly = 1L, chargingOnly = 1L)
        }
        assertEquals(1L, jobQueries.countJob().executeAsOne())
    }

    private fun databaseTest(body: PhotoIndexDatabase.() -> Unit) {
        val opened = openPhotoIndexDatabase()
        try {
            opened.database.body()
        } finally {
            opened.close()
        }
    }

    private fun assertWriteFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("这次写入应该被拒绝")
        } catch (error: AssertionError) {
            throw error
        } catch (_: Throwable) {
        }
    }

    private fun PhotoIndexDatabase.insertAlbum() {
        sourceQueries.insertSource(
            id = "album-1",
            kind = "album",
            systemKey = "bucket-camera",
            displayName = "相机",
            enabled = 1L,
        )
    }

    private fun PhotoIndexDatabase.insertAsset(
        id: String,
        status: String,
        batchId: String?,
        lastError: String?,
    ) {
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
            status = status,
            batchId = batchId,
            configFingerprint = "fp-1",
            attemptCount = 0L,
            lastError = lastError,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "期望 $expected，实际 $actual")
    }
}
