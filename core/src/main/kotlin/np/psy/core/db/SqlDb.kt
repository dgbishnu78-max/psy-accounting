package np.psy.core.db

/**
 * Minimal SQL access used by all business logic. The Android app implements it on top of
 * android.database.sqlite; tests implement it with SQLite JDBC. Both run the same SQL.
 */
interface SqlDb {
    fun execute(sql: String, vararg args: Any?)
    /** Executes an INSERT and returns the new row id. */
    fun insert(sql: String, vararg args: Any?): Long
    /** Executes UPDATE/DELETE and returns the number of changed rows. */
    fun update(sql: String, vararg args: Any?): Int
    fun <T> query(sql: String, args: Array<out Any?>, mapper: (Row) -> T): List<T>

    /** Runs [block] in a transaction. Nested calls join the outer transaction. */
    fun <T> transaction(block: () -> T): T
    val inTransaction: Boolean

    var userVersion: Int
}

interface Row {
    fun has(column: String): Boolean
    fun isNull(column: String): Boolean
    fun long(column: String): Long
    fun longOrNull(column: String): Long? = if (isNull(column)) null else long(column)
    fun int(column: String): Int = long(column).toInt()
    fun double(column: String): Double
    fun string(column: String): String
    fun stringOrNull(column: String): String? = if (isNull(column)) null else string(column)
    fun bool(column: String): Boolean = long(column) != 0L
    fun columns(): List<String>
    /** Raw value: Long, Double, String, ByteArray or null. */
    fun value(column: String): Any?
}

fun <T> SqlDb.list(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = query(sql, args, mapper)
fun <T> SqlDb.first(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = query(sql, args, mapper).firstOrNull()
fun SqlDb.long(sql: String, vararg args: Any?): Long = query(sql, args) { r -> if (r.isNull(r.columns()[0])) 0L else r.long(r.columns()[0]) }.firstOrNull() ?: 0L
fun SqlDb.longOrNull(sql: String, vararg args: Any?): Long? = query(sql, args) { r -> r.longOrNull(r.columns()[0]) }.firstOrNull()
fun SqlDb.string(sql: String, vararg args: Any?): String? = query(sql, args) { r -> r.stringOrNull(r.columns()[0]) }.firstOrNull()

/** A user-facing validation or business-rule error. Message is shown to the user as-is. */
class UserError(message: String) : RuntimeException(message)

fun ensure(condition: Boolean, message: () -> String) { if (!condition) throw UserError(message()) }
