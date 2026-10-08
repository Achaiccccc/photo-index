package app.photoindex.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class 假服务商与上传条件 {
    @Test
    fun 仅充电关掉时不允许上传打开后允许() {
        val gate = ManualUploadConditions(onWifi = true, charging = false)
        assertFalse(gate.allows(wifiOnly = true, chargingOnly = true))
        gate.charging = true
        assertTrue(gate.allows(wifiOnly = true, chargingOnly = true))
    }

    @Test
    fun 只要求WiFi时没充电也可以上传() {
        assertTrue(
            uploadAllowedByConditions(
                wifiOnly = true,
                chargingOnly = false,
                onWifi = true,
                charging = false,
            ),
        )
        assertFalse(
            uploadAllowedByConditions(
                wifiOnly = true,
                chargingOnly = false,
                onWifi = false,
                charging = true,
            ),
        )
    }

    @Test
    fun 两个条件都关时随时可以上传() {
        assertTrue(
            uploadAllowedByConditions(
                wifiOnly = false,
                chargingOnly = false,
                onWifi = false,
                charging = false,
            ),
        )
    }

    @Test
    fun 通知栏用已入库张数和当前批() {
        assertEquals(
            "已入库 3/10 · 当前第 2 批",
            indexNotificationText(
                doneCount = 3,
                totalCount = 10,
                currentBatchNumber = 2,
                paused = false,
                waitingForUpload = false,
            ),
        )
        assertEquals(
            "已暂停 · 已入库 3/10 · 当前第 2 批",
            indexNotificationText(
                doneCount = 3,
                totalCount = 10,
                currentBatchNumber = 2,
                paused = true,
                waitingForUpload = true,
            ),
        )
        assertEquals(
            "等待 Wi-Fi 或充电 · 已入库 0/4 · 当前第 1 批",
            indexNotificationText(
                doneCount = 0,
                totalCount = 4,
                currentBatchNumber = 1,
                paused = false,
                waitingForUpload = true,
            ),
        )
    }

    @Test
    fun 当前批指向还没结束的那一批() {
        assertEquals(0, currentBatchNumber(emptyList()))
        assertEquals(2, currentBatchNumber(listOf(BatchState.COMPLETED, BatchState.RUNNING)))
        assertEquals(2, currentBatchNumber(listOf(BatchState.COMPLETED, BatchState.COMPLETED)))
    }

    @Test
    fun 假服务商写出可解析的详细JSON且等待结束后任务号不变() {
        val directory = File(createTempDir(), "fake")
        directory.mkdirs()
        val ledger = MemoryFakeProviderLedger()
        var now = 0L
        val provider = provider(ledger, directory, holdMillis = 5_000) { now }
        val request = File(directory, "batch.jsonl")
        request.writeText(
            batchRequestLine(
                assetId = "photo-1",
                jpegBytes = byteArrayOf(1, 2, 3),
                model = "qwen3-vl-flash",
                thinkingEnabled = false,
                thinkingTokenLimit = 1024,
                detailLevel = DetailLevel.DETAILED,
            ),
        )
        val fileId = provider.upload(request.absolutePath)
        val taskId = provider.createTask(fileId)
        assertEquals(RemoteBatchPhase.RUNNING, provider.query(taskId).phase)
        assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)

        val revived = provider(MemoryFakeProviderLedger(ledger.read()), directory, holdMillis = 5_000) { now }
        assertEquals(RemoteBatchPhase.RUNNING, revived.query(taskId).phase)
        assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)

        now = 5_000
        val remote = revived.query(taskId)
        assertEquals(RemoteBatchPhase.COMPLETED, remote.phase)
        val result = File(directory, "result.jsonl")
        revived.download(remote.outputFileId!!, result.absolutePath)
        val parsed = parseBatchResultLine(result.readText())
        assertIs<BatchResultLine.Ok>(parsed)
        assertEquals("photo-1", parsed.customId)
        val model = parseModelOutput(parsed.content)
        assertIs<ModelParseResult.Accepted>(model)
        assertEquals(FAKE_DETAIL_RECORD, model.record)
        assertEquals(FAKE_INPUT_TOKENS, parsed.inputTokens)
        assertEquals(FAKE_OUTPUT_TOKENS, parsed.outputTokens)
        assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)
        assertEquals(taskId, decodeFakeBatchLedger(ledger.read()).tasks.keys.single())
    }

    @Test
    fun 同一文件再次创建任务仍返回原来的任务号() {
        val directory = createTempDir()
        val ledger = MemoryFakeProviderLedger()
        val provider = provider(ledger, directory, holdMillis = 0) { 0L }
        val request = File(directory, "batch.jsonl")
        request.writeText(
            batchRequestLine(
                assetId = "photo-1",
                jpegBytes = byteArrayOf(1),
                model = "qwen3-vl-flash",
                thinkingEnabled = false,
                thinkingTokenLimit = 1024,
                detailLevel = DetailLevel.DETAILED,
            ),
        )
        val fileId = provider.upload(request.absolutePath)
        val taskId = provider.createTask(fileId)
        assertEquals(taskId, provider.createTask(fileId))
        assertEquals(1, decodeFakeBatchLedger(ledger.read()).uploadCount)
        assertEquals(1, decodeFakeBatchLedger(ledger.read()).tasks.size)
    }

    @Test
    fun 解不开的图记失败下一张继续并且一次只封口一批() {
        val failed = mutableListOf<String>()
        val seen = mutableListOf<String>()
        val outcome = packImageBatch(
            source = PendingImageSource {
                val next = listOf("bad", "ok", "later").getOrNull(seen.size) ?: return@PendingImageSource null
                seen += next
                next
            },
            compressor = object : ImageCompressor {
                override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg {
                    if (assetId == "bad") throw ImageUnreadable("HEIC 解不开")
                    return object : CompressedJpeg {
                        override val bytes: ByteArray = byteArrayOf(1, 2, 3)
                        override val widthPx: Int = 8
                        override val heightPx: Int = 8
                        override fun close() = Unit
                    }
                }
            },
            files = memoryFiles(),
            ledger = object : BatchLedger {
                override fun seal(batch: SealedBatchDraft) = Unit
                override fun markLineTooLarge(assetId: String, reason: String) {
                    failed += "$assetId:$reason"
                }
            },
            newBatchId = { "only-one" },
            config = BatchPackConfig(
                model = "qwen3-vl-flash",
                thinkingEnabled = false,
                thinkingTokenLimit = 1024,
                jpegQuality = 80,
                detailLevel = DetailLevel.DETAILED,
                configFingerprint = "fp",
                limits = BatchPackLimits(maxFileBytes = 1_000_000, maxLines = 1, maxLineBytes = 100_000),
            ),
            stopAfterSealedBatches = 1,
        )
        assertEquals(listOf("bad:HEIC 解不开"), failed)
        assertEquals(listOf("ok"), outcome.sealed.single().assetIds)
        assertEquals(listOf("bad", "ok"), seen)
        assertTrue(outcome.unsealed == null)
    }

    private fun provider(
        ledger: FakeProviderLedger,
        directory: File,
        holdMillis: Long,
        now: () -> Long,
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

    private fun memoryFiles(): BatchFileSinkFactory = BatchFileSinkFactory { batchId ->
        object : BatchFileSink {
            override val path: String = batchId
            override var byteSize: Long = 0L
            override fun appendLine(line: String) {
                byteSize += line.encodeToByteArray().size + 1L
            }
            override fun close() = Unit
        }
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("photo-index-t11").toFile()
}
