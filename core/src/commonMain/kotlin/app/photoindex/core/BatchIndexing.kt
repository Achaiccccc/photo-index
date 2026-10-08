package app.photoindex.core

/**
 * 设计文档第 4.3 节的批状态机。
 * 进度只写在 [BatchCatalog] 里。进程被杀之后，用同一个库新建一个实例再调用 [recover] 或 [resume]。
 *
 * 状态：packing → packed → uploading → uploaded → submitted → running → committing → completed。
 * 另有 failed、cancelled、expired。
 * 暂停不再封口、也不上传还没有任务 ID 的批；已经有任务 ID 的批仍下载并入库。
 * 明确取消才调用取消接口。已经跑完的行入库，没跑完的回到 pending，不自动重试。
 */
class BatchIndexing(
    private val catalog: BatchCatalog,
    private val provider: BatchProvider,
    private val workspace: BatchWorkspace,
    private val compressor: ImageCompressor,
    private val files: BatchFileSinkFactory,
    private val newBatchId: () -> String,
    private val limits: BatchPackLimits,
    private val control: BatchRunControl = BatchRunControl(),
    private val uploadAllowed: () -> Boolean = { true },
) {
    private var halted = false

    /**
     * 用户确认估价之后才能开始。
     * 没有确认标记时抛出 [QuoteNotConfirmed]，不改任务状态，不打包，不上传。
     * 已经在跑时再次调用，只是接着恢复。
     */
    fun start() {
        if (!catalog.quoteConfirmed()) throw QuoteNotConfirmed()
        catalog.setJobState(JobState.RUNNING)
        resume()
    }

    /**
     * 新进程进来时先恢复已有的批，再按任务状态决定要不要封口新批。
     * 任务不是 running 时，这里不会打包。
     * 没有确认标记时也不封口新批；已经有任务 ID 的批仍可查询和入库。
     */
    fun resume() {
        recover()
        if (halted) return
        if (catalog.jobState() != JobState.RUNNING) return
        if (!catalog.quoteConfirmed()) return
        while (catalog.pendingCount() > 0 && canPackAnother()) {
            val pendingBefore = catalog.pendingCount()
            val batchesBefore = catalog.batches().size
            packOne()
            if (halted || control.stopBeforeSeal || control.stopAfterSeal) return
            if (catalog.pendingCount() == pendingBefore && catalog.batches().size == batchesBefore) return
            recover()
            if (halted) return
        }
    }

    /**
     * 只处理已经落盘的进度：丢掉没封口的半截文件，并推进已有的批。
     * 不打包新批，因此封口前被杀不会调用服务商。
     */
    fun recover() {
        discardPacking()
        deleteOrphanFiles()
        for (batch in catalog.batches()) {
            if (halted) return
            advance(batch)
            if (halted) return
        }
    }

    /** 不再封口新批。已经有任务 ID 的批继续查询、下载、入库。 */
    fun pause() {
        catalog.setJobState(JobState.PAUSED)
        recover()
    }

    /**
     * 调用取消。结果里已经跑完的行入库；同批里没出现的图回到 pending。
     * 任务改为 idle，接下来的 [resume] 不会自动把这些图再打进新批。
     */
    fun cancel() {
        catalog.setJobState(JobState.IDLE)
        discardPacking()
        deleteOrphanFiles()
        for (batch in catalog.batches()) {
            if (batch.state in BatchState.terminal) {
                tidy(batch.id)
                continue
            }
            val taskId = batch.remoteBatchId
            if (taskId == null) {
                batch.localPath?.let { workspace.deleteIfExists(it) }
                batch.remoteFileId?.let { fileId ->
                    provider.deleteRemoteFile(fileId)
                    catalog.clearRemoteFileId(batch.id)
                }
                catalog.resetInBatchToPending(batch.id)
                catalog.markTerminal(batch.id, BatchState.CANCELLED)
                continue
            }
            provider.cancel(taskId)
            val remote = provider.query(taskId)
            if (remote.outputFileId != null || remote.errorFileId != null) {
                download(batch.id, remote)
                if (!commitFile(batch.id, MissingLinePolicy.LEAVE)) return
            }
            catalog.resetInBatchToPending(batch.id)
            val path = catalog.batch(batch.id).localPath
            catalog.markTerminal(batch.id, BatchState.CANCELLED)
            if (path != null) workspace.deleteIfExists(path)
            tidy(batch.id)
        }
    }

    /** 只把 failed 改回 pending。已经 done 的图留在原处，不会进新批。 */
    fun retryFailed() {
        if (!catalog.quoteConfirmed()) throw QuoteNotConfirmed()
        catalog.requeueFailed()
        catalog.setJobState(JobState.RUNNING)
        resume()
    }

    private fun packOne() {
        val config = catalog.packConfig(limits)
        val ledger = if (control.stopBeforeSeal) SealGate(catalog) else catalog
        val outcome = try {
            packImageBatch(
                source = catalog.pendingSource(),
                compressor = compressor,
                files = files,
                ledger = ledger,
                newBatchId = newBatchId,
                config = config,
                stopAfterSealedBatches = 1,
            )
        } catch (_: SealInterrupted) {
            halted = true
            return
        }
        if (control.stopBeforeSeal) {
            halted = true
            return
        }
        val tail = outcome.unsealed ?: return
        sealOutstandingBatch(catalog, tail)
    }

    private fun advance(batch: StoredBatch) {
        if (batch.state in BatchState.terminal) {
            tidy(batch.id)
            return
        }
        if (batch.state == BatchState.PACKING) return
        var current = batch
        if (current.remoteBatchId == null) {
            if (!catalog.quoteConfirmed()) return
            if (!canSend()) return
            if (current.remoteFileId == null) {
                val path = current.localPath ?: error("已封口的批 ${current.id} 没有本地文件")
                catalog.markUploading(current.id)
                val fileId = provider.upload(path)
                catalog.markUploaded(current.id, fileId)
                workspace.deleteIfExists(path)
                if (control.stopAfterUpload) {
                    halted = true
                    return
                }
                current = catalog.batch(current.id)
            } else if (current.localPath != null) {
                workspace.deleteIfExists(current.localPath)
                catalog.clearLocalPath(current.id)
                current = catalog.batch(current.id)
            }
            if (!canSend()) return
            val fileId = current.remoteFileId ?: return
            val taskId = provider.createTask(fileId)
            catalog.markSubmitted(current.id, taskId)
            if (control.stopAfterSubmit) {
                halted = true
                return
            }
            current = catalog.batch(current.id)
        }
        poll(current)
    }

    private fun poll(batch: StoredBatch) {
        if (batch.state == BatchState.COMMITTING) {
            val path = batch.localPath ?: workspace.resultPath(batch.id)
            if (!workspace.exists(path)) {
                val outputId = batch.outputFileId ?: error("正在入库的批 ${batch.id} 没有结果文件")
                provider.download(outputId, path)
                catalog.markCommitting(batch.id, outputId, path)
            }
            if (!commitFile(batch.id, MissingLinePolicy.FAIL)) return
            failLeftovers(batch.id, "结果里没有这张图")
            finish(batch.id, BatchState.COMPLETED, stopQueue = false)
            return
        }
        val taskId = batch.remoteBatchId ?: return
        val remote = provider.query(taskId)
        when (remote.phase) {
            RemoteBatchPhase.RUNNING -> catalog.markRunning(batch.id)
            RemoteBatchPhase.COMPLETED -> {
                download(batch.id, remote)
                if (!commitFile(batch.id, MissingLinePolicy.FAIL)) return
                failLeftovers(batch.id, "结果里没有这张图")
                finish(batch.id, BatchState.COMPLETED, stopQueue = false)
            }
            RemoteBatchPhase.FAILED -> {
                if (remote.outputFileId != null || remote.errorFileId != null) {
                    download(batch.id, remote)
                    if (!commitFile(batch.id, MissingLinePolicy.FAIL)) return
                }
                failLeftovers(batch.id, remote.error ?: "批量任务失败")
                finish(batch.id, BatchState.FAILED, stopQueue = true)
            }
            RemoteBatchPhase.EXPIRED -> {
                if (remote.outputFileId != null || remote.errorFileId != null) {
                    download(batch.id, remote)
                    if (!commitFile(batch.id, MissingLinePolicy.LEAVE)) return
                }
                catalog.resetInBatchToPending(batch.id)
                finish(batch.id, BatchState.EXPIRED, stopQueue = true)
            }
            RemoteBatchPhase.CANCELLED -> {
                if (remote.outputFileId != null || remote.errorFileId != null) {
                    download(batch.id, remote)
                    if (!commitFile(batch.id, MissingLinePolicy.LEAVE)) return
                }
                catalog.resetInBatchToPending(batch.id)
                finish(batch.id, BatchState.CANCELLED, stopQueue = true)
            }
        }
    }

    /**
     * 成功行和失败行合成一个本地结果文件，再交给入库。
     * 两边都有时，失败文件在本地写好后就删掉远端副本；输入文件和成功文件仍等入库结束后再删。
     * 进程若死在写完之前，恢复只认库里记下的那一个文件 ID。
     */
    private fun download(batchId: String, remote: RemoteBatch) {
        val storedId = remote.outputFileId ?: remote.errorFileId ?: error("批 $batchId 没有结果文件")
        val path = workspace.resultPath(batchId)
        catalog.markCommitting(batchId, storedId, path)
        if (workspace.exists(path)) return
        val lines = mutableListOf<String>()
        remote.outputFileId?.let { lines += pull(it, "$path.output") }
        if (remote.errorFileId != null && remote.errorFileId != remote.outputFileId) {
            lines += pull(remote.errorFileId, "$path.error")
        }
        workspace.writeLines(path, lines)
        if (remote.outputFileId != null && remote.errorFileId != null) {
            provider.deleteRemoteFile(remote.errorFileId)
        }
    }

    private fun pull(fileId: String, partPath: String): List<String> {
        provider.download(fileId, partPath)
        val lines = workspace.readLines(partPath)
        workspace.deleteIfExists(partPath)
        return lines
    }

    /**
     * 按 [BatchRunControl.commitChunkSize] 行一个事务写入。
     * 已经是 done 的跳过，避免重放时再写一遍。
     * 返回 false 表示这一次在事务边界上停住了，批还留在 committing。
     */
    private fun commitFile(batchId: String, missing: MissingLinePolicy): Boolean {
        val batch = catalog.batch(batchId)
        val path = batch.localPath ?: error("批 $batchId 没有本地结果文件")
        val config = catalog.packConfig(limits)
        val lines = workspace.readLines(path).filter { it.isNotBlank() }
        val chunk = mutableListOf<CommitRow>()
        var committed = batch.committedCount
        var chunks = 0
        for (line in lines) {
            val parsed = parseBatchResultLine(line)
            if (parsed == null) {
                flush(batchId, chunk, committed)
                failLeftovers(batchId, "结果行无法解析")
                return true
            }
            val asset = catalog.asset(parsed.customId)
            if (asset.status == "done") continue
            if (asset.batchId != batchId || asset.status != "in_batch") continue
            chunk += toRow(parsed, config)
            if (chunk.size == control.commitChunkSize) {
                committed += chunk.size
                catalog.commitChunk(batchId, chunk, committed)
                chunk.clear()
                chunks += 1
                if (reachedChunkLimit(chunks)) return false
            }
        }
        if (chunk.isNotEmpty()) {
            committed += chunk.size
            catalog.commitChunk(batchId, chunk, committed)
            chunks += 1
            if (reachedChunkLimit(chunks)) return false
        }
        return true
    }

    private fun reachedChunkLimit(chunks: Int): Boolean {
        val limit = control.stopAfterCommitChunks ?: return false
        if (chunks < limit) return false
        halted = true
        return true
    }

    private fun flush(batchId: String, chunk: MutableList<CommitRow>, committed: Int) {
        if (chunk.isEmpty()) return
        catalog.commitChunk(batchId, chunk.toList(), committed + chunk.size)
        chunk.clear()
    }

    private fun toRow(parsed: BatchResultLine, config: BatchPackConfig): CommitRow = when (parsed) {
        is BatchResultLine.Bad -> CommitRow.Failed(parsed.customId, parsed.reason)
        is BatchResultLine.Ok -> when (val model = parseModelOutput(parsed.content)) {
            is ModelParseResult.Rejected -> CommitRow.Failed(parsed.customId, model.reason)
            is ModelParseResult.Accepted -> {
                val merged = mergeOnRerun(
                    previousUser = catalog.userTerms(parsed.customId),
                    newModel = model.record,
                    local = catalog.localText(parsed.customId),
                )
                CommitRow.Succeeded(
                    assetId = parsed.customId,
                    promptVersion = PROMPT_VERSION,
                    modelId = config.model,
                    json = merged.model.toStableJson(),
                    searchText = merged.searchText,
                    inputTokens = parsed.inputTokens,
                    outputTokens = parsed.outputTokens,
                )
            }
        }
    }

    private fun failLeftovers(batchId: String, reason: String) {
        catalog.assetsInBatch(batchId)
            .filter { it.status == "in_batch" }
            .forEach { catalog.markInBatchFailed(it.id, reason) }
    }

    private fun finish(batchId: String, state: String, stopQueue: Boolean) {
        val path = catalog.batch(batchId).localPath
        catalog.markTerminal(batchId, state)
        if (path != null) workspace.deleteIfExists(path)
        tidy(batchId)
        if (stopQueue) {
            catalog.setJobState(JobState.IDLE)
            halted = true
        }
    }

    private fun tidy(batchId: String) {
        val batch = catalog.batch(batchId)
        if (batch.localPath != null) {
            workspace.deleteIfExists(batch.localPath)
            catalog.clearLocalPath(batchId)
        }
        val remoteFileId = batch.remoteFileId
        if (remoteFileId != null) {
            provider.deleteRemoteFile(remoteFileId)
            catalog.clearRemoteFileId(batchId)
        }
        val outputFileId = batch.outputFileId
        if (outputFileId != null) {
            provider.deleteRemoteFile(outputFileId)
            catalog.clearOutputFileId(batchId)
        }
    }

    private fun discardPacking() {
        catalog.batches().filter { it.state == BatchState.PACKING }.forEach { batch ->
            batch.localPath?.let { workspace.deleteIfExists(it) }
            catalog.resetInBatchToPending(batch.id)
            catalog.deleteBatch(batch.id)
        }
    }

    private fun deleteOrphanFiles() {
        val referenced = catalog.batches().mapNotNull { it.localPath }.map { workspace.normalize(it) }.toSet()
        workspace.listPaths().filter { workspace.normalize(it) !in referenced }.forEach { workspace.deleteIfExists(it) }
    }

    private fun canPackAnother(): Boolean {
        if (catalog.jobState() != JobState.RUNNING) return false
        val active = catalog.batches().filter { it.state !in BatchState.terminal && it.state != BatchState.PACKING }
        if (active.any { it.remoteBatchId == null }) return false
        val inFlight = active.count { it.remoteBatchId != null }
        return inFlight < catalog.concurrentBatches()
    }

    private fun canSend(): Boolean = catalog.jobState() == JobState.RUNNING && uploadAllowed()
}

