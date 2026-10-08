package app.photoindex.core

/**
 * T11 的假服务商。不访问网络。
 * 上传时记下每一行的 custom_id，创建任务后把任务号写进 [FakeProviderLedger]。
 * 进程重启后用同一份记录查询和下载，不会因为任务号还在而再上传一次。
 * 结果正文是固定的详细 JSON，字段可以解析。
 * [holdMillis] 内查询保持运行中，用来在拿到任务号之后留出划掉应用的时间。
 */
class FixedDetailBatchProvider(
    private val ledger: FakeProviderLedger,
    private val readLines: (String) -> List<String>,
    private val writeText: (String, String) -> Unit,
    private val holdMillis: Long = 0,
    private val now: () -> Long = { 0L },
) : BatchProvider {
    init {
        require(holdMillis >= 0L) { "等待时间不能为负" }
    }

    override fun upload(localPath: String): String {
        val ids = readLines(localPath)
            .filter { it.isNotBlank() }
            .map { line -> customIdOfBatchRequest(line) ?: error("批文件里有一行没有 custom_id") }
        val stored = load()
        val fileId = "file-${stored.nextNumber}"
        val files = stored.files.toMutableMap()
        files[fileId] = ids
        save(
            stored.copy(
                uploadCount = stored.uploadCount + 1,
                nextNumber = stored.nextNumber + 1,
                files = files,
            ),
        )
        return fileId
    }

    override fun createTask(remoteFileId: String): String {
        val stored = load()
        val existing = stored.tasks.values.firstOrNull { it.fileId == remoteFileId && !it.cancelled }
        if (existing != null) return existing.id
        check(remoteFileId in stored.files) { "假服务商没有文件 $remoteFileId" }
        val taskId = "task-${stored.nextNumber}"
        val tasks = stored.tasks.toMutableMap()
        tasks[taskId] = FakeTask(
            id = taskId,
            fileId = remoteFileId,
            createdAtMillis = now(),
            cancelled = false,
        )
        save(stored.copy(nextNumber = stored.nextNumber + 1, tasks = tasks))
        return taskId
    }

    override fun query(remoteBatchId: String): RemoteBatch {
        val task = load().tasks[remoteBatchId] ?: error("假服务商没有任务 $remoteBatchId")
        val ready = now() >= task.createdAtMillis + holdMillis
        val outputId = if (ready) outputFileId(task.id) else null
        val phase = when {
            task.cancelled -> RemoteBatchPhase.CANCELLED
            ready -> RemoteBatchPhase.COMPLETED
            else -> RemoteBatchPhase.RUNNING
        }
        return RemoteBatch(phase = phase, outputFileId = outputId, error = null)
    }

    override fun download(outputFileId: String, destinationPath: String) {
        val taskId = outputFileId.removePrefix("out-")
        val stored = load()
        val task = stored.tasks[taskId] ?: error("假服务商没有结果 $outputFileId")
        val ids = stored.files[task.fileId] ?: error("假服务商没有文件 ${task.fileId}")
        val lines = ids.map { id ->
            successfulBatchResultLine(
                customId = id,
                modelJson = FAKE_DETAIL_RECORD.toStableJson(),
                inputTokens = FAKE_INPUT_TOKENS,
                outputTokens = FAKE_OUTPUT_TOKENS,
            )
        }
        writeText(destinationPath, lines.joinToString("\n"))
    }

    override fun cancel(remoteBatchId: String) {
        val stored = load()
        val task = stored.tasks[remoteBatchId] ?: error("假服务商没有任务 $remoteBatchId")
        val tasks = stored.tasks.toMutableMap()
        tasks[remoteBatchId] = task.copy(cancelled = true)
        save(stored.copy(tasks = tasks))
    }

    override fun deleteRemoteFile(remoteFileId: String) = Unit

    private fun load(): FakeBatchLedger = decodeFakeBatchLedger(ledger.read())

    private fun save(value: FakeBatchLedger) {
        ledger.write(encodeFakeBatchLedger(value))
    }

    private fun outputFileId(taskId: String): String = "out-$taskId"
}

