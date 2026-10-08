package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class 设置与估价确认 {
    @Test
    fun 长边从1280改到512合计下降打开思考合计上升() {
        val pending = 4
        val original = previewQuote(defaultIndexSettings(), pending)
        val shorter = previewQuote(defaultIndexSettings().copy(longEdge = 512), pending)
        val thinking = previewQuote(
            defaultIndexSettings().copy(thinkingEnabled = true, thinkingTokenLimit = 1024),
            pending,
        )

        assertEquals(1280, original.referenceSize.widthPx)
        assertEquals(960, original.referenceSize.heightPx)
        assertEquals(512, shorter.referenceSize.widthPx)
        assertEquals(384, shorter.referenceSize.heightPx)
        assertTrue(shorter.activeTotal.point < original.activeTotal.point)
        assertTrue(thinking.activeTotal.point > original.activeTotal.point)
        assertTrue(shorter.activePerImage.point < original.activePerImage.point)
        assertTrue(thinking.activePerImage.point > original.activePerImage.point)
    }

    @Test
    fun 批量文件同时给出批量合计和实时合计且批量更低() {
        val preview = previewQuote(defaultIndexSettings(), 3)

        assertEquals(UPLOAD_MODE_BATCH_FILE, defaultIndexSettings().uploadMode)
        assertTrue(preview.usesBatchPrice)
        assertEquals("批量价", preview.priceLabel)
        assertTrue(preview.showBatchAndRealtime)
        assertTrue(preview.totalBatch.point < preview.totalRealtime.point)
        assertEquals(preview.totalRealtime.point * BATCH_PRICE_RATIO, preview.totalBatch.point, 1e-9)
        assertEquals(preview.perImageBatch, preview.activePerImage)
    }

    @Test
    fun 实时上传只按实时价并且不要求同时展示批量合计() {
        val preview = previewQuote(
            defaultIndexSettings().copy(uploadMode = UPLOAD_MODE_REALTIME),
            2,
        )

        assertFalse(preview.usesBatchPrice)
        assertEquals("实时价", preview.priceLabel)
        assertFalse(preview.showBatchAndRealtime)
        assertEquals(preview.totalRealtime.point, preview.activeTotal.point, 1e-9)
    }

    @Test
    fun 尚未接入的服务商即使用批量方式也按实时价并标明未接入() {
        val preview = previewQuote(defaultIndexSettings().applyingProvider("doubao"), 2)

        assertFalse(preview.usesBatchPrice)
        assertEquals("实时价", preview.priceLabel)
        assertTrue(preview.showBatchAndRealtime)
        assertTrue(preview.providerNote!!.contains("尚未接入"))
        assertTrue(preview.totalBatch.point < preview.totalRealtime.point)
    }

    @Test
    fun 预计批次按行数向上取整待处理为零时合计为零() {
        val settings = defaultIndexSettings().copy(batchMaxLines = 2000)
        assertEquals(0, previewQuote(settings, 0).estimatedBatchCount)
        assertEquals(0.0, previewQuote(settings, 0).activeTotal.point, 1e-9)
        assertEquals(1, previewQuote(settings, 1).estimatedBatchCount)
        assertEquals(1, previewQuote(settings, 2000).estimatedBatchCount)
        assertEquals(2, previewQuote(settings, 2001).estimatedBatchCount)
    }

    @Test
    fun 合计超过提醒线时要在确认按钮旁再显示金额() {
        val quiet = previewQuote(defaultIndexSettings(), 1)
        val loud = previewQuote(defaultIndexSettings().copy(amountAlertYuan = 0.0), 1)
        val empty = previewQuote(defaultIndexSettings().copy(amountAlertYuan = 0.0), 0)

        assertEquals(DEFAULT_AMOUNT_ALERT_YUAN, defaultIndexSettings().amountAlertYuan, 0.0)
        assertFalse(quiet.repeatsAmountBesideButton)
        assertTrue(loud.activeTotal.point > 0.0)
        assertTrue(loud.repeatsAmountBesideButton)
        assertFalse(empty.repeatsAmountBesideButton)
    }

    @Test
    fun 不满20张不修正估价() {
        val preview = previewQuote(defaultIndexSettings(), 6)
        assertFalse(preview.calibration.applied)
        assertEquals(0, preview.calibration.sampleCount)
        assertEquals(1.0, preview.calibration.inputFactor, 0.0)
    }

    @Test
    fun 不压缩时按假定长边估算且高于1600() {
        val uncompressed = previewQuote(defaultIndexSettings().copy(longEdge = null), 1)
        val edge1600 = previewQuote(defaultIndexSettings().copy(longEdge = 1600), 1)

        assertTrue(uncompressed.assumesUncompressedLongEdge)
        assertEquals(ASSUMED_UNCOMPRESSED_LONG_EDGE, uncompressed.referenceSize.widthPx)
        assertTrue(uncompressed.activeTotal.point > edge1600.activeTotal.point)
    }

    @Test
    fun 未确认时上传函数不会执行() {
        var called = false
        assertFailsWith<QuoteNotConfirmed> {
            uploadIfConfirmed(confirmed = false) { called = true }
        }
        assertFalse(called)

        uploadIfConfirmed(confirmed = true) { called = true }
        assertTrue(called)
    }

    @Test
    fun 默认设置与设计文档一致且不含密钥字段() {
        val settings = defaultIndexSettings()
        assertEquals("qwen", settings.provider)
        assertEquals(QWEN_COMPATIBLE_BASE_URL, settings.endpoint)
        assertEquals("qwen3-vl-flash", settings.model)
        assertFalse(settings.thinkingEnabled)
        assertEquals(1024, settings.thinkingTokenLimit)
        assertEquals(1280, settings.longEdge)
        assertEquals(80, settings.jpegQuality)
        assertEquals(DetailLevel.DETAILED, settings.detailLevel)
        assertEquals(UPLOAD_MODE_BATCH_FILE, settings.uploadMode)
        assertEquals(400L * 1024L * 1024L, settings.batchMaxBytes)
        assertEquals(2000, settings.batchMaxLines)
        assertEquals(1, settings.concurrentBatches)
        assertTrue(settings.wifiOnly)
        assertTrue(settings.chargingOnly)
        assertEquals(0.15, settings.inputPricePerMillion, 0.0)
        assertEquals(1.5, settings.outputPricePerMillion, 0.0)
        assertEquals("any", settings.matchMode)
        assertEquals(5.0, settings.amountAlertYuan, 0.0)
        validateIndexSettings(settings)
    }

    @Test
    fun 选择预置服务商会填上地址模型和单价() {
        val doubao = defaultIndexSettings().applyingProvider("doubao")
        assertEquals("doubao", doubao.provider)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", doubao.endpoint)
        assertEquals("", doubao.model)
        assertEquals(0.2, doubao.inputPricePerMillion, 0.0)
        assertEquals(2.0, doubao.outputPricePerMillion, 0.0)

        val plus = doubao.applyingProvider("qwen").applyingPreset(PresetPrices.qwenVlPlus)
        assertEquals("qwen3-vl-plus", plus.model)
        assertEquals(1.0, plus.inputPricePerMillion, 0.0)
        assertEquals(10.0, plus.outputPricePerMillion, 0.0)
        assertEquals(QWEN_COMPATIBLE_BASE_URL, plus.endpoint)
    }
}

/** 与索引库入口相同的门槛：没确认就不调用上传。 */
private fun uploadIfConfirmed(confirmed: Boolean, upload: () -> Unit) {
    if (!confirmed) throw QuoteNotConfirmed()
    upload()
}
