package np.psy.accounting

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import np.psy.core.db.Row
import np.psy.core.db.SqlDb
import java.io.File

/** SqlDb implementation on Android's built-in SQLite. */
class AndroidSqlDb(val raw: SQLiteDatabase) : SqlDb {
    private val depth = object : ThreadLocal<Int>() { override fun initialValue() = 0 }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val k = i + 1
            when (a) {
                null -> st.bindNull(k)
                is Int -> st.bindLong(k, a.toLong())
                is Long -> st.bindLong(k, a)
                is Short -> st.bindLong(k, a.toLong())
                is Boolean -> st.bindLong(k, if (a) 1 else 0)
                is Double -> st.bindDouble(k, a)
                is Float -> st.bindDouble(k, a.toDouble())
                is ByteArray -> st.bindBlob(k, a)
                else -> st.bindString(k, a.toString())
            }
        }
    }

    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) raw.execSQL(sql) else { val st = raw.compileStatement(sql); try { bind(st, args); st.execute() } finally { st.close() } }
    }

    override fun insert(sql: String, vararg args: Any?): Long {
        val st = raw.compileStatement(sql)
        try { bind(st, args); return st.executeInsert() } finally { st.close() }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val st = raw.compileStatement(sql)
        try { bind(st, args); return st.executeUpdateDelete() } finally { st.close() }
    }

    /**
     * rawQuery only binds strings, so numbers are written into the SQL as literals (safe: they are
     * numbers, never user text) and only strings are bound. This keeps comparisons and LIMIT exact.
     */
    override fun <T> query(sql: String, args: Array<out Any?>, mapper: (Row) -> T): List<T> {
        val sb = StringBuilder(); val strArgs = ArrayList<String>()
        var k = 0; var inQuote = false
        for (c in sql) {
            if (c == '\'') inQuote = !inQuote
            if (c == '?' && !inQuote) {
                when (val a = args[k++]) {
                    null -> sb.append("NULL")
                    is Int, is Long, is Short -> sb.append(a.toString())
                    is Boolean -> sb.append(if (a) "1" else "0")
                    is Double, is Float -> sb.append(a.toString())
                    else -> { sb.append('?'); strArgs.add(a.toString()) }
                }
            } else sb.append(c)
        }
        val cur = raw.rawQuery(sb.toString(), strArgs.toTypedArray())
        try {
            val out = ArrayList<T>()
            val row = CRow(cur)
            while (cur.moveToNext()) out.add(mapper(row))
            return out
        } finally { cur.close() }
    }

    override fun <T> transaction(block: () -> T): T {
        val d = depth.get()!!
        if (d > 0) { depth.set(d + 1); try { return block() } finally { depth.set(d) } }
        raw.beginTransaction()
        depth.set(1)
        try {
            val r = block()
            raw.setTransactionSuccessful()
            return r
        } finally {
            depth.set(0)
            raw.endTransaction()
        }
    }

    override val inTransaction: Boolean get() = depth.get()!! > 0

    override var userVersion: Int
        get() = raw.version
        set(v) { raw.execSQL("PRAGMA user_version = $v") }

    private class CRow(val c: Cursor) : Row {
        private fun idx(col: String): Int { val i = c.getColumnIndex(col); require(i >= 0) { "No column $col" }; return i }
        override fun has(column: String) = c.getColumnIndex(column) >= 0
        override fun isNull(column: String) = c.isNull(idx(column))
        override fun long(column: String) = c.getLong(idx(column))
        override fun double(column: String) = c.getDouble(idx(column))
        override fun string(column: String): String = c.getString(idx(column)) ?: ""
        override fun columns(): List<String> = c.columnNames.toList()
        override fun value(column: String): Any? {
            val i = idx(column)
            return when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                else -> c.getString(i)
            }
        }
    }

    companion object {
        fun open(file: File): AndroidSqlDb {
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.disableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
            return AndroidSqlDb(db)
        }
    }
}
