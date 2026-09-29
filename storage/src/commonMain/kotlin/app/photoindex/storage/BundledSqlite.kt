package app.photoindex.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** 打开内存库并读取打包版 SQLite 的版本号。 */
fun bundledSqliteVersion(): String {
    val connection = BundledSQLiteDriver().open(":memory:")
    try {
        connection.prepare("SELECT sqlite_version()").use { statement ->
            check(statement.step()) { "sqlite_version() 没有返回结果" }
            return statement.getText(0)
        }
    } finally {
        connection.close()
    }
}