object BatchState {
    const val PACKING = "packing"
    const val PACKED = "packed"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val SUBMITTED = "submitted"
    const val RUNNING = "running"
    const val COMMITTING = "committing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    val terminal = setOf(COMPLETED, FAILED, CANCELLED, EXPIRED)
}

object JobState {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val PAUSED = "paused"
}

/** 默认 50 行一个事务。测试把这个数改小，用来把入库停在半截。 */
const val DEFAULT_COMMIT_CHUNK = 50

data class BatchRunControl(
    val commitChunkSize: Int = DEFAULT_COMMIT_CHUNK,
    val stopBeforeSeal: Boolean = false,
    val stopAfterSeal: Boolean = false,
    val stopAfterUpload: Boolean = false,
    val stopAfterSubmit: Boolean = false,
    val stopAfterCommitChunks: Int? = null,
) {
    init {
        require(commitChunkSize > 0) { "入库事务的行数必须为正数" }
        val chunks = stopAfterCommitChunks
        if (chunks != null) require(chunks > 0) { "停住前至少要提交一个事务" }
    }
}

data class StoredBatch(
    val id: String,
    val configFingerprint: String,
    val state: String,
    val localPath: String?,
    val remoteFileId: String?,
    val remoteBatchId: String?,
    val outputFileId: String?,
    val lineCount: Int,
    val byteSize: Long,
    val committedCount: Int,
)

