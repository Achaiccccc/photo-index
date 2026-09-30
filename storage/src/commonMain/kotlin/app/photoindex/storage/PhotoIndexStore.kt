package app.photoindex.storage

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

/** 用打包版 SQLite 打开索引库。不传文件名时使用内存库，供 JVM 测试使用。 */
fun openPhotoIndexDatabase(name: String = ":memory:"): OpenedPhotoIndexDatabase {
    val driver = openBundledSqlDriver(name)
    PhotoIndexDatabase.Schema.create(driver).value
    // SQLDelight 会把触发器排到虚拟表前面，所以改在建表之后安装。
    // 识别结果一写入，全文索引就跟着变，下一次查询能看见新词。
    installSearchTriggers(driver)
    return OpenedPhotoIndexDatabase(
        driver = driver,
        database = PhotoIndexDatabase(driver),
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
