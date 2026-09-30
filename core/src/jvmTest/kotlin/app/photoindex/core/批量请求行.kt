package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class 批量请求行 {
    @Test
    fun 关闭思考时一行是JPEG的dataURI并显式限制输出() {
        val line = batchRequestLine(
            assetId = "photo-1",
            jpegBytes = byteArrayOf(1, 2, 3),
            model = "qwen3-vl-flash",
            thinkingEnabled = false,
            thinkingTokenLimit = 1024,
            detailLevel = DetailLevel.DETAILED,
        )

        assertEquals(1, line.split("\"custom_id\"").size - 1)
        assertFalse(line.contains('\n') || line.contains('\r'))
        assertTrue(line.contains("\"enable_thinking\":false"))
        assertTrue(line.contains("\"max_tokens\":600"))
        assertFalse(line.contains("thinking_budget"))

        val root = readJsonValue(line) as JsonValue.Obj
        assertEquals("photo-1", root.str("custom_id"))
        assertEquals("POST", root.str("method"))
        assertEquals("/v1/chat/completions", root.str("url"))
        val body = root.obj("body")
        assertEquals("qwen3-vl-flash", body.str("model"))
        assertIs<JsonValue.Bool>(body.fields.getValue("enable_thinking"))
        assertIs<JsonValue.Num>(body.fields.getValue("max_tokens"))
        val messages = body.arr("messages")
        assertEquals(1, messages.size)
        val content = (messages[0] as JsonValue.Obj).arr("content")
        assertEquals(2, content.size)
        val image = content[0] as JsonValue.Obj
        assertEquals("image_url", image.str("type"))
        val url = image.obj("image_url").str("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertFalse(url.contains("\n"))
        val text = content[1] as JsonValue.Obj
        assertEquals("text", text.str("type"))
        assertEquals(recognitionPrompt(DetailLevel.DETAILED), text.str("text"))
    }

    @Test
    fun 简要档关闭思考时输出上限用80() {
        val line = batchRequestLine(
            assetId = "photo-1",
            jpegBytes = byteArrayOf(1),
            model = "qwen3-vl-flash",
            thinkingEnabled = false,
            thinkingTokenLimit = 1024,
            detailLevel = DetailLevel.BRIEF,
        )
        assertTrue(line.contains("\"max_tokens\":80"))
        val text = ((readJsonValue(line) as JsonValue.Obj)
            .obj("body")
            .arr("messages")[0] as JsonValue.Obj)
            .arr("content")[1] as JsonValue.Obj
        assertEquals(recognitionPrompt(DetailLevel.BRIEF), text.str("text"))
    }

    @Test
    fun 打开思考时带思考上限且两行开关相同() {
        val first = sampleLine(assetId = "photo-1", thinkingEnabled = true)
        val second = sampleLine(assetId = "photo-2", thinkingEnabled = true)
        assertTrue(first.contains("\"enable_thinking\":true"))
        assertTrue(first.contains("\"thinking_budget\":1024"))
        assertFalse(first.contains("max_tokens"))
        assertTrue(second.contains("\"model\":\"qwen3-vl-flash\""))
        assertTrue(second.contains("\"enable_thinking\":true"))
        assertEquals(1, second.split("\"custom_id\"").size - 1)
        assertTrue(readJsonValue(first) != null)
        assertTrue(readJsonValue(second) != null)
    }

    @Test
    fun 图片ID里的引号不会变成第二个custom_id() {
        val line = sampleLine(assetId = "a\"b", thinkingEnabled = false)
        assertEquals(1, line.split("\"custom_id\"").size - 1)
        val root = readJsonValue(line) as JsonValue.Obj
        assertEquals("a\"b", root.str("custom_id"))
    }

    @Test
    fun 降质量只降20且不低于40() {
        assertEquals(60, reducedJpegQuality(80))
        assertEquals(40, reducedJpegQuality(60))
    }

    @Test
    fun 内容哈希是sha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    private fun sampleLine(assetId: String, thinkingEnabled: Boolean): String = batchRequestLine(
        assetId = assetId,
        jpegBytes = byteArrayOf(9, 8, 7),
        model = "qwen3-vl-flash",
        thinkingEnabled = thinkingEnabled,
        thinkingTokenLimit = 1024,
        detailLevel = DetailLevel.DETAILED,
    )

    private fun JsonValue.Obj.str(key: String): String = (fields.getValue(key) as JsonValue.Str).value

    private fun JsonValue.Obj.obj(key: String): JsonValue.Obj = fields.getValue(key) as JsonValue.Obj

    private fun JsonValue.Obj.arr(key: String): List<JsonValue> = (fields.getValue(key) as JsonValue.Arr).items
}