data class StoredAsset(
    val id: String,
    val status: String,
    val batchId: String?,
)

sealed interface CommitRow {
    val assetId: String

    data class Succeeded(
        override val assetId: String,
        val promptVersion: String,
        val modelId: String,
        val json: String,
        val searchText: String,
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : CommitRow

    data class Failed(
        override val assetId: String,
        val reason: String,
    ) : CommitRow
}

/** 假服务商或千问适配器都实现这一组。状态机不关心 HTTP。 */
interface BatchProvider {
    fun upload(localPath: String): String
    fun createTask(remoteFileId: String): String
    fun query(remoteBatchId: String): RemoteBatch
    fun download(outputFileId: String, destinationPath: String)
    fun cancel(remoteBatchId: String)
    fun deleteRemoteFile(remoteFileId: String)
}

/** 批文件和结果文件都在这个目录里。状态机不直接碰文件系统。 */
interface BatchWorkspace {
    fun listPaths(): List<String>
    fun normalize(path: String): String
    fun deleteIfExists(path: String)
    fun exists(path: String): Boolean
    fun readLines(path: String): List<String>
    fun writeLines(path: String, lines: List<String>)
    fun resultPath(batchId: String): String
}

/**
 * 状态机要记住的进度。实现必须在进程退出后还能读到，不能只放在内存对象里。
 */
interface BatchCatalog : BatchLedger {
    fun jobState(): String
    fun setJobState(state: String)

