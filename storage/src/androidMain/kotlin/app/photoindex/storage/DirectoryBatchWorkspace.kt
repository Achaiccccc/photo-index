package app.photoindex.storage

import app.photoindex.core.BatchWorkspace
import java.io.File

/** 批 JSONL 和下载下来的结果文件都放在这个目录。状态机用路径找回它们。 */
class DirectoryBatchWorkspace(
    private val directory: File,
) : BatchWorkspace {
    override fun listPaths(): List<String> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()?.filter { it.isFile }?.map { it.absolutePath }.orEmpty()
    }

    override fun normalize(path: String): String = File(path).absolutePath

    override fun deleteIfExists(path: String) {
        val file = File(path)
        if (!file.exists()) return
        check(file.delete()) { "删不掉批文件：$path" }
    }

    override fun exists(path: String): Boolean = File(path).isFile

    override fun readLines(path: String): List<String> = File(path).readLines()

    override fun writeLines(path: String, lines: List<String>) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }

    override fun resultPath(batchId: String): String {
        directory.mkdirs()
        return File(directory, "$batchId.result.jsonl").absolutePath
    }
}
