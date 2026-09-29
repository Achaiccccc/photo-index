package app.photoindex.storage

import app.cash.sqldelight.db.SqlDriver

/** 一次打开的索引库。用完要关闭。 */
class OpenedPhotoIndexDatabase(
    private val driver: SqlDriver,
    val database: PhotoIndexDatabase,
) : AutoCloseable {
    override fun close() {
        driver.close()
    }
}

/** 用打包版 SQLite 打开索引库。不传文件名时使用内存库，供 JVM 测试使用。 */
fun openPhotoIndexDatabase(name: String = ":memory:"): OpenedPhotoIndexDatabase {
    val driver = openBundledSqlDriver(name)
    PhotoIndexDatabase.Schema.create(driver).value
    return OpenedPhotoIndexDatabase(
        driver = driver,
        database = PhotoIndexDatabase(driver),
    )
}
