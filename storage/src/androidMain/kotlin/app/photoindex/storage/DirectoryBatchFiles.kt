package app.photoindex.storage

import app.photoindex.core.BatchFileSink
import app.photoindex.core.BatchFileSinkFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 批文件写在这个目录里。同时只打开一个写入流，封口后的文件留在目录里，等上传成功再删。
 */
class DirectoryBatchFiles(
    private val directory: File,
) : BatchFileSinkFactory {
    private var openCount = 0
    internal var maxOpenWriters: Int = 0
        private set

    override fun open(batchId: String): BatchFileSink {
        require(batchId.none { it == '/' || it == '\\' || it == ':' }) { "批 ID 不能包含路径分隔符" }
        check(openCount == 0) { "磁盘上已经有一个正在写的批文件" }
        openCount += 1
        maxOpenWriters = maxOf(maxOpenWriters, openCount)
        return try {
            directory.mkdirs()
            val file = File(directory, "$batchId.jsonl")
            check(!file.exists()) { "批文件已存在：$batchId" }
            FileBatchSink(file) { openCount -= 1 }
        } catch (error: Throwable) {
            openCount -= 1
            throw error
        }
    }
}

private class FileBatchSink(
    private val file: File,
    private val onClose: () -> Unit,
) : BatchFileSink {
    private val stream = BufferedOutputStream(FileOutputStream(file))
    private var closed = false
    override val path: String = file.absolutePath
    override var byteSize: Long = 0L
        private set

    override fun appendLine(line: String) {
        check(!closed) { "批文件已经关闭" }
        val bytes = (line + "\n").encodeToByteArray()
        stream.write(bytes)
        byteSize += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.close()
        } finally {
            onClose()
        }
    }
}
