package app.photoindex.storage

import app.photoindex.core.PictureFile
import app.photoindex.core.stableLocalId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class 选择范围并扫描 {
    @Test
    fun 空库打开时没有待处理也没有勾选来源() = databaseTest {
        val index = ScopeIndex(this)
        assertEquals(0L, index.pendingCount())
        assertEquals(0L, index.countByStatus("out_of_scope"))
        assertTrue(index.enabledSources().isEmpty())
        assertTrue(sourceQueries.selectEnabledSources().executeAsList().isEmpty())
        assertEquals(false, index.deleteResultsOutOfScope())
        index.disableSource("album", "还没选过")
        assertEquals(0L, index.pendingCount())
    }

    @Test
    fun 只扫描勾选的两个来源且待处理等于张数之和() = databaseTest {
        val index = ScopeIndex(this)
        val album = index.enableSource(
            kind = "album",
            systemKey = "bucket-a",
            displayName = "相册甲",
            files = listOf(file("1", "甲1.jpg"), file("2", "甲2.jpg"), file("", "空标识.jpg"), file("2", "重复.jpg")),
        )
        val folder = index.enableSource(
            kind = "folder",
            systemKey = "content://tree/folder-b",
            displayName = "文件夹乙",
            files = listOf(file("1", "乙1.jpg")),
        )

        assertEquals(2, album.inserted)
        assertEquals(1, folder.inserted)
        assertEquals(3L, index.pendingCount())
        assertEquals(2L, index.countBySource(album.sourceId))
        assertEquals(1L, index.countBySource(folder.sourceId))
        assertEquals(0L, index.countBySource(sourceId("album", "bucket-c")))
        val pending = assetQueries.selectAssetsByStatus("pending").executeAsList()
        assertEquals(setOf("1", "2"), pending.filter { it.sourceId == album.sourceId }.map { it.systemId }.toSet())
        assertTrue(pending.all { it.status == "pending" && it.contentHash == null && it.heldStatus == null })
        assertTrue(batchQueries.selectAllBatches().executeAsList().isEmpty())
        assertEquals(setOf("相册甲", "文件夹乙"), index.enabledSources().map { it.displayName }.toSet())
    }

    @Test
    fun 取消一个来源后不再待处理但行和识别结果都还在() = databaseTest {
        val index = ScopeIndex(this)
        val album = index.enableSource("album", "bucket-a", "相册甲", listOf(file("a1"), file("a2")))
        val folder = index.enableSource("folder", "content://tree/folder-b", "文件夹乙", listOf(file("b1")))
        val hiddenId = stableLocalId("asset", folder.sourceId, "b1")
        recognitionQueries.insertRecognition(
            assetId = hiddenId,
            promptVersion = "1",
            modelId = "qwen3-vl-flash",
            json = """{"summary":"乙的图"}""",
            searchText = "乙的图",
        )
        index.setDeleteResultsOutOfScope(true)

        index.disableSource("folder", "content://tree/folder-b")

        assertEquals(2L, index.pendingCount())
        assertEquals(1L, index.countByStatus("out_of_scope"))
        assertEquals(1L, index.countBySource(folder.sourceId))
        val hidden = assetQueries.selectAssetById(hiddenId).executeAsOne()
        assertEquals("out_of_scope", hidden.status)
        assertEquals("pending", hidden.heldStatus)
        assertEquals("""{"summary":"乙的图"}""", recognitionQueries.selectRecognitionByAssetId(hiddenId).executeAsOne().json)
        assertTrue(index.enabledSources().none { it.id == folder.sourceId })
        assertEquals(0L, sourceQueries.selectSourceById(folder.sourceId).executeAsOne().enabled)
        val page = index.photosAfter(-1, "", 10)
        assertEquals(2, page.size)
        assertTrue(page.none { it.systemId == "b1" })
        assertEquals(2L, index.countBySource(album.sourceId))
    }

    @Test
    fun 再次扫描同一范围不会写成第二行也不会建批() = databaseTest {
        val index = ScopeIndex(this)
        val first = index.enableSource(
            "album",
            "bucket-a",
            "相册甲",
            listOf(file("a1", "原来的名字.jpg", modified = 100)),
        )
        val again = index.enableSource(
            "album",
            "bucket-a",
            "相册甲改名",
            listOf(file("a1", "新名字.jpg", modified = 999), file("a2", "新图.jpg")),
        )

        assertEquals(1, first.inserted)
        assertEquals(0, first.kept)
        assertEquals(1, again.inserted)
        assertEquals(1, again.kept)
        assertEquals(2L, index.pendingCount())
        assertEquals(2L, index.countBySource(first.sourceId))
        val original = assetQueries.selectAssetById(stableLocalId("asset", first.sourceId, "a1")).executeAsOne()
        assertEquals("原来的名字.jpg", original.displayName)
        assertEquals(100L, original.dateModified)
        assertNull(original.contentHash)
        assertTrue(batchQueries.selectAllBatches().executeAsList().isEmpty())
    }

    @Test
    fun 范围内的图按页返回且移出范围的不在页里() = databaseTest {
        val index = ScopeIndex(this)
        index.enableSource(
            "album",
            "bucket-a",
            "相册甲",
            listOf(
                file("a1", modified = 300),
                file("a2", modified = 200),
                file("a3", modified = 200),
                file("a4", modified = 100),
            ),
        )
        index.enableSource("folder", "content://tree/folder-b", "文件夹乙", listOf(file("b1", modified = 900)))
        index.disableSource("folder", "content://tree/folder-b")

        val seen = mutableListOf<String>()
        var afterModified = -1L
        var afterId = ""
        repeat(5) {
            val page = index.photosAfter(afterModified, afterId, 2)
            if (page.isEmpty()) return@repeat
            assertTrue(page.size <= 2)
            seen += page.map { it.systemId }
            afterModified = page.last().dateModified
            afterId = page.last().id
        }
        assertEquals("a1", seen.first())
        assertEquals(listOf("a1", "a2", "a3", "a4").toSet(), seen.toSet())
        assertEquals(4, seen.size)
        assertTrue(seen.none { it == "b1" })
    }

    @Test
    fun 重新勾选已完成的图会回到完成而不是待处理() = databaseTest {
        val index = ScopeIndex(this)
        val album = index.enableSource("album", "bucket-a", "相册甲", listOf(file("done-1"), file("wait-1")))
        val doneId = stableLocalId("asset", album.sourceId, "done-1")
        batchQueries.insertBatch(
            id = "batch-done",
            configFingerprint = "fp-done",
            state = "completed",
            localPath = null,
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 1L,
            byteSize = 8L,
            committedCount = 1L,
        )
        assetQueries.markAssetInBatch(
            batchId = "batch-done",
            contentHash = "hash-done",
            configFingerprint = "fp-done",
            id = doneId,
        )
        assetQueries.markAssetDone(
            actualInputTokens = 10L,
            actualOutputTokens = 20L,
            id = doneId,
        )
        recognitionQueries.insertRecognition(
            assetId = doneId,
            promptVersion = "1",
            modelId = "qwen3-vl-flash",
            json = """{"summary":"已经识别"}""",
            searchText = "已经识别",
        )

        index.disableSource("album", "bucket-a")
        assertEquals(0L, index.pendingCount())
        assertEquals("done", assetQueries.selectAssetById(doneId).executeAsOne().heldStatus)

        val restored = index.enableSource(
            "album",
            "bucket-a",
            "相册甲",
            listOf(file("done-1"), file("wait-1"), file("new-1")),
        )
        assertEquals(1, restored.inserted)
        val done = assetQueries.selectAssetById(doneId).executeAsOne()
        assertEquals("done", done.status)
        assertNull(done.heldStatus)
        assertEquals("hash-done", done.contentHash)
        assertEquals("fp-done", done.configFingerprint)
        assertEquals("""{"summary":"已经识别"}""", recognitionQueries.selectRecognitionByAssetId(doneId).executeAsOne().json)
        assertEquals(2L, index.pendingCount())
        assertEquals(3L, index.countBySource(album.sourceId))
    }

    @Test
    fun 移出范围开关默认关闭且改其他设置后仍在() = databaseTest {
        val index = ScopeIndex(this)
        assertEquals(false, index.deleteResultsOutOfScope())
        index.setDeleteResultsOutOfScope(true)
        val setting = settingQueries.selectSetting().executeAsOne()
        settingQueries.updateSetting(
            provider = setting.provider,
            endpoint = setting.endpoint,
            model = setting.model,
            thinkingEnabled = setting.thinkingEnabled,
            thinkingTokenLimit = setting.thinkingTokenLimit,
            longEdge = setting.longEdge,
            jpegQuality = setting.jpegQuality,
            detailLevel = setting.detailLevel,
            uploadMode = setting.uploadMode,
            batchMaxBytes = setting.batchMaxBytes,
            batchMaxLines = setting.batchMaxLines,
            concurrentBatches = setting.concurrentBatches,
            wifiOnly = setting.wifiOnly,
            chargingOnly = setting.chargingOnly,
            inputPricePerMillion = setting.inputPricePerMillion,
            outputPricePerMillion = setting.outputPricePerMillion,
            matchMode = setting.matchMode,
            amountAlertYuan = setting.amountAlertYuan,
            synonyms = setting.synonyms,
        )
        assertEquals(true, ScopeIndex(this).deleteResultsOutOfScope())
        index.setDeleteResultsOutOfScope(false)
        assertEquals(0L, settingQueries.selectSetting().executeAsOne().deleteResultsOutOfScope)
    }

    @Test
    fun 再次打开同一个库仍能读到待处理和开关() {
        val file = File.createTempFile("photo-index-scope", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            openPhotoIndexDatabase(path).use { opened ->
                val index = ScopeIndex(opened.database)
                index.enableSource("album", "bucket-a", "相册甲", listOf(file("a1"), file("a2")))
                index.setDeleteResultsOutOfScope(true)
                assertEquals(2L, index.pendingCount())
            }
            openPhotoIndexDatabase(path).use { opened ->
                val index = ScopeIndex(opened.database)
                assertEquals(2L, index.pendingCount())
                assertEquals(true, index.deleteResultsOutOfScope())
                assertEquals("相册甲", index.enabledSources().single().displayName)
            }
        } finally {
            file.delete()
            File("$path-wal").delete()
            File("$path-shm").delete()
        }
    }

    private fun databaseTest(body: PhotoIndexDatabase.() -> Unit) {
        val opened = openPhotoIndexDatabase()
        try {
            opened.database.body()
        } finally {
            opened.close()
        }
    }

    private fun file(
        systemId: String,
        name: String = "$systemId.jpg",
        modified: Long = 1_700_000_000_000L,
    ) = PictureFile(
        systemId = systemId,
        fileName = name,
        relativePath = "Camera/$name",
        sizeBytes = 128L,
        dateModifiedMillis = modified,
        dateTakenMillis = null,
        sourceKey = "bucket",
    )
}
