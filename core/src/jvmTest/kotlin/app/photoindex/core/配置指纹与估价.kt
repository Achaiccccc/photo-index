package app.photoindex.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class 配置指纹与估价 {
    @Test
    fun 只改单价指纹不变() {
        val base = defaultRecognitionSettings()
        val repriced = base.overridingPrices(inputPerMillionYuan = 9.9, outputPerMillionYuan = 88.0)

        assertEquals(base.fingerprint(), repriced.fingerprint())
        assertTrue(
            estimateRecognitionCost(repriced, listOf(EXAMPLE_IMAGE)).perImageRealtime.point >
                estimateRecognitionCost(base, listOf(EXAMPLE_IMAGE)).perImageRealtime.point,
        )
    }

    @Test
    fun 匹配方式和网络开关不进指纹() {
        val base = defaultRecognitionSettings()
        val changed = base.copy(matchMode = "all", wifiOnly = false, chargingOnly = false)

        assertEquals(base.fingerprint(), changed.fingerprint())
    }

    @Test
    fun 模型思考压缩和提示词版本会改变指纹() {
        val base = defaultRecognitionSettings()

        assertNotEquals(base.fingerprint(), base.copy(provider = "doubao").fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(model = "qwen3-vl-plus").fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(thinkingEnabled = true).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(thinkingTokenLimit = 2048).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(longEdge = 512).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(longEdge = null).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(jpegQuality = 60).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(detailLevel = DetailLevel.BRIEF).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(promptVersion = "2").fingerprint())
    }

    @Test
    fun 打开思考后金额上升() {
        val off = defaultRecognitionSettings()
        val on = off.copy(thinkingEnabled = true, thinkingTokenLimit = 1024)
        val offCost = estimateRecognitionCost(off, listOf(EXAMPLE_IMAGE))
        val onCost = estimateRecognitionCost(on, listOf(EXAMPLE_IMAGE))

        assertTrue(onCost.perImageRealtime.point > offCost.perImageRealtime.point)
        assertTrue(onCost.perImageBatch.point > offCost.perImageBatch.point)

        val batchOutputIncrease = 1024.0 * off.outputPricePerMillion * BATCH_PRICE_RATIO / 1_000_000.0
        assertEquals(batchOutputIncrease, onCost.perImageBatch.point - offCost.perImageBatch.point, TOLERANCE)
        assertTrue(abs(batchOutputIncrease - 0.0008) < 0.00005)
    }

    @Test
    fun 批量约为实时的一半() {
        val images = listOf(EXAMPLE_IMAGE, CompressedImageSize(800, 600))
        val cost = estimateRecognitionCost(defaultRecognitionSettings(), images)

        assertEquals(cost.perImageRealtime.point * BATCH_PRICE_RATIO, cost.perImageBatch.point, TOLERANCE)
        assertEquals(cost.perImageRealtime.low * BATCH_PRICE_RATIO, cost.perImageBatch.low, TOLERANCE)
        assertEquals(cost.perImageRealtime.high * BATCH_PRICE_RATIO, cost.perImageBatch.high, TOLERANCE)
        assertEquals(cost.totalRealtime.point * BATCH_PRICE_RATIO, cost.totalBatch.point, TOLERANCE)
        assertEquals(cost.totalRealtime.point, cost.perImageRealtime.point * images.size, TOLERANCE)
    }

    @Test
    fun 千问Flash详细约1280乘960思考关时数量级与公式一致() {
        val settings = defaultRecognitionSettings()
        val cost = estimateRecognitionCost(settings, listOf(EXAMPLE_IMAGE))
        val imageTokens = qwenImageTokens(1280, 960)
        val inputTokens = imageTokens + DetailLevel.DETAILED.promptTokens
        val outputTokens = DetailLevel.DETAILED.plannedOutputTokens
        val realtime = inputTokens * settings.inputPricePerMillion / 1_000_000.0 +
            outputTokens * settings.outputPricePerMillion / 1_000_000.0

        assertEquals(1202L, imageTokens)
        assertEquals(1602L, inputTokens)
        assertEquals(600L, outputTokens)
        assertEquals(realtime, cost.perImageRealtime.point, TOLERANCE)
        assertEquals(realtime * BATCH_PRICE_RATIO, cost.perImageBatch.point, TOLERANCE)
        assertTrue(abs(cost.perImageRealtime.point - 0.0011) < 0.00005)
        assertTrue(abs(cost.perImageBatch.point - 0.00057) < 0.00002)
        assertFalse(cost.calibration.applied)
    }

    @Test
    fun 详细模式的区间只缩放输出规划值() {
        val settings = defaultRecognitionSettings()
        val cost = estimateRecognitionCost(settings, listOf(EXAMPLE_IMAGE))
        val inputYuan = 1602.0 * settings.inputPricePerMillion / 1_000_000.0
        val low = inputYuan + 600.0 * DetailLevel.DETAILED.outputLowFactor * settings.outputPricePerMillion / 1_000_000.0
        val high = inputYuan + 600.0 * DetailLevel.DETAILED.outputHighFactor * settings.outputPricePerMillion / 1_000_000.0

        assertEquals(0.4, DetailLevel.DETAILED.outputLowFactor, 0.0)
        assertEquals(2.5, DetailLevel.DETAILED.outputHighFactor, 0.0)
        assertEquals(0.5, DetailLevel.BRIEF.outputLowFactor, 0.0)
        assertEquals(1.5, DetailLevel.BRIEF.outputHighFactor, 0.0)
        assertEquals(low, cost.perImageRealtime.low, TOLERANCE)
        assertEquals(high, cost.perImageRealtime.high, TOLERANCE)
        assertTrue(cost.perImageRealtime.low < cost.perImageRealtime.point)
        assertTrue(cost.perImageRealtime.point < cost.perImageRealtime.high)
        assertNotEquals(cost.perImageRealtime.point * 0.4, cost.perImageRealtime.low, TOLERANCE)
    }

    @Test
    fun 简要模式使用另一档提示词和输出规划值() {
        val settings = defaultRecognitionSettings().copy(detailLevel = DetailLevel.BRIEF)
        val image = CompressedImageSize(640, 480)
        val planned = plannedTokens(image, settings.detailLevel, settings.thinkingEnabled, settings.thinkingTokenLimit)
        val cost = estimateRecognitionCost(settings, listOf(image))
        val expected = planned.inputTokens * settings.inputPricePerMillion / 1_000_000.0 +
            planned.outputTokens * settings.outputPricePerMillion / 1_000_000.0

        assertEquals(150L, planned.promptTokens)
        assertEquals(80L, planned.outputTokens)
        assertEquals(expected, cost.perImageRealtime.point, TOLERANCE)
    }

    @Test
    fun 合计按每张压缩后的真实宽高相加() {
        val wide = CompressedImageSize(1280, 960)
        val narrow = CompressedImageSize(512, 512)
        val settings = defaultRecognitionSettings()
        val together = estimateRecognitionCost(settings, listOf(wide, narrow))
        val separate = estimateRecognitionCost(settings, listOf(wide)).totalRealtime.point +
            estimateRecognitionCost(settings, listOf(narrow)).totalRealtime.point

        assertEquals(separate, together.totalRealtime.point, TOLERANCE)
        assertTrue(qwenImageTokens(wide.widthPx, wide.heightPx) != qwenImageTokens(narrow.widthPx, narrow.heightPx))
    }

    @Test
    fun 第19张不校准第20张开始校准() {
        val settings = defaultRecognitionSettings()
        val planned = plannedTokens(EXAMPLE_IMAGE, settings.detailLevel, settings.thinkingEnabled, settings.thinkingTokenLimit)
        val sample = TokenUsageSample(
            estimatedInputTokens = planned.inputTokens,
            actualInputTokens = planned.inputTokens * 2,
            estimatedOutputTokens = planned.outputTokens,
            actualOutputTokens = planned.outputTokens * 2,
        )
        val uncalibrated = estimateRecognitionCost(settings, listOf(EXAMPLE_IMAGE))
        val at19 = estimateRecognitionCost(settings, listOf(EXAMPLE_IMAGE), List(19) { sample })
        val at20 = estimateRecognitionCost(settings, listOf(EXAMPLE_IMAGE), List(20) { sample })

        assertEquals(19, at19.calibration.sampleCount)
        assertFalse(at19.calibration.applied)
        assertEquals(1.0, at19.calibration.inputFactor, 0.0)
        assertEquals(1.0, at19.calibration.outputFactor, 0.0)
        assertEquals(uncalibrated.perImageRealtime.point, at19.perImageRealtime.point, TOLERANCE)

        assertEquals(20, at20.calibration.sampleCount)
        assertTrue(at20.calibration.applied)
        assertEquals(2.0, at20.calibration.inputFactor, TOLERANCE)
        assertEquals(2.0, at20.calibration.outputFactor, TOLERANCE)
        assertEquals(uncalibrated.perImageRealtime.point * 2, at20.perImageRealtime.point, TOLERANCE)
        assertEquals(uncalibrated.totalBatch.point * 2, at20.totalBatch.point, TOLERANCE)
    }

    @Test
    fun 校准系数是各张实际与估算之比的平均() {
        val low = TokenUsageSample(100, 100, 50, 40)
        val high = TokenUsageSample(100, 300, 50, 80)
        val samples = List(10) { low } + List(10) { high }
        val calibration = calibrate(samples)

        assertTrue(calibration.applied)
        assertEquals(2.0, calibration.inputFactor, TOLERANCE)
        assertEquals((0.8 + 1.6) / 2.0, calibration.outputFactor, TOLERANCE)
    }

    @Test
    fun 预置单价按设计文档写入默认可被调用方覆盖() {
        val defaults = defaultRecognitionSettings()
        assertEquals("qwen", defaults.provider)
        assertEquals("qwen3-vl-flash", defaults.model)
        assertFalse(defaults.thinkingEnabled)
        assertEquals(1024, defaults.thinkingTokenLimit)
        assertEquals(1280, defaults.longEdge)
        assertEquals(80, defaults.jpegQuality)
        assertEquals(DetailLevel.DETAILED, defaults.detailLevel)
        assertEquals(PROMPT_VERSION, defaults.promptVersion)
        assertEquals(0.15, defaults.inputPricePerMillion, 0.0)
        assertEquals(1.5, defaults.outputPricePerMillion, 0.0)

        assertEquals(0.15, PresetPrices.qwenVlFlash.inputPricePerMillion, 0.0)
        assertEquals(1.5, PresetPrices.qwenVlFlash.outputPricePerMillion, 0.0)
        assertEquals(1.0, PresetPrices.qwenVlPlus.inputPricePerMillion, 0.0)
        assertEquals(10.0, PresetPrices.qwenVlPlus.outputPricePerMillion, 0.0)
        assertEquals(0.2, PresetPrices.doubaoSeedMini.inputPricePerMillion, 0.0)
        assertEquals(2.0, PresetPrices.doubaoSeedMini.outputPricePerMillion, 0.0)
        assertEquals("", PresetPrices.doubaoSeedMini.model)
        assertEquals(0.6, PresetPrices.doubaoSeedLite.inputPricePerMillion, 0.0)
        assertEquals(3.6, PresetPrices.doubaoSeedLite.outputPricePerMillion, 0.0)
        assertEquals(2.0, PresetPrices.zhipuGlm45v.inputPricePerMillion, 0.0)
        assertEquals(6.0, PresetPrices.zhipuGlm45v.outputPricePerMillion, 0.0)
        assertEquals(2.16, PresetPrices.gemini25Flash.inputPricePerMillion, 0.0)
        assertEquals(18.0, PresetPrices.gemini25Flash.outputPricePerMillion, 0.0)
        assertTrue(PresetPrices.all.all { !it.thinkingEnabledByDefault })

        val overridden = PresetPrices.qwenVlFlash.prices(inputOverride = 0.5, outputOverride = null)
        assertEquals(0.5, overridden.inputPerMillionYuan, 0.0)
        assertEquals(1.5, overridden.outputPerMillionYuan, 0.0)
        val custom = defaultRecognitionSettings(
            inputPricePerMillion = overridden.inputPerMillionYuan,
            outputPricePerMillion = overridden.outputPerMillionYuan,
        )
        assertEquals(defaults.fingerprint(), custom.fingerprint())
        assertTrue(
            estimateRecognitionCost(custom, listOf(EXAMPLE_IMAGE)).perImageRealtime.point >
                estimateRecognitionCost(defaults, listOf(EXAMPLE_IMAGE)).perImageRealtime.point,
        )
    }

    private companion object {
        val EXAMPLE_IMAGE = CompressedImageSize(1280, 960)
        const val TOLERANCE = 1e-12
    }
}
