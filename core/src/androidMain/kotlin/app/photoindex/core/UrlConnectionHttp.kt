package app.photoindex.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

// 与 jvmMain 的同名文件保持一致。HttpURLConnection 在 JVM 17 和 Android API 26 上都有。

actual fun defaultHttpTransport(): HttpTransport = UrlConnectionTransport()

actual fun defaultBinaryFiles(): BinaryFiles = SystemBinaryFiles()

actual fun defaultSleeper(): (Long) -> Unit = { millis ->
    if (millis > 0) Thread.sleep(millis)
}

internal class UrlConnectionTransport : HttpTransport {
    override fun exchange(request: OutboundHttp): InboundHttp {
        val connection = URI.create(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            // 真机上传一整份 JSONL 时，服务商收下文件可能超过 30 秒。
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val payload = when {
                request.body != null -> request.body
                request.method == "POST" -> ByteArray(0)
                else -> null
            }
            if (payload != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return InboundHttp(status, body)
        } catch (error: IOException) {
            throw HttpTransportException(error.message ?: "网络失败", error)
        } finally {
            connection.disconnect()
        }
    }
}

private class SystemBinaryFiles : BinaryFiles {
    override fun read(path: String): ByteArray = File(path).readBytes()

    override fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}
