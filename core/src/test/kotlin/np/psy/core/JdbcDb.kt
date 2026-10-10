package np.psy.core

import np.psy.core.db.Row
import np.psy.core.db.SqlDb
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** SqlDb on SQLite JDBC, used only by tests. */
class JdbcDb(val file: File?) : SqlDb {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:" + (file?.absolutePath ?: ":memory:")).also {
        it.autoCommit = true
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = ON") }
    }
    private var depth = 0

    private fun bind(sql: String, args: Array<out Any?>) = conn.prepareStatement(sql).also { ps ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is Int -> ps.setLong(i + 1, a.toLong())
                is Long -> ps.setLong(i + 1, a)
                is Double -> ps.setDouble(i + 1, a)
                is Boolean -> ps.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> ps.setBytes(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) conn.createStatement().use { it.execute(sql) } else bind(sql, args).use { it.execute() }
    }
    override fun insert(sql: String, vararg args: Any?): Long {
        bind(sql, args).use { it.executeUpdate() }
        return conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }
    override fun update(sql: String, vararg args: Any?): Int = bind(sql, args).use { it.executeUpdate() }

    override fun <T> query(sql: String, args: Array<out Any?>, mapper: (Row) -> T): List<T> = bind(sql, args).use { ps ->
        val out = ArrayList<T>()
        val hasRs = ps.execute()
        if (hasRs) ps.resultSet.use { rs -> val row = RsRow(rs); while (rs.next()) out.add(mapper(row)) }
        out
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) { depth++; try { return block() } finally { depth-- } }
        conn.autoCommit = false
        depth = 1
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback(); throw e
        } finally { depth = 0; conn.autoCommit = true }
    }
    override val inTransaction: Boolean get() = depth > 0
    override var userVersion: Int
        get() = conn.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        set(v) { conn.createStatement().use { it.execute("PRAGMA user_version = $v") } }

    fun close() = conn.close()

    private class RsRow(val rs: ResultSet) : Row {
        private val names: List<String> by lazy { (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) } }
        private fun idx(c: String): Int { val i = names.indexOf(c); require(i >= 0) { "No column $c in $names" }; return i + 1 }
        override fun has(column: String) = column in names
        override fun isNull(column: String): Boolean { rs.getObject(idx(column)); return rs.wasNull() || rs.getObject(idx(column)) == null }
        override fun long(column: String) = rs.getLong(idx(column))
        override fun double(column: String) = rs.getDouble(idx(column))
        override fun string(column: String): String = rs.getString(idx(column)) ?: ""
        override fun columns() = names
        override fun value(column: String): Any? = when (val o = rs.getObject(idx(column))) {
            null -> null
            is Int -> o.toLong()
            is Long -> o
            is Double -> o
            is Float -> o.toDouble()
            is ByteArray -> o
            else -> o.toString()
        }
    }
}
