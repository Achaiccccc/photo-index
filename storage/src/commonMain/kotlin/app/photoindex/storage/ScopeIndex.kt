package app.photoindex.storage

import app.photoindex.core.PictureFile
import app.photoindex.core.stableLocalId

/** 勾选来源后写入的结果。inserted 是这次新出现的图，kept 是库里已经有的图。 */
data class ScopeEnableResult(
    val sourceId: String,
    val inserted: Int,
    val kept: Int,
)

/** 界面上仍可勾选的一个来源。 */
data class RememberedSource(
    val id: String,
    val kind: String,
    val systemKey: String,
    val displayName: String,
)

/** 仍在选择范围内的一张图。只带缩略图需要的标识，不含原图字节。 */
data class InScopePhoto(
    val id: String,
    val displayName: String,
    val systemId: String,
    val dateModified: Long,
    val kind: String,
    val systemKey: String,
)

/**
 * 把选中范围扫描进索引库。
 * 只写元数据，新图的状态是 pending。不压缩，不建批，不上传。
 * 取消来源时改成 out_of_scope，行和识别结果都留着。
 * 「移出范围时删除识别结果」只保存开关，这里不删除识别行。
 */
class ScopeIndex(
    private val database: PhotoIndexDatabase,
) {
    fun pendingCount(): Long = database.assetQueries.countPending().executeAsOne()

    fun countByStatus(status: String): Long =
        database.assetQueries.countAssetsByStatus(status).executeAsOne()

    fun countBySource(sourceId: String): Long =
        database.assetQueries.countAssetsBySource(sourceId).executeAsOne()

    fun deleteResultsOutOfScope(): Boolean =
        database.settingQueries.selectSetting().executeAsOne().deleteResultsOutOfScope == 1L

    fun setDeleteResultsOutOfScope(enabled: Boolean) {
        database.settingQueries.updateDeleteResultsOutOfScope(enabled = if (enabled) 1L else 0L)
    }

    fun enabledSources(): List<RememberedSource> {
        return database.sourceQueries.selectEnabledSources().executeAsList().map { row ->
            RememberedSource(
                id = row.id,
                kind = row.kind,
                systemKey = row.systemKey,
                displayName = row.displayName,
            )
        }
    }

    fun enableSource(
        kind: String,
        systemKey: String,
        displayName: String,
        files: List<PictureFile>,
    ): ScopeEnableResult {
        val sourceId = sourceId(kind, systemKey)
        database.transaction {
            val existing = database.sourceQueries.selectSourceById(sourceId).executeAsOneOrNull()
            if (existing == null) {
                database.sourceQueries.insertSource(
                    id = sourceId,
                    kind = kind,
                    systemKey = systemKey,
                    displayName = displayName,
                    enabled = 1L,
                )
            } else {
                database.sourceQueries.updateSourceEnabled(
                    enabled = 1L,
                    displayName = displayName,
                    id = sourceId,
                )
                database.assetQueries.restoreSourceScope(sourceId)
            }
        }
        val known = database.assetQueries.selectSystemIdsBySource(sourceId).executeAsList().toHashSet()
        val fresh = ArrayList<PictureFile>()
        val seen = HashSet<String>()
        for (file in files) {
            if (file.systemId.isBlank() || !seen.add(file.systemId)) continue
            if (file.systemId !in known) fresh += file
        }
        fresh.chunked(INSERT_CHUNK).forEach { chunk ->
            database.transaction {
                chunk.forEach { file -> insertPending(sourceId, file) }
            }
        }
        return ScopeEnableResult(
            sourceId = sourceId,
            inserted = fresh.size,
            kept = known.size,
        )
    }

    fun disableSource(kind: String, systemKey: String) {
        val sourceId = sourceId(kind, systemKey)
        val existing = database.sourceQueries.selectSourceById(sourceId).executeAsOneOrNull() ?: return
        database.transaction {
            database.assetQueries.markSourceOutOfScope(sourceId)
            database.sourceQueries.updateSourceEnabled(
                enabled = 0L,
                displayName = existing.displayName,
                id = sourceId,
            )
        }
    }

    /** afterModified 小于 0 时返回第一页。之后用上一页最后一张的修改时间和 ID 接着取。 */
    fun photosAfter(afterModified: Long, afterId: String, limit: Int): List<InScopePhoto> {
        return database.assetQueries.selectInScopePage(
            afterModified = afterModified,
            afterId = afterId,
            pageSize = limit.toLong(),
        ).executeAsList().map { row ->
            InScopePhoto(
                id = row.id,
                displayName = row.displayName,
                systemId = row.systemId,
                dateModified = row.dateModified,
                kind = row.kind,
                systemKey = row.systemKey,
            )
        }
    }

    private fun insertPending(sourceId: String, file: PictureFile) {
        database.assetQueries.insertAsset(
            id = stableLocalId("asset", sourceId, file.systemId),
            sourceId = sourceId,
            systemId = file.systemId,
            displayName = file.fileName.ifBlank { "未命名" },
            relativePath = file.relativePath,
            size = file.sizeBytes.coerceAtLeast(0L),
            dateModified = file.dateModifiedMillis.coerceAtLeast(0L),
            dateTaken = file.dateTakenMillis?.takeIf { it > 0L },
            contentHash = null,
            status = "pending",
            batchId = null,
            configFingerprint = "",
            attemptCount = 0L,
            lastError = null,
            actualInputTokens = null,
            actualOutputTokens = null,
        )
    }

    private companion object {
        const val INSERT_CHUNK = 80
    }
}

internal fun sourceId(kind: String, systemKey: String): String =
    stableLocalId("source", kind, systemKey)
