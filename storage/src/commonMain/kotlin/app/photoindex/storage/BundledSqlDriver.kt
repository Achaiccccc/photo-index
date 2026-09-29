package app.photoindex.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * 把 SQLDelight 接到 T0 固定的打包版 SQLite。
 * JVM 测试和 Android 走同一个驱动，不用系统自带的 SQLite。
 */
internal class BundledSqlDriver(
    private val connection: SQLiteConnection,
) : SqlDriver {
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private var transaction: Transaction? = null
    private var savepointId = 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        connection.prepare(sql).use { statement ->
            binders?.invoke(Binder(statement))
            while (statement.step()) {
                // 写语句没有结果行。把游标走完，避免语句停在半截。
            }
        }
        return QueryResult.Value(changes())
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val statement = connection.prepare(sql)
        try {
            binders?.invoke(Binder(statement))
            return mapper(Cursor(statement))
        } finally {
            statement.close()
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val next = Transaction(transaction)
        transaction = next
        return QueryResult.Value(next)
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key ->
            listeners.getOrPut(key) { mutableSetOf() }.add(listener)
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { key ->
            listeners[key]?.remove(listener)
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val pending = linkedSetOf<Query.Listener>()
        queryKeys.forEach { key ->
            listeners[key]?.let { pending.addAll(it) }
        }
        pending.forEach { it.queryResultsChanged() }
    }

    override fun close() {
        connection.close()
    }

    private fun changes(): Long {
        connection.prepare("SELECT changes()").use { statement ->
            check(statement.step()) { "changes() 没有返回结果" }
            return statement.getLong(0)
        }
    }

    private fun exec(sql: String) {
        connection.prepare(sql).use { statement ->
            while (statement.step()) {
            }
        }
    }

    private inner class Transaction(
        override val enclosingTransaction: Transacter.Transaction?,
    ) : Transacter.Transaction() {
        private val savepointName: String? = if (enclosingTransaction == null) {
            null
        } else {
            "photoindex_sp_${savepointId++}"
        }

        init {
            if (savepointName == null) {
                exec("BEGIN")
            } else {
                exec("SAVEPOINT $savepointName")
            }
        }

        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (savepointName == null) {
                exec(if (successful) "COMMIT" else "ROLLBACK")
            } else if (successful) {
                exec("RELEASE $savepointName")
            } else {
                exec("ROLLBACK TO $savepointName")
                exec("RELEASE $savepointName")
            }
            transaction = enclosingTransaction as Transaction?
            return QueryResult.Value(Unit)
        }
    }

    private class Binder(
        private val statement: SQLiteStatement,
    ) : SqlPreparedStatement {
        override fun bindBytes(index: Int, bytes: ByteArray?) {
            if (bytes == null) statement.bindNull(index + 1) else statement.bindBlob(index + 1, bytes)
        }

        override fun bindLong(index: Int, long: Long?) {
            if (long == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, long)
        }

        override fun bindDouble(index: Int, double: Double?) {
            if (double == null) statement.bindNull(index + 1) else statement.bindDouble(index + 1, double)
        }

        override fun bindString(index: Int, string: String?) {
            if (string == null) statement.bindNull(index + 1) else statement.bindText(index + 1, string)
        }

        override fun bindBoolean(index: Int, boolean: Boolean?) {
            when (boolean) {
                null -> statement.bindNull(index + 1)
                true -> statement.bindLong(index + 1, 1)
                false -> statement.bindLong(index + 1, 0)
            }
        }
    }

    private class Cursor(
        private val statement: SQLiteStatement,
    ) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(statement.step())

        override fun getString(index: Int): String? {
            return if (statement.isNull(index)) null else statement.getText(index)
        }

        override fun getLong(index: Int): Long? {
            return if (statement.isNull(index)) null else statement.getLong(index)
        }

        override fun getBytes(index: Int): ByteArray? {
            return if (statement.isNull(index)) null else statement.getBlob(index)
        }

        override fun getDouble(index: Int): Double? {
            return if (statement.isNull(index)) null else statement.getDouble(index)
        }

        override fun getBoolean(index: Int): Boolean? {
            return getLong(index)?.let { it != 0L }
        }
    }
}

internal fun openBundledSqlDriver(name: String = ":memory:"): BundledSqlDriver {
    val connection = BundledSQLiteDriver().open(name)
    connection.prepare("PRAGMA foreign_keys = ON").use { statement ->
        while (statement.step()) {
        }
    }
    return BundledSqlDriver(connection)
}
