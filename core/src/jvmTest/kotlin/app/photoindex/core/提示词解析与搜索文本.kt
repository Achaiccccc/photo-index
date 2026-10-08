package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class 提示词解析与搜索文本 {
    @Test
    fun 详细提示词要求固定JSON且看不见的平台作者时间必须是空字符串() {
        val prompt = recognitionPrompt(DetailLevel.DETAILED)

        assertTrue(prompt.contains("\"summary\""))
        assertTrue(prompt.contains("\"objects\""))
        assertTrue(prompt.contains("\"scene\""))
        assertTrue(prompt.contains("\"platform\""))
        assertTrue(prompt.contains("\"author\""))
        assertTrue(prompt.contains("\"publishedAt\""))
        assertTrue(prompt.contains("\"ocrText\""))
        assertTrue(prompt.contains("\"tags\""))
        assertTrue(prompt.contains("图片类型不固定"))
        assertTrue(prompt.contains("表情包"))
        assertTrue(prompt.contains("宠物"))
        assertTrue(prompt.contains("帖子"))
        assertTrue(prompt.contains("空字符串"))
        assertTrue(prompt.contains("禁止") && prompt.contains("编造"))
        assertTrue(prompt.contains("不要把某一种图当成标准"))
        assertTrue(prompt.contains(PROMPT_VERSION))
    }

    @Test
    fun 简要提示词只要求摘要主体场景和标签且不要求逐字摘录() {
        val brief = recognitionPrompt(DetailLevel.BRIEF)
        val detailed = recognitionPrompt(DetailLevel.DETAILED)

        assertNotEquals(detailed, brief)
        assertTrue(brief.contains("\"summary\""))
        assertTrue(brief.contains("\"objects\""))
        assertTrue(brief.contains("\"scene\""))
        assertTrue(brief.contains("\"tags\""))
        assertTrue(brief.contains("不要求逐字摘录"))
        assertTrue(brief.contains("不要填写作者、时间和平台"))
        assertFalse(brief.contains("\"ocrText\""))
        assertFalse(brief.contains("\"publishedAt\""))
        assertTrue(brief.contains(PROMPT_VERSION))
    }

    @Test
    fun 提示词版本常量写进两份提示词并参与指纹() {
        val settings = defaultRecognitionSettings()

        assertEquals(PROMPT_VERSION, settings.promptVersion)
        assertEquals(PROMPT_VERSION, settings.fingerprintInput().promptVersion)
        assertNotEquals(
            settings.fingerprint(),
            settings.copy(promptVersion = "$PROMPT_VERSION-changed").fingerprint(),
        )
        assertTrue(recognitionPrompt(DetailLevel.DETAILED).contains("提示词版本：$PROMPT_VERSION"))
        assertTrue(recognitionPrompt(DetailLevel.BRIEF).contains("提示词版本：$PROMPT_VERSION"))
    }

    @Test
    fun 合法JSON解析成稳定字段且缺字段空数组空字符串都合法() {
        val full = parseModelOutput(
            """
            {
              "tags": ["手冲", ""],
              "ocrText": "今天喝咖啡",
              "publishedAt": "",
              "author": "张三",
              "platform": "小红书",
              "scene": [],
              "objects": ["杯子"],
              "summary": "周末的咖啡",
              "note": "忽略未知字段"
            }
            """.trimIndent(),
        )
        val record = assertIs<ModelParseResult.Accepted>(full).record

        assertEquals("周末的咖啡", record.summary)
        assertEquals(listOf("杯子"), record.objects)
        assertEquals(emptyList(), record.scene)
        assertEquals("小红书", record.platform)
        assertEquals("张三", record.author)
        assertEquals("", record.publishedAt)
        assertEquals("今天喝咖啡", record.ocrText)
        assertEquals(listOf("手冲", ""), record.tags)

        val stable = record.toStableJson()
        assertEquals(
            """{"summary":"周末的咖啡","objects":["杯子"],"scene":[],"platform":"小红书","author":"张三","publishedAt":"","ocrText":"今天喝咖啡","tags":["手冲",""]}""",
            stable,
        )
        assertEquals(record, assertIs<ModelParseResult.Accepted>(parseModelOutput(stable)).record)

        val sparse = assertIs<ModelParseResult.Accepted>(parseModelOutput("""{"summary":""}""")).record
        assertEquals("", sparse.summary)
        assertEquals(emptyList(), sparse.objects)
        assertEquals("", sparse.platform)
        assertEquals(
            """{"summary":"","objects":[],"scene":[],"platform":"","author":"","publishedAt":"","ocrText":"","tags":[]}""",
            sparse.toStableJson(),
        )

        val escaped = ModelRecord(summary = "他说\"你好\"\n下一行", ocrText = "C:\\图")
        assertEquals(escaped, assertIs<ModelParseResult.Accepted>(parseModelOutput(escaped.toStableJson())).record)
        val unicode = assertIs<ModelParseResult.Accepted>(
            parseModelOutput("""{"summary":"\u5c0f\u7ea2\u4e66"}"""),
        ).record
        assertEquals("小红书", unicode.summary)
    }

    @Test
    fun 不是JSON或半截文字不会变成结果() {
        val samples = listOf(
            "这是一只猫，不是 JSON",
            "{\"summary\":\"半截文字\"",
            """前言 {"summary":"完整"}""",
            """{"summary":"完整"} 还有半截""",
            "```json\n{\"summary\":\"完整\"}\n```",
            "",
            "[]",
            "null",
        )
        for (sample in samples) {
            val result = parseModelOutput(sample)
            val rejected = assertIs<ModelParseResult.Rejected>(result, sample)
            assertEquals(PARSE_NOT_JSON, rejected.reason)
            assertFalse(rejected.reason.contains("半截文字"))
            assertFalse(rejected.reason.contains("完整"))
        }
    }

    @Test
    fun 缺少summary或字段类型不对视为这一张失败() {
        val missing = assertIs<ModelParseResult.Rejected>(parseModelOutput("""{"objects":["猫"]}"""))
        assertEquals(PARSE_MISSING_SUMMARY, missing.reason)

        val nullSummary = assertIs<ModelParseResult.Rejected>(parseModelOutput("""{"summary":null}"""))
        assertEquals(PARSE_MISSING_SUMMARY, nullSummary.reason)

        val wrongSummary = assertIs<ModelParseResult.Rejected>(parseModelOutput("""{"summary":1}"""))
        assertEquals(PARSE_MISSING_SUMMARY, wrongSummary.reason)

        val wrongObjects = assertIs<ModelParseResult.Rejected>(
            parseModelOutput("""{"summary":"猫","objects":"猫"}"""),
        )
        assertEquals(PARSE_WRONG_FIELD_TYPE, wrongObjects.reason)

        val mixedArray = assertIs<ModelParseResult.Rejected>(
            parseModelOutput("""{"summary":"猫","tags":["a", 1]}"""),
        )
        assertEquals(PARSE_WRONG_FIELD_TYPE, mixedArray.reason)
    }

    @Test
    fun 屏蔽词不出现在searchText且本地字段仍在() {
        val record = ModelRecord(
            summary = "周末咖啡",
            objects = listOf("杯子"),
            scene = listOf("咖啡馆"),
            platform = "小红书",
            author = "张三",
            publishedAt = "2024-05-01",
            ocrText = "今天张三去喝咖啡",
            tags = listOf("手冲"),
        )
        val text = searchText(
            record = record,
            user = UserTerms(addedTerms = listOf("收藏"), suppressedTerms = listOf("张三")),
            local = LocalPictureText(
                fileName = "张三的照片.JPG",
                albumName = "旅行相册",
                takenDate = "2024-05-01",
            ),
        )

        assertFalse(text.contains("张三"))
        assertTrue(text.contains("周末咖啡"))
        assertTrue(text.contains("杯子"))
        assertTrue(text.contains("咖啡馆"))
        assertTrue(text.contains("小红书"))
        assertTrue(text.contains("今天"))
        assertTrue(text.contains("去喝咖啡"))
        assertTrue(text.contains("手冲"))
        assertTrue(text.contains("收藏"))
        assertTrue(text.contains("的照片.jpg"))
        assertTrue(text.contains("旅行相册"))
        assertTrue(text.contains("2024-05-01"))
        assertFalse(text.startsWith(" ") || text.endsWith(" "))
    }

    @Test
    fun 全角转半角且英文小写后空字段不进入搜索文本() {
        val text = searchText(
            record = ModelRecord(
                summary = "　Ｃａｆｅ　照片",
                objects = listOf("", "  "),
                platform = "",
                author = "ABC",
            ),
            user = UserTerms(addedTerms = listOf("　")),
            local = LocalPictureText(fileName = "IMG.JPEG", albumName = "", takenDate = ""),
        )

        assertEquals("cafe 照片 abc img.jpeg", text)
    }

    @Test
    fun 重跑后替换模型记录并保留用户追加词和屏蔽列表() {
        val previous = UserTerms(
            addedTerms = listOf("手冲咖啡"),
            suppressedTerms = listOf("并不存在的作者"),
        )
        val replacement = ModelRecord(
            summary = "窗边的杯子",
            platform = "",
            author = "并不存在的作者",
            ocrText = "旧摘要不会留在新记录里",
        )
        val merged = mergeOnRerun(
            previousUser = previous,
            newModel = replacement,
            local = LocalPictureText(fileName = "cup.JPG", albumName = "手机相册", takenDate = "2026-09-29"),
        )

        assertEquals(replacement, merged.model)
        assertEquals("", merged.model.platform)
        assertEquals(listOf("手冲咖啡"), merged.user.addedTerms)
        assertEquals(listOf("并不存在的作者"), merged.user.suppressedTerms)
        assertTrue(merged.searchText.contains("手冲咖啡"))
        assertTrue(merged.searchText.contains("窗边的杯子"))
        assertTrue(merged.searchText.contains("cup.jpg"))
        assertTrue(merged.searchText.contains("手机相册"))
        assertTrue(merged.searchText.contains("2026-09-29"))
        assertFalse(merged.searchText.contains("并不存在的作者"))
    }
}
