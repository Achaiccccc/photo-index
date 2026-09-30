package app.photoindex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.photoindex.platform.KeystoreApiKeyStore
import app.photoindex.storage.openPhotoIndexDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class 数据库文件不含密钥 {
    @Test
    fun 加密存储里的密钥不会出现在索引库文件中() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentinel = "t8-sentinel-key-6f0c9a"
        val marker = "相册甲"
        val store = KeystoreApiKeyStore(context)
        store.clear()
        store.save(sentinel)
        assertEquals(sentinel, store.read())

        val keyFile = File(context.noBackupFilesDir, "api-key.bin")
        assertTrue(keyFile.isFile)
        assertFalse(keyFile.readBytes().containsSequence(sentinel.toByteArray(Charsets.UTF_8)))

        val databaseFile = File(context.cacheDir, "t8-index.db")
        listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
        ).forEach { it.delete() }
        openPhotoIndexDatabase(databaseFile.absolutePath).use { opened ->
            opened.database.sourceQueries.insertSource(
                id = "src-t8",
                kind = "album",
                systemKey = "bucket-t8",
                displayName = marker,
                enabled = 1L,
            )
        }

        val stored = databaseBytes(databaseFile)
        assertTrue(stored.containsSequence(marker.toByteArray(Charsets.UTF_8)))
        assertFalse(stored.containsSequence(sentinel.toByteArray(Charsets.UTF_8)))
        store.clear()
        assertNull(store.read())
    }
}

private fun databaseBytes(databaseFile: File): ByteArray {
    val output = ByteArrayOutputStream()
    listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
    ).filter { it.isFile }.forEach { output.write(it.readBytes()) }
    return output.toByteArray()
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matched = true
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}
