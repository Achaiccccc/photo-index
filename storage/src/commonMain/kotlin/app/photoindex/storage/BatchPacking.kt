package app.photoindex.storage

import app.photoindex.core.BatchLedger
import app.photoindex.core.BatchPackConfig
import app.photoindex.core.BatchPackOutcome
import app.photoindex.core.BatchFileSinkFactory
import app.photoindex.core.ImageCompressor
import app.photoindex.core.PendingImageSource
import app.photoindex.core.SealedBatchDraft
import app.photoindex.core.UnsealedBatch
import app.photoindex.core.packImageBatch

/**
 * 按图片 ID 顺序每次读出一张待处理图。已经交给打包器的 ID 不会再读出来，
 * 即使封口事务还没把它们改成 in_batch。
 */
class PendingAssetIds(
    private val database: PhotoIndexDatabase,
) : PendingImageSource {
    private var afterId = ""

    override fun nextId(): String? {
        val id = database.assetQueries.selectNextPendingId(afterId).executeAsOneOrNull() ?: return null
        afterId = id
        return id
    }
}

fun packPendingImages(
    database: PhotoIndexDatabase,
    source: PendingImageSource,
    compressor: ImageCompressor,
    files: BatchFileSinkFactory,
    config: BatchPackConfig,
    newBatchId: () -> String,
): BatchPackOutcome = packImageBatch(
    source = source,
    compressor = compressor,
    files = files,
    ledger = PhotoIndexBatchLedger(database),
    newBatchId = newBatchId,
    config = config,
)

/** 输入读完后补记最后一批。调用前，这个文件不在 batch 表里。 */
fun sealOutstandingBatch(
    database: PhotoIndexDatabase,
    batch: UnsealedBatch,
) {
    app.photoindex.core.sealOutstandingBatch(PhotoIndexBatchLedger(database), batch)
}

private class PhotoIndexBatchLedger(
    private val database: PhotoIndexDatabase,
) : BatchLedger {
    override fun seal(batch: SealedBatchDraft) {
        database.transaction {
            database.batchQueries.insertBatch(
                id = batch.id,
                configFingerprint = batch.configFingerprint,
                state = "packed",
                localPath = batch.localPath,
                remoteFileId = null,
                remoteBatchId = null,
                outputFileId = null,
                lineCount = batch.lineCount.toLong(),
                byteSize = batch.byteSize,
                committedCount = 0L,
            )
            batch.assets.forEach { asset ->
                database.assetQueries.markAssetInBatch(
                    batchId = batch.id,
                    contentHash = asset.contentHash,
                    configFingerprint = batch.configFingerprint,
                    id = asset.id,
                )
                val stored = database.assetQueries.selectAssetById(asset.id).executeAsOne()
                check(stored.status == "in_batch" && stored.batchId == batch.id) {
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
}
