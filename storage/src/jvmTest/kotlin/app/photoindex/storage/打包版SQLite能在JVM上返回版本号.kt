package app.photoindex.storage

import kotlin.test.Test
import kotlin.test.assertTrue

class 打包版SQLite能在JVM上返回版本号 {
    @Test
    fun 执行sqlite_version能读到打包库的版本号() {
        val version = bundledSqliteVersion()
        println("SQLite version: $version")
        assertTrue(version.isNotEmpty(), "sqlite_version() 应返回非空版本")
        assertTrue(version.first().isDigit(), "版本号应以数字开头，实际为 $version")
    }
}
