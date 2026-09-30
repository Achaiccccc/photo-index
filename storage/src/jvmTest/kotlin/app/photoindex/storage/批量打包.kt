package app.photoindex.storage

import app.photoindex.core.BatchPackConfig
import app.photoindex.core.BatchPackLimits
import app.photoindex.core.CompressedJpeg
import app.photoindex.core.DetailLevel
import app.photoindex.core.ImageCompressor
import app.photoindex.core.PendingImageSource
import app.photoindex.core.lineTooLargeReason
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.deleteRecursively
import kotlin.io.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class 批量打包 {
    @Test
    fun 第3张触发封口后前一批已落库() {
        val directory = tempDir()
        val opened = openPhotoIndexDatabase()
        try {
            val database = opened.database
            database.insertAlbum()
            listOf("p1", "p2", "p3", "p4").forEach { database.insertPending(it) }
            val files = DirectoryBatchFiles(directory)
            val compressor = TrackingCompressor { _, _ -> byteArrayOf(1, 2, 3) }
            val source = object : PendingImageSource {
                private val pending = PendingAssetIds(database)
                override fun nextId(): String? {
                    assertTrue(compressor.openCount == 0, "要下一张时上一张压缩结果还没释放")
                    val id = pending.nextId() ?: return null
                    if (id == "p4") {
                        val batches = database.batchQueries.selectAllBatches().executeAsList()
                        assertEquals(1, batches.size, "第 3 张写满 3 行后，前一批应该已经落库")
                        assertEquals(listOf("p1", "p2", "p3"), database.assetIdsInBatch(batches[0].id))
                        assertEquals("pending", database.assetQueries.selectAssetById("p4").executeAsOne().status)
                    }
                    return id
                }
            }

            val outcome = packPendingImages(
                database = database,
                source = source,
                compressor = compressor,
                files = files,
                config = packConfig(maxLines = 3),
                newBatchId = batchIds(),
            )

            val packed = database.batchQueries.selectAllBatches().executeAsList()
            assertEquals(1, packed.size)
            val batch = packed[0]
            assertEquals("packed", batch.state)
            assertEquals("fp-pack", batch.configFingerprint)
            assertEquals(3L, batch.lineCount)
            assertEquals(File(batch.localPath!!).length(), batch.byteSize)
            assertNull(batch.remoteFileId)
            assertNull(batch.remoteBatchId)
            assertEquals(0L, batch.committedCount)
            assertEquals(listOf("p1", "p2", "p3"), database.assetIdsInBatch(batch.id))
            listOf("p1", "p2", "p3").forEach { id ->
                val asset = database.assetQueries.selectAssetById(id).executeAsOne()
                assertEquals("in_batch", asset.status)
                assertEquals(batch.id, asset.batchId)
                assertEquals("fp-pack", asset.configFingerprint)
                assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), asset.contentHash)
                assertNull(asset.lastError)
            }
            val unsealed = outcome.unsealed!!
            assertEquals(listOf("p4"), unsealed.assets.map { it.id })
            assertNull(database.batchQueries.selectBatchById(unsealed.id).executeAsOneOrNull())
            val pending = database.assetQueries.selectAssetById("p4").executeAsOne()
            assertEquals("pending", pending.status)
            assertNull(pending.batchId)
            assertNull(pending.contentHash)
            assertEquals(1, File(unsealed.localPath).readLines().size)
            assertEquals(1, files.maxOpenWriters)
            assertEquals(listOf("p1", "p2", "p3"), File(batch.localPath).readLines().map { customIdOf(it) })

            sealOutstandingBatch(database, unsealed)
            val afterSeal = database.batchQueries.selectAllBatches().executeAsList()
            assertEquals(2, afterSeal.size)
            assertEquals("in_batch", database.assetQueries.selectAssetById("p4").executeAsOne().status)
        } finally {
            opened.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun 一行只有一个custom_id() {
        val directory = tempDir()
        val opened = openPhotoIndexDatabase()
        try {
            val database = opened.database
            database.insertAlbum()
            listOf("p1", "p2").forEach { database.insertPending(it) }
            val outcome = packPendingImages(
                database = database,
                source = PendingAssetIds(database),
                compressor = TrackingCompressor { _, _ -> byteArrayOf(4, 5) },
                files = DirectoryBatchFiles(directory),
                config = packConfig(maxLines = 2),
                newBatchId = batchIds(),
            )

            assertNull(outcome.unsealed)
            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            val lines = File(batch.localPath!!).readLines()
            assertEquals(2, lines.size)
            lines.forEachIndexed { index, line ->
                val assetId = "p${index + 1}"
                assertEquals(1, countOf(line, "\"custom_id\""))
                assertEquals(assetId, customIdOf(line))
                assertEquals(1, countOf(line, "\"type\":\"image_url\""))
                assertEquals(1, countOf(line, "data:image/jpeg;base64,"))
                assertTrue(line.contains("\"model\":\"qwen3-vl-flash\""))
                assertTrue(line.contains("\"enable_thinking\":false"))
                assertTrue(!line.contains('\n') && !line.contains('\r'))
            }
            assertEquals(
                lines[0].substringAfter("\"model\"").substringBefore("\"messages\""),
                lines[1].substringAfter("\"model\"").substringBefore("\"messages\""),
            )
        } finally {
            opened.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun 超大图失败不影响同批其他图() {
        val directory = tempDir()
        val opened = openPhotoIndexDatabase()
        try {
            val database = opened.database
            database.insertAlbum()
            listOf("a-ok", "b-shrink", "c-huge", "d-ok").forEach { database.insertPending(it) }
            val compressor = TrackingCompressor { assetId, quality ->
                val oversized = assetId == "c-huge" || (assetId == "b-shrink" && quality == 80)
                if (oversized) ByteArray(80_000) { 7 } else byteArrayOf(1, 2, 3)
            }
            val outcome = packPendingImages(
                database = database,
                source = PendingAssetIds(database),
                compressor = compressor,
                files = DirectoryBatchFiles(directory),
                config = packConfig(maxLines = 3, maxLineBytes = 64L * 1024),
                newBatchId = batchIds(),
            )

            assertNull(outcome.unsealed)
            assertEquals(
                listOf(
                    "a-ok" to 80,
                    "b-shrink" to 80,
                    "b-shrink" to 60,
                    "c-huge" to 80,
                    "c-huge" to 60,
                    "d-ok" to 80,
                ),
                compressor.calls,
            )
            assertEquals(1, compressor.maxOpen)
            val failed = database.assetQueries.selectAssetById("c-huge").executeAsOne()
            assertEquals("failed", failed.status)
            assertNull(failed.batchId)
            assertNull(failed.contentHash)
            assertEquals(1L, failed.attemptCount)
            assertEquals(lineTooLargeReason(64L * 1024), failed.lastError)

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(listOf("a-ok", "b-shrink", "d-ok"), database.assetIdsInBatch(batch.id))
            val lines = File(batch.localPath!!).readLines()
            assertEquals(listOf("a-ok", "b-shrink", "d-ok"), lines.map { customIdOf(it) })
            assertTrue(lines.none { it.contains("c-huge") })
            assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), database.assetQueries.selectAssetById("b-shrink").executeAsOne().contentHash)
        } finally {
            opened.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun 打包器任何时刻只向压缩器要一张() {
        val directory = tempDir()
        val opened = openPhotoIndexDatabase()
        try {
            val database = opened.database
            database.insertAlbum()
            listOf("p1", "p2", "p3", "p4").forEach { database.insertPending(it) }
            val compressor = TrackingCompressor { _, _ -> byteArrayOf(1) }
            var nextWhileCompressing = 0
            val source = object : PendingImageSource {
                private val pending = PendingAssetIds(database)
                override fun nextId(): String? {
                    if (compressor.openCount > 0) nextWhileCompressing += 1
                    return pending.nextId()
                }
            }
            val files = DirectoryBatchFiles(directory)
            packPendingImages(
                database = database,
                source = source,
                compressor = compressor,
                files = files,
                config = packConfig(maxLines = 2),
                newBatchId = batchIds(),
            )

            assertEquals(0, nextWhileCompressing)
            assertEquals(1, compressor.maxOpen)
            assertEquals(1, files.maxOpenWriters)
            assertEquals(
                listOf("p1" to 80, "p2" to 80, "p3" to 80, "p4" to 80),
                compressor.calls,
            )
            assertEquals(2, database.batchQueries.selectAllBatches().executeAsList().size)
        } finally {
            opened.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun 体积装不下第3张时封口且文件不超过阈值() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val sample = app.photoindex.core.batchRequestLine(
            assetId = "p1",
            jpegBytes = jpeg,
            model = "qwen3-vl-flash",
            thinkingEnabled = false,
            thinkingTokenLimit = 1024,
            detailLevel = DetailLevel.DETAILED,
        )
        val lineBytes = sample.encodeToByteArray().size + 1
        val maxFileBytes = lineBytes * 2 + lineBytes / 2
        val directory = tempDir()
        val opened = openPhotoIndexDatabase()
        try {
            val database = opened.database
            database.insertAlbum()
            listOf("p1", "p2", "p3").forEach { database.insertPending(it) }
            val outcome = packPendingImages(
                database = database,
                source = PendingAssetIds(database),
                compressor = TrackingCompressor { _, _ -> jpeg },
                files = DirectoryBatchFiles(directory),
                config = packConfig(
                    maxLines = 100,
                    maxFileBytes = maxFileBytes.toLong(),
                    maxLineBytes = (lineBytes - 1).toLong(),
                ),
                newBatchId = batchIds(),
            )

            val batch = database.batchQueries.selectAllBatches().executeAsOne()
            assertEquals(2L, batch.lineCount)
            assertTrue(batch.byteSize <= maxFileBytes)
            assertEquals(File(batch.localPath!!).length(), batch.byteSize)
            assertEquals(listOf("p1", "p2"), database.assetIdsInBatch(batch.id))
            assertEquals("pending", database.assetQueries.selectAssetById("p3").executeAsOne().status)
            val unsealed = outcome.unsealed!!
            assertEquals(listOf("p3"), unsealed.assets.map { it.id })
            assertTrue(File(unsealed.localPath).length() <= maxFileBytes)
        } finally {
            opened.close()
            directory.deleteRecursively()
        }
    }

    private fun packConfig(
        maxLines: Int,
        maxFileBytes: Long = 400L * 1024 * 1024,
        maxLineBytes: Long = 5L * 1024 * 1024,
    ): BatchPackConfig = BatchPackConfig(
        model = "qwen3-vl-flash",
        thinkingEnabled = false,
        thinkingTokenLimit = 1024,
        jpegQuality = 80,
        detailLevel = DetailLevel.DETAILED,
        configFingerprint = "fp-pack",
        limits = BatchPackLimits(
            maxFileBytes = maxFileBytes,
            maxLines = maxLines,
            maxLineBytes = maxLineBytes,
        ),
    )

    private fun batchIds(): () -> String {
        var number = 0
        return { "batch-${++number}" }
    }

    private fun tempDir(): File = Files.createTempDirectory("photo-index-t5").toFile()

    private fun PhotoIndexDatabase.insertAlbum() {
        sourceQueries.insertSource(
            id = "album-1",
            kind = "album",
            systemKey = "bucket-camera",
            displayName = "相机",
            enabled = 1L,
        )
    }

    private fun PhotoIndexDatabase.insertPending(id: String) {
        assetQueries.insertAsset(
            id = id,
            sourceId = "album-1",
            systemId = "media-$id",
            displayName = "$id.jpg",
            relativePath = "Camera/$id.jpg",
            size = 100L,
            dateModified = 10L,
            dateTaken = null,
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

    private fun PhotoIndexDatabase.assetIdsInBatch(batchId: String): List<String> =
        assetQueries.selectAssetsByBatchId(batchId).executeAsList().map { it.id }

    private fun customIdOf(line: String): String {
        val marker = "\"custom_id\":\""
        val start = line.indexOf(marker) + marker.length
        return line.substring(start, line.indexOf('"', start))
    }

    private fun countOf(text: String, token: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val found = text.indexOf(token, from)
            if (found < 0) return count
            count += 1
            from = found + token.length
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digits = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(digits[value ushr 4])
                append(digits[value and 0x0F])
            }
        }
    }
}

private class TrackingCompressor(
    private val bytesFor: (assetId: String, quality: Int) -> ByteArray,
) : ImageCompressor {
    var openCount = 0
    var maxOpen = 0
    val calls = mutableListOf<Pair<String, Int>>()

    override fun compress(assetId: String, jpegQuality: Int): CompressedJpeg {
        check(openCount == 0) { "上一张压缩结果还没释放" }
        calls += assetId to jpegQuality
        openCount += 1
        maxOpen = maxOf(maxOpen, openCount)
        return object : CompressedJpeg {
            override val bytes: ByteArray = bytesFor(assetId, jpegQuality)
            override val widthPx: Int = 320
            override val heightPx: Int = 240
            override fun close() {
                openCount -= 1
            }
        }
    }
}
