package app.photoindex

import app.photoindex.core.BatchState
import app.photoindex.core.IndexSettings
import app.photoindex.core.JobState
import app.photoindex.core.ProviderCatalog
import app.photoindex.core.UPLOAD_MODE_BATCH_FILE
import app.photoindex.core.currentBatchNumber
import app.photoindex.core.decodeFakeBatchLedger
import app.photoindex.core.indexNotificationText
import app.photoindex.platform.FileFakeProviderLedger
import app.photoindex.platform.UploadGate
import app.photoindex.platform.fakeLedgerFile
import app.photoindex.platform.systemLinkStatus
import app.photoindex.storage.IndexSettingsStore
import app.photoindex.storage.PhotoIndexDatabase
import android.content.Context

data class IndexBatchStatus(
    val state: String,
    val lineCount: Int,
    val remoteBatchId: String?,
)

data class IndexProgress(
    val doneCount: Int,
    val totalCount: Int,
    val pendingCount: Int,
    val jobState: String,
    val currentBatchNumber: Int,
    val batches: List<IndexBatchStatus>,
    val uploadCount: Int,
    val notificationText: String,
    val systemStatus: String,
)

/** 尚未接入的服务商，以及还没做的实时分批，确认后也不启动上传。 */
fun indexStartBlockReason(settings: IndexSettings): String? {
    val provider = ProviderCatalog.require(settings.provider)
    if (!provider.batchFileReady) return provider.note ?: "这个服务商尚未接入，不会上传。"
    if (settings.uploadMode != UPLOAD_MODE_BATCH_FILE) return "实时分批还没接入，不会上传。"
    return null
}

fun readIndexProgress(
    context: Context,
    database: PhotoIndexDatabase,
    uploadAllowed: Boolean,
): IndexProgress {
    val done = database.assetQueries.countAssetsByStatus("done").executeAsOne().toInt()
    val pending = database.assetQueries.countPending().executeAsOne().toInt()
    val inBatch = database.assetQueries.countAssetsByStatus("in_batch").executeAsOne().toInt()
    val failed = database.assetQueries.countAssetsByStatus("failed").executeAsOne().toInt()
    val batches = database.batchQueries.selectAllBatches().executeAsList()
    val job = database.jobQueries.selectJob().executeAsOne().state
    val waiting = job == JobState.RUNNING && !uploadAllowed && batches.any { batch ->
        batch.remoteBatchId == null && batch.state !in BatchState.terminal && batch.state != BatchState.PACKING
    }
    val number = currentBatchNumber(batches.map { it.state })
    val uploadCount = try {
        decodeFakeBatchLedger(FileFakeProviderLedger(fakeLedgerFile(context)).read()).uploadCount
    } catch (_: Exception) {
        0
    }
    return IndexProgress(
        doneCount = done,
        totalCount = done + pending + inBatch + failed,
        pendingCount = pending,
        jobState = job,
        currentBatchNumber = number,
        batches = batches.map { batch ->
            IndexBatchStatus(
                state = batch.state,
                lineCount = batch.lineCount.toInt(),
                remoteBatchId = batch.remoteBatchId,
            )
        },
        uploadCount = uploadCount,
        notificationText = indexNotificationText(
            doneCount = done,
            totalCount = done + pending + inBatch + failed,
            currentBatchNumber = number,
            paused = job == JobState.PAUSED,
            waitingForUpload = waiting,
        ),
        systemStatus = systemLinkStatus(context),
    )
}

fun uploadAllowedNow(context: Context, database: PhotoIndexDatabase): Boolean {
    val settings = IndexSettingsStore(database).load()
    return UploadGate(context).allows(settings.wifiOnly, settings.chargingOnly)
}
