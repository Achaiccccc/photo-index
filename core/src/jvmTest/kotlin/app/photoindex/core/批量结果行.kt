package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class 批量结果行 {
    @Test
    fun 成功行能解析出图片ID和模型JSON() {
        val model = ModelRecord(summary = "周末的咖啡", objects = listOf("杯子"))
        val line = successfulBatchResultLine(
            customId = "photo-1",
            modelJson = model.toStableJson(),
            inputTokens = 1602,
            outputTokens = 80,
        )

        val parsed = assertIs<BatchResultLine.Ok>(parseBatchResultLine(line))
        assertEquals("photo-1", parsed.customId)
        assertEquals(model, assertIs<ModelParseResult.Accepted>(parseModelOutput(parsed.content)).record)
        assertEquals(1602L, parsed.inputTokens)
        assertEquals(80L, parsed.outputTokens)
    }

    @Test
    fun 错误行只标出这一张的原因() {
        val line = failedBatchResultLine(customId = "photo-2", message = "看不清")

        val parsed = assertIs<BatchResultLine.Bad>(parseBatchResultLine(line))
        assertEquals("photo-2", parsed.customId)
        assertEquals("看不清", parsed.reason)
    }

    @Test
    fun 没有图片ID的行不能安到某一张上() {
        assertNull(parseBatchResultLine("""{"response":{"status_code":200}}"""))
        assertNull(parseBatchResultLine("不是 JSON"))
    }
}
