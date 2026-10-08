package app.photoindex.storage

import app.photoindex.core.LocalPictureText
import app.photoindex.core.ModelRecord
import app.photoindex.core.UserTerms
import app.photoindex.core.searchText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class 搜索与编辑不产生批记录 {
    @Test
    fun 删掉作者并追加词后能搜到新词且批记录不增加() = databaseTest {
        insertEnabledAlbum()
        val record = ModelRecord(
            summary = "窗边的杯子",
            author = "错误作者",
            ocrText = "周末喝咖啡",
        )
        insertDonePhoto(record, displayName = "杯子.jpg")
        batchQueries.insertBatch(
            id = "batch-1",
            configFingerprint = "fp-1",
            state = "completed",
            localPath = null,
            remoteFileId = null,
            remoteBatchId = "remote-1",
            outputFileId = null,
            lineCount = 1L,
            byteSize = 10L,
            committedCount = 1L,
        )
        val before = batchQueries.selectAllBatches().executeAsList().map { it.id }
        assertEquals(listOf("photo-1"), searchPhotos("错误作者").map { it.assetId })

        saveRecognitionEdits(
            assetId = "photo-1",
            addedTerms = listOf("手冲"),
            suppressedTerms = listOf("错误作者"),
        )

        assertTrue(searchPhotos("错误作者").isEmpty())
        assertEquals(listOf("photo-1"), searchPhotos("手冲").map { it.assetId })
        assertTrue(searchPhotos("手冲").single().excerpt.contains("手冲"))
        assertEquals(before, batchQueries.selectAllBatches().executeAsList().map { it.id })
        val stored = recognitionQueries.selectRecognitionByAssetId("photo-1").executeAsOne()
        assertEquals(record.toStableJson(), stored.json)
        assertTrue(stored.searchText.contains("手冲"))
        assertFalse(stored.searchText.contains("错误作者"))
        val edit = userEditQueries.selectUserEditByAssetId("photo-1").executeAsOne()
        assertEquals(listOf("手冲"), splitUserTerms(edit.addedTerms))
        assertEquals(listOf("错误作者"), splitUserTerms(edit.suppressedTerms))
    }

    @Test
    fun 空范围尚未建库和无结果各自留在自己的状态() = databaseTest {
        assertEquals(SearchLibraryState.EMPTY_SCOPE, searchLibraryState())

        insertEnabledAlbum()
        assertEquals(SearchLibraryState.NOT_INDEXED, searchLibraryState())

        insertDonePhoto(ModelRecord(summary = "窗边的杯子"), displayName = "杯子.jpg")
        assertEquals(SearchLibraryState.READY, searchLibraryState())
        assertTrue(searchPhotos("完全没有的词").isEmpty())

        sourceQueries.updateSourceEnabled(enabled = 0L, displayName = "相机", id = "album-1")
        assertEquals(SearchLibraryState.EMPTY_SCOPE, searchLibraryState())
    }

    @Test
    fun 带空格的屏蔽词整段去掉且旧的空格分隔仍能读回() = databaseTest {
        assertEquals(listOf("周末", "咖啡"), splitUserTerms("周末 咖啡"))
        assertEquals(listOf("错误 作者"), splitUserTerms(joinUserTerms(listOf("错误 作者"))))

        insertEnabledAlbum()
        val record = ModelRecord(summary = "风景", author = "错误 作者")
        insertDonePhoto(record)
        saveRecognitionEdits(
            assetId = "photo-1",
            addedTerms = emptyList(),
            suppressedTerms = listOf("错误 作者"),
        )

        val stored = recognitionQueries.selectRecognitionByAssetId("photo-1").executeAsOne()
        assertEquals(record.toStableJson(), stored.json)
        assertFalse(stored.searchText.contains("错误 作者"))
        assertTrue(searchPhotos("错误 作者").isEmpty())
        assertEquals(0, batchQueries.selectAllBatches().executeAsList().size)
    }

    private fun databaseTest(body: PhotoIndexDatabase.() -> Unit) {
        val opened = openPhotoIndexDatabase()
        try {
            opened.database.body()
        } finally {
            opened.close()
        }
    }

    private fun PhotoIndexDatabase.insertEnabledAlbum() {
        sourceQueries.insertSource(
            id = "album-1",
            kind = "album",
            systemKey = "bucket-camera",
            displayName = "相机",
            enabled = 1L,
        )
    }

    private fun PhotoIndexDatabase.insertDonePhoto(
        record: ModelRecord,
        displayName: String = "photo-1.jpg",
    ) {
        assetQueries.insertAsset(
            id = "photo-1",
            sourceId = "album-1",
            systemId = "media-photo-1",
            displayName = displayName,
            relativePath = "Camera/$displayName",
            size = 10L,
            dateModified = 10L,
            dateTaken = null,
            contentHash = null,
            status = "done",
            batchId = null,
            configFingerprint = "fp-1",
            attemptCount = 0L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
        recognitionQueries.insertRecognition(
            assetId = "photo-1",
            promptVersion = "2",
            modelId = "qwen3-vl-flash",
            json = record.toStableJson(),
            searchText = searchText(
                record = record,
                user = UserTerms(),
                local = LocalPictureText(fileName = displayName, albumName = "相机", takenDate = ""),
            ),
        )
    }
}
