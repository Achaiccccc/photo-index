package app.photoindex

import android.content.Context
import app.photoindex.storage.OpenedPhotoIndexDatabase
import app.photoindex.storage.PhotoIndexDatabase
import app.photoindex.storage.openPhotoIndexDatabase
import java.io.File
import java.util.concurrent.Executors

fun photoIndexDatabaseFile(context: Context): File =
    File(context.applicationContext.filesDir, "photo-index.db")

/**
 * 索引库只在这一条后台线程上打开和使用。
 * 主线程不跑扫描，也不做整库查询。
 */
class AppIndex(context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val opened = worker.submit<OpenedPhotoIndexDatabase> {
        val file = photoIndexDatabaseFile(context)
        openPhotoIndexDatabase(file.absolutePath)
    }

    fun access(block: (PhotoIndexDatabase) -> Unit) {
        worker.execute {
            block(opened.get().database)
        }
    }

    override fun close() {
        worker.submit {
            opened.get().close()
        }.get()
        worker.shutdown()
    }
}