/** 假服务商写出的详细记录。平台、作者、时间留空，不编造。 */
val FAKE_DETAIL_RECORD = ModelRecord(
    summary = "一张用于索引测试的画面",
    objects = listOf("测试主体"),
    scene = listOf("室内"),
    ocrText = "假服务商固定摘录",
    tags = listOf("测试"),
)

const val FAKE_INPUT_TOKENS = 100L
const val FAKE_OUTPUT_TOKENS = 40L

interface FakeProviderLedger {
    fun read(): String
    fun write(text: String)
}

class MemoryFakeProviderLedger(
    initial: String = "",
) : FakeProviderLedger {
    private var text: String = initial

    override fun read(): String = text

    override fun write(text: String) {
        this.text = text
    }
}

data class FakeTask(
    val id: String,
    val fileId: String,
    val createdAtMillis: Long,
    val cancelled: Boolean,
)

data class FakeBatchLedger(
    val uploadCount: Int = 0,
    val nextNumber: Int = 1,
    val files: Map<String, List<String>> = emptyMap(),
    val tasks: Map<String, FakeTask> = emptyMap(),
)

fun encodeFakeBatchLedger(ledger: FakeBatchLedger): String = buildString {
    append("upload ")
    append(ledger.uploadCount)
    append('\n')
    append("next ")
    append(ledger.nextNumber)
    append('\n')
    ledger.files.forEach { (fileId, assetIds) ->
        append("file ")
        append(fileId)
        append('\n')
        assetIds.forEach { assetId ->
            append("asset ")
            append(assetId)
            append('\n')
        }
    }
    ledger.tasks.values.forEach { task ->
        append("task ")
        append(task.id)
        append(' ')
        append(task.fileId)
        append(' ')
        append(task.createdAtMillis)
        append(' ')
        append(if (task.cancelled) "1" else "0")
        append('\n')
    }
}

fun decodeFakeBatchLedger(text: String): FakeBatchLedger {
    if (text.isBlank()) return FakeBatchLedger()
    var uploadCount = 0
    var nextNumber = 1
    val files = linkedMapOf<String, List<String>>()
    val tasks = linkedMapOf<String, FakeTask>()
    var currentFile: String? = null
    val currentAssets = mutableListOf<String>()
    fun flushFile() {
        val fileId = currentFile ?: return
        files[fileId] = currentAssets.toList()
        currentAssets.clear()
        currentFile = null
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        if (line.isBlank()) return@forEach
        when {
            line.startsWith("upload ") -> uploadCount = line.removePrefix("upload ").toInt()
            line.startsWith("next ") -> nextNumber = line.removePrefix("next ").toInt()
            line.startsWith("file ") -> {
                flushFile()
                currentFile = line.removePrefix("file ")
            }
            line.startsWith("asset ") -> currentAssets += line.removePrefix("asset ")
            line.startsWith("task ") -> {
                flushFile()
                val parts = line.removePrefix("task ").split(' ')
                check(parts.size == 4) { "假服务商记录里的任务行不完整" }
                val task = FakeTask(
                    id = parts[0],
                    fileId = parts[1],
                    createdAtMillis = parts[2].toLong(),
                    cancelled = parts[3] == "1",
                )
                tasks[task.id] = task
            }
            else -> error("假服务商记录里有认不出的行")
        }
    }
    flushFile()
    return FakeBatchLedger(
        uploadCount = uploadCount,
        nextNumber = nextNumber,
        files = files,
        tasks = tasks,
    )
}

private fun customIdOfBatchRequest(line: String): String? {
    val root = readJsonValue(line) as? JsonValue.Obj ?: return null
    return (root.fields["custom_id"] as? JsonValue.Str)?.value
}
