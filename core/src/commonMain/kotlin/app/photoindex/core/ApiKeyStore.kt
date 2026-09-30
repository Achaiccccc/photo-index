package app.photoindex.core

/**
 * API Key 的平台存储。Android 用 Keystore 加密后写入应用私有文件。
 * 索引库、导出和日志都不经过这个接口。
 */
interface ApiKeyStore {
    fun save(apiKey: String)

    fun read(): String?

    fun clear()
}
