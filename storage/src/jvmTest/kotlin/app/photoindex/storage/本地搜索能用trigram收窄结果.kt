package app.photoindex.storage

import app.photoindex.core.DEFAULT_SYNONYM_TABLE
import app.photoindex.core.LocalPictureText
import app.photoindex.core.ModelRecord
import app.photoindex.core.UserTerms
import app.photoindex.core.searchText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class 本地搜索能用trigram收窄结果 {
    @Test
    fun 打包的SQLite支持fts5的trigram分词() = databaseTest {
        val sql = SearchIndex.createSql(this, "searchFts")
        assertTrue(
            sql.contains("trigram"),
            "searchFts 必须使用 tokenize = trigram，实际为 $sql",
        )
    }

    @Test
    fun 小红命中小红书() = databaseTest {
        insertEnabledAlbum()
        insertPhoto(id = "xiaohongshu", searchText = "周末去了小红书喝咖啡", displayName = "笔记.jpg")
        insertPhoto(id = "dahongpao", searchText = "大红袍茶叶", displayName = "茶叶.jpg")

        assertTrue(searchPhotos("   ").isEmpty())
        val hits = searchPhotos("小红")

        assertEquals(listOf("xiaohongshu"), hits.map { it.assetId })
        assertEquals("笔记.jpg", hits.single().displayName)
        assertTrue(hits.single().excerpt.contains("小红"), "摘录应包含查询片段，实际为 ${hits.single().excerpt}")
    }

    @Test
    fun 全角英文查询按规范化后的子串命中() = databaseTest {
        insertEnabledAlbum()
        insertPhoto(id = "cafe", searchText = "cafe menu", displayName = "menu.jpg")

        assertEquals(listOf("cafe"), searchPhotos("ＣＡＦＥ").map { it.assetId })
    }

    @Test
    fun 三词命中多的排前面() = databaseTest {
        insertEnabledAlbum()
        insertPhoto(id = "hit-1", searchText = "只要咖啡")
        insertPhoto(id = "hit-3", searchText = "小红书 周末 咖啡")
        insertPhoto(id = "hit-2", searchText = "周末 咖啡 散步")
        insertPhoto(id = "hit-0", searchText = "风景和山")

        val ranked = searchPhotos("小红书 周末 咖啡")

        assertEquals(listOf("hit-3", "hit-2", "hit-1"), ranked.map { it.assetId })
        assertEquals(listOf(3, 2, 1), ranked.map { it.matchedSegments })
        assertEquals(listOf("hit-3"), searchPhotos("小红书 周末 咖啡", offset = 0, limit = 1).map { it.assetId })
        assertEquals(listOf("hit-2"), searchPhotos("小红书 周末 咖啡", offset = 1, limit = 1).map { it.assetId })
        assertEquals(listOf("hit-1"), searchPhotos("小红书 周末 咖啡", offset = 2, limit = 1).map { it.assetId })
        assertTrue(searchPhotos("小红书 周末 咖啡", offset = 3, limit = 1).isEmpty())
    }

    @Test
    fun AND模式下缺词不出现() = databaseTest {
        insertEnabledAlbum()
        insertPhoto(id = "hit-1", searchText = "只要咖啡")
        insertPhoto(id = "hit-3", searchText = "小红书 周末 咖啡")
        insertPhoto(id = "hit-2", searchText = "周末 咖啡 散步")
        setMatchMode("all")

        assertEquals(listOf("hit-3"), searchPhotos("小红书 周末 咖啡").map { it.assetId })
    }

    @Test
    fun out_of_scope不出现且只返回已完成并仍启用的来源() = databaseTest {
        insertEnabledAlbum()
        sourceQueries.insertSource(
            id = "album-off",
            kind = "album",
            systemKey = "bucket-off",
            displayName = "已取消",
            enabled = 0L,
        )
        insertPhoto(id = "visible", searchText = "小红书周末")
        insertPhoto(id = "hidden-scope", searchText = "小红书周末", status = "out_of_scope")
        insertPhoto(id = "hidden-pending", searchText = "小红书周末", status = "pending")
        insertPhoto(id = "hidden-failed", searchText = "小红书周末", status = "failed")
        insertPhoto(id = "hidden-batch", searchText = "小红书周末", status = "in_batch")
        insertPhoto(id = "hidden-source", searchText = "小红书周末", sourceId = "album-off")

        assertEquals(listOf("visible"), searchPhotos("小红书").map { it.assetId })
    }

    @Test
    fun 改词后立刻能搜到新词() = databaseTest {
        insertEnabledAlbum()
        insertPhoto(id = "photo-1", searchText = "只有旧词", displayName = "旧.jpg")

        assertTrue(searchPhotos("新词").isEmpty())

        val rewritten = searchText(
            record = ModelRecord(summary = "只有新词"),
            user = UserTerms(),
            local = LocalPictureText(fileName = "新.jpg", albumName = "", takenDate = ""),
        )
        recognitionQueries.updateRecognitionSearchText(searchText = rewritten, assetId = "photo-1")

        assertEquals(listOf("photo-1"), searchPhotos("新词").map { it.assetId })
        assertTrue(searchPhotos("新词").single().excerpt.contains("新词"))
        assertTrue(searchPhotos("旧词").isEmpty())
    }

    @Test
    fun 同义词在查询时扩展且不改已存的识别结果() = databaseTest {
        insertEnabledAlbum()
        val stored = "红书上的手帐"
        insertPhoto(id = "note", searchText = stored)

        assertEquals(listOf("note"), searchPhotos("小红书").map { it.assetId })
        assertEquals(stored, recognitionQueries.selectRecognitionByAssetId("note").executeAsOne().searchText)
        assertEquals(DEFAULT_SYNONYM_TABLE, settingQueries.selectSetting().executeAsOne().synonyms)

        setSynonyms("手帐 / 手账")

        assertEquals(listOf("note"), searchPhotos("手账").map { it.assetId })
        assertTrue(searchPhotos("小红书").isEmpty())
        assertEquals(stored, recognitionQueries.selectRecognitionByAssetId("note").executeAsOne().searchText)
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

    private fun PhotoIndexDatabase.insertPhoto(
        id: String,
        searchText: String,
        status: String = "done",
        sourceId: String = "album-1",
        displayName: String = "$id.jpg",
    ) {
        assetQueries.insertAsset(
            id = id,
            sourceId = sourceId,
            systemId = "media-$id",
            displayName = displayName,
            relativePath = "Camera/$id.jpg",
            size = 10L,
            dateModified = 10L,
            dateTaken = null,
            contentHash = null,
            status = status,
            batchId = null,
            configFingerprint = "fp-1",
            attemptCount = 0L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
        recognitionQueries.insertRecognition(
            assetId = id,
            promptVersion = "1",
            modelId = "qwen3-vl-flash",
            json = """{"summary":"$searchText"}""",
            searchText = searchText,
        )
    }

    private fun PhotoIndexDatabase.setMatchMode(mode: String) {
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
            matchMode = mode,
            amountAlertYuan = setting.amountAlertYuan,
            synonyms = setting.synonyms,
        )
    }

    private fun PhotoIndexDatabase.setSynonyms(synonyms: String) {
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
            synonyms = synonyms,
        )
    }
}
