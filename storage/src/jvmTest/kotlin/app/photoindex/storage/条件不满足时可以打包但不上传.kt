package app.photoindex.storage

import app.photoindex.core.BatchPackLimits
import app.photoindex.core.BatchState
import app.photoindex.core.FAKE_DETAIL_RECORD
import app.photoindex.core.FixedDetailBatchProvider
import app.photoindex.core.JobState
import app.photoindex.core.ManualUploadConditions
import app.photoindex.core.MemoryFakeProviderLedger
import app.photoindex.core.decodeFakeBatchLedger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 上传条件用可切换的假开关，不依赖真的拔电。
 * 假服务商的记录写成文本再读回来，用来模拟进程被杀掉之后的第二个实例。
 */
class 条件不满足时可以打包但不上传 : 批状态机与中断恢复() {
    @Test
    fun 关掉充电后只打包不上传再打开后才上传一次() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("a", "b").forEach { database.insertAsset(it) }
            val gate = ManualUploadConditions(onWifi = true, charging = false)
            val ledger = MemoryFakeProviderLedger()
            val packing = indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(ledger),
                compressor = FixedCompressor(),
                newBatchId = batchIds(),
                uploadAllowed = { gate.allows(wifiOnly = true, chargingOnly = true) },
            )
            packing.start()

            val packed = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.PACKED, packed.state)
            assertEquals(null, packed.remoteBatchId)
            assertEquals(0, decodeFakeBatchLedger(ledger.read()).uploadCount)
            assertEquals(2, database.assetQueries.selectAssetsByStatus("in_batch").executeAsList().size)

            gate.charging = true
            val revivedLedger = MemoryFakeProviderLedger(ledger.read())
            indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(revivedLedger),
                compressor = ThrowingCompressor(),
                newBatchId = batchIds(),
                uploadAllowed = { gate.allows(wifiOnly = true, chargingOnly = true) },
            ).resume()

            val done = database.assetQueries.selectAssetsByStatus("done").executeAsList()
            assertEquals(listOf("a", "b"), done.map { it.id })
            val completed = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(BatchState.COMPLETED, completed.state)
            assertTrue(completed.remoteBatchId != null)
            assertEquals(1, decodeFakeBatchLedger(revivedLedger.read()).uploadCount)
            assertEquals(0, directory.jsonlCount())
            assertEquals(FAKE_DETAIL_RECORD.summary, summaryOf(database, "a"))
        }
    }

    @Test
    fun 拿到任务号后换一个状态机不会再次上传() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("a", "b").forEach { database.insertAsset(it) }
            val ledger = MemoryFakeProviderLedger()
            var now = 0L
            val ids = batchIds()
            indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(ledger, holdMillis = 10_000) { now },
                compressor = FixedCompressor(),
                newBatchId = ids,
            ).start()

            val submitted = database.batchQueries.selectAllBatches().executeAsOne()
            val taskId = submitted.remoteBatchId
            assertEquals(BatchState.RUNNING, submitted.state)
            assertTrue(taskId != null)
            assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)

            now = 10_000
            indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(MemoryFakeProviderLedger(ledger.read()), holdMillis = 10_000) { now },
                compressor = ThrowingCompressor(),
                newBatchId = ids,
            ).resume()

            assertEquals(taskId, database.batchQueries.selectAllBatches().executeAsOne().remoteBatchId)
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectAllBatches().executeAsOne().state)
            assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)
            assertEquals(listOf("a", "b"), database.assetQueries.selectAssetsByStatus("done").executeAsList().map { it.id })
        }
    }

    @Test
    fun 暂停后不再封新批但已提交的那批会完成() {
        scenario { database, directory ->
            database.insertAlbum()
            listOf("a", "b", "c").forEach { database.insertAsset(it) }
            val ledger = MemoryFakeProviderLedger()
            var now = 0L
            val ids = batchIds()
            val limits = BatchPackLimits(maxFileBytes = 1_000_000, maxLines = 1, maxLineBytes = 200_000)
            val running = indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(ledger, holdMillis = 10_000) { now },
                compressor = FixedCompressor(),
                newBatchId = ids,
                limits = limits,
            )
            running.start()
            assertEquals(1, database.batchQueries.selectAllBatches().executeAsList().size)
            assertEquals(2, database.assetQueries.countPending().executeAsOne().toInt())
            running.pause()
            assertEquals(JobState.PAUSED, database.jobQueries.selectJob().executeAsOne().state)

            now = 10_000
            indexing(
                database = database,
                directory = directory,
                provider = fakeProvider(MemoryFakeProviderLedger(ledger.read()), holdMillis = 10_000) { now },
                compressor = ThrowingCompressor(),
                newBatchId = ids,
                limits = limits,
            ).resume()

            assertEquals(1, database.batchQueries.selectAllBatches().executeAsList().size)
            assertEquals(BatchState.COMPLETED, database.batchQueries.selectAllBatches().executeAsOne().state)
            assertEquals(listOf("a"), database.assetQueries.selectAssetsByStatus("done").executeAsList().map { it.id })
            assertEquals(listOf("b", "c"), database.assetQueries.selectAssetsByStatus("pending").executeAsList().map { it.id })
            assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)
            assertEquals(JobState.PAUSED, database.jobQueries.selectJob().executeAsOne().state)
        }
    }

    private fun fakeProvider(
        ledger: MemoryFakeProviderLedger,
        holdMillis: Long = 0,
        now: () -> Long = { 0L },
    ): FixedDetailBatchProvider = FixedDetailBatchProvider(
        ledger = ledger,
        readLines = { path -> File(path).readLines() },
        writeText = { path, text ->
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        },
        holdMillis = holdMillis,
        now = now,
    )

    private fun summaryOf(database: PhotoIndexDatabase, assetId: String): String {
        val json = database.recognitionQueries.selectRecognitionByAssetId(assetId).executeAsOne().json
        return Regex("\"summary\":\"([^\"]*)\"").find(json)?.groupValues?.get(1).orEmpty()
    }

    private fun File.jsonlCount(): Int =
        listFiles()?.count { it.isFile && it.name.endsWith(".jsonl") } ?: 0
}
