package app.photoindex.platform

import android.content.Context
import java.io.File

/**
 * 千问真调用留下的操作记录。
 * 只记动作和远端 ID，不记 API Key，也不写入索引库。
 * 进程被杀掉之后，这份记录还在，用来核对没有第二份上传、以及入库后删过远端文件。
 */
class QwenCallTrace(private val file: File) {
    fun noteUpload(remoteFileId: String) = append("upload $remoteFileId")

    fun noteTask(remoteBatchId: String) = append("task $remoteBatchId")

    fun noteDelete(remoteFileId: String) = append("delete $remoteFileId")

    fun uploadCount(): Int = lines().count { it.startsWith("upload ") }

    fun deletedFileIds(): List<String> = lines().mapNotNull { line ->
        if (line.startsWith("delete ")) line.removePrefix("delete ") else null
    }

    private fun lines(): List<String> = locked {
        if (!file.isFile) emptyList() else file.readLines().filter { it.isNotBlank() }
    }

    private fun append(line: String) {
        locked {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }
    }

    private fun <T> locked(block: () -> T): T {
        val key = file.absolutePath.intern()
        return synchronized(key) { block() }
    }
}

fun qwenCallTraceFile(context: Context): File =
    File(context.applicationContext.filesDir, "qwen-call-trace.txt")
