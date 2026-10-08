package app.photoindex.storage

import app.cash.sqldelight.db.QueryResult

/** 一次打开的索引库。用完要关闭。 */
class OpenedPhotoIndexDatabase internal constructor(
    private val driver: BundledSqlDriver,
    val database: PhotoIndexDatabase,
) : AutoCloseable {
    init {
        SearchIndex.attach(database, driver)
    }

    override fun close() {
        SearchIndex.detach(database)
        driver.close()
    }
}

/**
 * 短查询要读 FTS5 的词表。SQLDelight 认不出 fts5vocab 的列，所以这条查询走驱动。
 * 打开库时登记，关闭时摘掉，避免搜索还去用已经关掉的连接。
 */
internal object SearchIndex {
    private val drivers = mutableMapOf<PhotoIndexDatabase, BundledSqlDriver>()

    fun attach(database: PhotoIndexDatabase, driver: BundledSqlDriver) {
        synchronized(drivers) { drivers[database] = driver }
    }

    fun detach(database: PhotoIndexDatabase) {
        synchronized(drivers) { drivers.remove(database) }
    }

    fun createSql(database: PhotoIndexDatabase, table: String): String {
        val sql = driver(database).selectTexts(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf(table),
        )
        return sql.singleOrNull().orEmpty()
    }

    fun trigramTerms(database: PhotoIndexDatabase, fragment: String): List<String> {
        return driver(database).selectTexts(
            "SELECT term FROM searchFtsVocab WHERE col = 'searchText' AND instr(term, ?) > 0",
            listOf(fragment),
        )
    }

    private fun driver(database: PhotoIndexDatabase): BundledSqlDriver {
        return synchronized(drivers) { drivers[database] } ?: error("索引库已经关闭")
    }
}

/** 当前建表语句的版本。表结构变了再加，已经打开过的库不要重跑建表。 */
internal const val PHOTO_INDEX_SCHEMA_VERSION = 1L

/** 用打包版 SQLite 打开索引库。不传文件名时使用内存库，供 JVM 测试使用。 */
fun openPhotoIndexDatabase(name: String = ":memory:"): OpenedPhotoIndexDatabase {
    val driver = openBundledSqlDriver(name)
    if (driver.schemaUserVersion() == 0L) {
        PhotoIndexDatabase.Schema.create(driver).value
        // SQLDelight 会把触发器排到虚拟表前面，所以改在建表之后安装。
        // 识别结果一写入，全文索引就跟着变，下一次查询能看见新词。
        installSearchTriggers(driver)
        driver.execute(null, "PRAGMA user_version = $PHOTO_INDEX_SCHEMA_VERSION", 0, null)
    } else {
        installSearchTriggers(driver)
    }
    // 已经打开过的库不会重跑建表。确认标记是后加的表，这里补上，默认未确认。
    ensureQuoteConfirmation(driver)
    return OpenedPhotoIndexDatabase(
        driver = driver,
        database = PhotoIndexDatabase(driver),
    )
}

private fun BundledSqlDriver.schemaUserVersion(): Long {
    return executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            val present = cursor.next().value
            QueryResult.Value(if (present) cursor.getLong(0) ?: 0L else 0L)
        },
        0,
        null,
    ).value
}

private fun ensureQuoteConfirmation(driver: BundledSqlDriver) {
    driver.execute(
        null,
        """
        CREATE TABLE IF NOT EXISTS quote_confirmation (
          id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
          confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1))
        )
        """.trimIndent(),
        0,
        null,
    )
    driver.execute(
        null,
        """
        INSERT INTO quote_confirmation (id, confirmed)
        SELECT 1, 0
        WHERE NOT EXISTS (SELECT 1 FROM quote_confirmation WHERE id = 1)
        """.trimIndent(),
        0,
        null,
    )
}

private fun installSearchTriggers(driver: BundledSqlDriver) {
    driver.execute(
        null,
        """
        CREATE TRIGGER IF NOT EXISTS recognition_search_insert AFTER INSERT ON recognition BEGIN
          INSERT INTO searchFts(assetId, searchText) VALUES (new.assetId, new.searchText);
        END
        """.trimIndent(),
        0,
        null,
    )
    driver.execute(
        null,
        """
        CREATE TRIGGER IF NOT EXISTS recognition_search_update AFTER UPDATE OF searchText ON recognition BEGIN
          DELETE FROM searchFts WHERE assetId = old.assetId;
          INSERT INTO searchFts(assetId, searchText) VALUES (new.assetId, new.searchText);
        END
        """.trimIndent(),
        0,
        null,
    )
    driver.execute(
        null,
        """
        CREATE TRIGGER IF NOT EXISTS recognition_search_delete AFTER DELETE ON recognition BEGIN
          DELETE FROM searchFts WHERE assetId = old.assetId;
        END
        """.trimIndent(),
        0,
        null,
    )
}