    /** 用户是否已经确认当前估价。没有确认时不能上传。 */
    fun quoteConfirmed(): Boolean
    fun concurrentBatches(): Int
    fun packConfig(limits: BatchPackLimits): BatchPackConfig
    fun pendingSource(): PendingImageSource
    fun pendingCount(): Int
    fun batches(): List<StoredBatch>
    fun batch(id: String): StoredBatch
    fun asset(id: String): StoredAsset
    fun assetsInBatch(batchId: String): List<StoredAsset>
    fun userTerms(assetId: String): UserTerms
    fun localText(assetId: String): LocalPictureText
    fun markUploading(id: String)
    fun markUploaded(id: String, remoteFileId: String)
    fun markSubmitted(id: String, remoteBatchId: String)
    fun markRunning(id: String)
    fun markCommitting(id: String, outputFileId: String, resultPath: String)
    fun commitChunk(batchId: String, rows: List<CommitRow>, committedCount: Int)
    fun markInBatchFailed(assetId: String, reason: String)
    fun resetInBatchToPending(batchId: String)
    fun requeueFailed()
    fun markTerminal(id: String, state: String)
    fun clearLocalPath(id: String)
    fun clearRemoteFileId(id: String)
    fun clearOutputFileId(id: String)
    fun deleteBatch(id: String)
}

private enum class MissingLinePolicy {
    FAIL,
    LEAVE,
}

/** 封口事务还没写进去。用来模拟打包过程中进程被杀。 */
private class SealInterrupted : RuntimeException()

private class SealGate(
    private val delegate: BatchLedger,
) : BatchLedger {
    override fun beginPacking(batchId: String, configFingerprint: String, localPath: String) {
        delegate.beginPacking(batchId, configFingerprint, localPath)
    }

    override fun seal(batch: SealedBatchDraft) {
        throw SealInterrupted()
    }

    override fun markLineTooLarge(assetId: String, reason: String) {
        delegate.markLineTooLarge(assetId, reason)
    }
}
