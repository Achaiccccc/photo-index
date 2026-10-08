package app.photoindex.storage

import app.photoindex.core.BatchCatalog
import app.photoindex.core.BatchPackConfig
import app.photoindex.core.BatchPackLimits
import app.photoindex.core.BatchState
import app.photoindex.core.CommitRow
import app.photoindex.core.ConfigFingerprintInput
import app.photoindex.core.DetailLevel
import app.photoindex.core.LocalPictureText
import app.photoindex.core.PROMPT_VERSION
import app.photoindex.core.PendingImageSource
import app.photoindex.core.SealedBatchDraft
import app.photoindex.core.StoredAsset
import app.photoindex.core.StoredBatch
import app.photoindex.core.UserTerms
import app.photoindex.core.configFingerprint

/**
 * 把批状态机的进度写进索引库。调用方关掉进程再打开同一个库，就能读到这里的行。
 */
class PhotoIndexBatches(
    private val database: PhotoIndexDatabase,
) : BatchCatalog {
    override fun beginPacking(batchId: String, configFingerprint: String, localPath: String) {
        database.batchQueries.insertBatch(
            id = batchId,
            configFingerprint = configFingerprint,
            state = BatchState.PACKING,
            localPath = localPath,
            remoteFileId = null,
            remoteBatchId = null,
            outputFileId = null,
            lineCount = 0L,
            byteSize = 0L,
            committedCount = 0L,
        )
    }

    override fun seal(batch: SealedBatchDraft) {
        database.transaction {
            database.batchQueries.sealPackingBatch(
                localPath = batch.localPath,
                lineCount = batch.lineCount.toLong(),
                byteSize = batch.byteSize,
                id = batch.id,
            )
            val stored = database.batchQueries.selectBatchById(batch.id).executeAsOne()
            check(stored.state == BatchState.PACKED) { "批 ${batch.id} 没有从 packing 封口" }
            batch.assets.forEach { asset ->
                database.assetQueries.markAssetInBatch(
                    batchId = batch.id,
                    contentHash = asset.contentHash,
                    configFingerprint = batch.configFingerprint,
                    id = asset.id,
                )
                val row = database.assetQueries.selectAssetById(asset.id).executeAsOne()
                check(row.status == "in_batch" && row.batchId == batch.id) {
                    "图片 ${asset.id} 没有进入已封口的批"
                }
            }
        }
    }

    override fun markLineTooLarge(assetId: String, reason: String) {
        database.transaction {
            database.assetQueries.markAssetFailed(lastError = reason, id = assetId)
            val stored = database.assetQueries.selectAssetById(assetId).executeAsOne()
            check(stored.status == "failed") { "图片 $assetId 没有标成失败" }
        }
    }

    override fun jobState(): String = database.jobQueries.selectJob().executeAsOne().state

    override fun setJobState(state: String) {
        val job = database.jobQueries.selectJob().executeAsOne()
        database.jobQueries.updateJob(state = state, wifiOnly = job.wifiOnly, chargingOnly = job.chargingOnly)
    }

    override fun quoteConfirmed(): Boolean =
        database.quoteConfirmationQueries.selectConfirmed().executeAsOne() == 1L

    override fun concurrentBatches(): Int =
        database.settingQueries.selectSetting().executeAsOne().concurrentBatches.toInt()

    override fun packConfig(limits: BatchPackLimits): BatchPackConfig {
        val setting = database.settingQueries.selectSetting().executeAsOne()
        val detail = DetailLevel.fromStorage(setting.detailLevel)
        val fingerprint = configFingerprint(
            ConfigFingerprintInput(
                provider = setting.provider,
                model = setting.model,
                thinkingEnabled = setting.thinkingEnabled == 1L,
                thinkingTokenLimit = setting.thinkingTokenLimit.toInt(),
                longEdge = setting.longEdge?.toInt(),
                jpegQuality = setting.jpegQuality.toInt(),
                detailLevel = detail,
                promptVersion = PROMPT_VERSION,
            ),
        )
        return BatchPackConfig(
            model = setting.model,
            thinkingEnabled = setting.thinkingEnabled == 1L,
            thinkingTokenLimit = setting.thinkingTokenLimit.toInt(),
            jpegQuality = setting.jpegQuality.toInt(),
            detailLevel = detail,
            configFingerprint = fingerprint,
            limits = limits,
        )
    }

    override fun pendingSource(): PendingImageSource = PendingAssetIds(database)

    override fun pendingCount(): Int = database.assetQueries.countPending().executeAsOne().toInt()

    override fun batches(): List<StoredBatch> =
        database.batchQueries.selectAllBatches().executeAsList().map { it.toStored() }

    override fun batch(id: String): StoredBatch =
        database.batchQueries.selectBatchById(id).executeAsOne().toStored()

    override fun asset(id: String): StoredAsset {
        val row = database.assetQueries.selectAssetById(id).executeAsOne()
        return StoredAsset(id = row.id, status = row.status, batchId = row.batchId)
    }

    override fun assetsInBatch(batchId: String): List<StoredAsset> =
        database.assetQueries.selectAssetsByBatchId(batchId).executeAsList().map { row ->
            StoredAsset(id = row.id, status = row.status, batchId = row.batchId)
        }

    override fun userTerms(assetId: String): UserTerms {
        val row = database.userEditQueries.selectUserEditByAssetId(assetId).executeAsOneOrNull()
            ?: return UserTerms()
        return UserTerms(
            addedTerms = splitTerms(row.addedTerms),
            suppressedTerms = splitTerms(row.suppressedTerms),
        )
    }

    override fun localText(assetId: String): LocalPictureText {
        val asset = database.assetQueries.selectAssetById(assetId).executeAsOne()
        val source = database.sourceQueries.selectSourceById(asset.sourceId).executeAsOne()
        return LocalPictureText(
            fileName = asset.displayName,
            albumName = source.displayName,
            takenDate = asset.dateTaken?.toString().orEmpty(),
        )
    }

    override fun markUploading(id: String) {
        database.batchQueries.markBatchUploading(id)
    }

    override fun markUploaded(id: String, remoteFileId: String) {
        database.batchQueries.markBatchUploaded(remoteFileId = remoteFileId, id = id)
    }

    override fun markSubmitted(id: String, remoteBatchId: String) {
        database.batchQueries.markBatchSubmitted(remoteBatchId = remoteBatchId, id = id)
    }

    override fun markRunning(id: String) {
        database.batchQueries.markBatchRunning(id)
    }

    override fun markCommitting(id: String, outputFileId: String, resultPath: String) {
        database.batchQueries.markBatchCommitting(
            outputFileId = outputFileId,
            localPath = resultPath,
            id = id,
        )
    }

    override fun commitChunk(batchId: String, rows: List<CommitRow>, committedCount: Int) {
        database.transaction {
            rows.forEach { row ->
                when (row) {
                    is CommitRow.Succeeded -> {
                        val existing = database.recognitionQueries.selectRecognitionByAssetId(row.assetId)
                            .executeAsOneOrNull()
                        if (existing == null) {
                            database.recognitionQueries.insertRecognition(
                                assetId = row.assetId,
                                promptVersion = row.promptVersion,
                                modelId = row.modelId,
                                json = row.json,
                                searchText = row.searchText,
                            )
                        } else {
                            database.recognitionQueries.replaceRecognition(
                                promptVersion = row.promptVersion,
                                modelId = row.modelId,
                                json = row.json,
                                searchText = row.searchText,
                                assetId = row.assetId,
                            )
                        }
                        database.assetQueries.markAssetDone(
                            actualInputTokens = row.inputTokens,
                            actualOutputTokens = row.outputTokens,
                            id = row.assetId,
                        )
                    }
                    is CommitRow.Failed -> database.assetQueries.markInBatchAssetFailed(
                        lastError = row.reason,
                        id = row.assetId,
                    )
                }
            }
            database.batchQueries.updateCommittedCount(
                committedCount = committedCount.toLong(),
                id = batchId,
            )
        }
    }

    override fun markInBatchFailed(assetId: String, reason: String) {
        database.assetQueries.markInBatchAssetFailed(lastError = reason, id = assetId)
    }

    override fun resetInBatchToPending(batchId: String) {
        database.assetQueries.resetBatchAssetsToPending(batchId = batchId)
    }

    override fun requeueFailed() {
        database.assetQueries.requeueFailedAssets()
    }

    override fun markTerminal(id: String, state: String) {
        database.batchQueries.markBatchTerminal(state = state, id = id)
    }

    override fun clearLocalPath(id: String) {
        database.batchQueries.clearBatchLocalPath(id)
    }

    override fun clearRemoteFileId(id: String) {
        database.batchQueries.clearBatchRemoteFile(id)
    }

    override fun clearOutputFileId(id: String) {
        database.batchQueries.clearBatchOutputFile(id)
    }

    override fun deleteBatch(id: String) {
        database.batchQueries.deleteBatch(id)
    }
}

private fun splitTerms(text: String): List<String> =
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }

private fun app.photoindex.storage.Batch.toStored(): StoredBatch = StoredBatch(
    id = id,
    configFingerprint = configFingerprint,
    state = state,
    localPath = localPath,
    remoteFileId = remoteFileId,
    remoteBatchId = remoteBatchId,
    outputFileId = outputFileId,
    lineCount = lineCount.toInt(),
    byteSize = byteSize,
    committedCount = committedCount.toInt(),
)
