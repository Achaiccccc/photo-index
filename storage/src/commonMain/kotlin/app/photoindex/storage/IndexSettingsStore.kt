package app.photoindex.storage

import app.photoindex.core.DetailLevel
import app.photoindex.core.IndexSettings
import app.photoindex.core.QuoteNotConfirmed
import app.photoindex.core.QuotePreview
import app.photoindex.core.previewQuote
import app.photoindex.core.validateIndexSettings

/**
 * 读写设计文档第 5.1 节的设置，以及「用户已确认估价」的标记。
 * 这里没有 API Key 参数。确认只把标记写成已确认，不改任务状态，不建批，不上传。
 * [runUpload] 是上传的入口：没有标记时抛出 [QuoteNotConfirmed]，上传函数不会执行。
 */
class IndexSettingsStore(
    private val database: PhotoIndexDatabase,
) {
    fun load(): IndexSettings {
        val row = database.settingQueries.selectSetting().executeAsOne()
        return IndexSettings(
            provider = row.provider,
            endpoint = row.endpoint,
            model = row.model,
            thinkingEnabled = row.thinkingEnabled == 1L,
            thinkingTokenLimit = row.thinkingTokenLimit.toInt(),
            longEdge = row.longEdge?.toInt(),
            jpegQuality = row.jpegQuality.toInt(),
            detailLevel = DetailLevel.fromStorage(row.detailLevel),
            uploadMode = row.uploadMode,
            batchMaxBytes = row.batchMaxBytes,
            batchMaxLines = row.batchMaxLines.toInt(),
            concurrentBatches = row.concurrentBatches.toInt(),
            wifiOnly = row.wifiOnly == 1L,
            chargingOnly = row.chargingOnly == 1L,
            inputPricePerMillion = row.inputPricePerMillion,
            outputPricePerMillion = row.outputPricePerMillion,
            matchMode = row.matchMode,
            amountAlertYuan = row.amountAlertYuan,
            synonyms = row.synonyms,
            deleteResultsOutOfScope = row.deleteResultsOutOfScope == 1L,
        )
    }

    fun save(settings: IndexSettings) {
        validateIndexSettings(settings)
        val previous = load()
        database.settingQueries.updateSetting(
            provider = settings.provider,
            endpoint = settings.endpoint,
            model = settings.model,
            thinkingEnabled = settings.thinkingEnabled.bit(),
            thinkingTokenLimit = settings.thinkingTokenLimit.toLong(),
            longEdge = settings.longEdge?.toLong(),
            jpegQuality = settings.jpegQuality.toLong(),
            detailLevel = settings.detailLevel.storageValue,
            uploadMode = settings.uploadMode,
            batchMaxBytes = settings.batchMaxBytes,
            batchMaxLines = settings.batchMaxLines.toLong(),
            concurrentBatches = settings.concurrentBatches.toLong(),
            wifiOnly = settings.wifiOnly.bit(),
            chargingOnly = settings.chargingOnly.bit(),
            inputPricePerMillion = settings.inputPricePerMillion,
            outputPricePerMillion = settings.outputPricePerMillion,
            matchMode = settings.matchMode,
            amountAlertYuan = settings.amountAlertYuan,
            synonyms = settings.synonyms,
        )
        if (previous != settings) {
            database.quoteConfirmationQueries.clearConfirmed()
        }
    }

    fun pendingCount(): Int = database.assetQueries.countPending().executeAsOne().toInt()

    fun preview(): QuotePreview = previewQuote(load(), pendingCount())

    fun confirmed(): Boolean = database.quoteConfirmationQueries.selectConfirmed().executeAsOne() == 1L

    /** 只写入确认标记。调用方要留在确认页，不能在这里启动任务。 */
    fun confirm() {
        database.quoteConfirmationQueries.markConfirmed()
    }

    fun runUpload(upload: () -> Unit) {
        if (!confirmed()) throw QuoteNotConfirmed()
        upload()
    }

    private fun Boolean.bit(): Long = if (this) 1L else 0L
}
