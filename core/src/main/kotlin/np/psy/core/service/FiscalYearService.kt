package np.psy.core.service

import np.psy.core.Books
import np.psy.core.cal.BsDate
import np.psy.core.db.*
import np.psy.core.model.FiscalYear
import np.psy.core.model.FyStatus
import java.time.LocalDate

/**
 * Nepal financial years: Shrawan 1 to the last day of Asar (configurable start month).
 * Year rows are created on demand. AD boundaries are always derived from the BS calendar.
 */
class FiscalYearService(private val b: Books) {
    private val db get() = b.db

    private fun fy(r: Row) = FiscalYear(r.long("id"), r.string("name"), LocalDate.parse(r.string("start_date")),
        LocalDate.parse(r.string("end_date")), FyStatus.valueOf(r.string("status")))

    val startMonth: Int get() = b.settings.int("fy_start_month", 4)

    fun all(): List<FiscalYear> = db.list("SELECT * FROM fiscal_year ORDER BY start_date", mapper = ::fy)
    fun get(id: Long): FiscalYear = db.first("SELECT * FROM fiscal_year WHERE id = ?", id, mapper = ::fy) ?: throw UserError("Financial year not found")

    /** BS start/end of the financial year that contains [date]. */
    fun boundsFor(date: LocalDate): Pair<BsDate, BsDate> {
        val bs = BsDate.fromAd(date)
        val sm = startMonth
        val startYear = if (bs.month >= sm) bs.year else bs.year - 1
        val start = BsDate(startYear, sm, 1)
        val nextStart = if (sm == 1) BsDate(startYear + 1, 1, 1) else BsDate(startYear + 1, sm, 1)
        val end = nextStart.plusDays(-1)
        return start to end
    }

    fun nameFor(start: BsDate, end: BsDate): String =
        if (start.year == end.year) "${start.year}" else "${start.year}/${(end.year % 100).toString().padStart(2, '0')}"

    /** The financial year containing [date], created if it does not exist yet. */
    fun forDate(date: LocalDate): FiscalYear {
        db.first("SELECT * FROM fiscal_year WHERE start_date <= ? AND end_date >= ?", date.toString(), date.toString(), mapper = ::fy)?.let { return it }
        return db.transaction {
            val (s, e) = boundsFor(date)
            val name = nameFor(s, e)
            val start = s.toAd(); val end = e.toAd()
            val overlap = db.long("SELECT COUNT(*) FROM fiscal_year WHERE NOT (end_date < ? OR start_date > ?)", start.toString(), end.toString())
            ensure(overlap == 0L) { "Financial year settings overlap an existing year. Check the financial-year start month." }
            val id = db.insert("INSERT INTO fiscal_year(name, start_date, end_date, status) VALUES (?,?,?, 'OPEN')", name, start.toString(), end.toString())
            b.audit.log("CREATE", "fiscal_year", id, "Financial year $name created", "$s BS to $e BS ($start to $end AD)")
            get(id)
        }
    }

    fun current(): FiscalYear = forDate(b.today())

    /** Throws if [date] cannot receive postings (closed/closing year, before books start, outside calendar). */
    fun checkOpen(date: LocalDate, what: String = "This entry") {
        try { BsDate.fromAd(date) } catch (e: IllegalArgumentException) { throw UserError("$what has a date outside the supported calendar range") }
        val start = booksStart()
        ensure(!date.isBefore(start)) { "$what is dated before the books start date (${BsDate.fromAd(start)} BS). Opening balances cover earlier periods." }
        val fy = forDate(date)
        ensure(fy.status == FyStatus.OPEN) {
            if (fy.status == FyStatus.CLOSED) "$what falls in financial year ${fy.name}, which is closed. Reopen the year first (Financial Year screen) or record a correction in an open period."
            else "Financial year ${fy.name} is being closed. Try again in a moment."
        }
    }

    /** First day of the books (opening balances date). Defaults to the start of the year in which the database was created. */
    fun booksStart(): LocalDate {
        val s = b.settings.get("books_start_date")
        if (s.isNotEmpty()) return LocalDate.parse(s)
        val created = b.settings.get("created_at").takeIf { it.length >= 10 }?.substring(0, 10)?.let { LocalDate.parse(it) } ?: b.today()
        val start = boundsFor(created).first.toAd()
        b.settings.set("books_start_date", start.toString(), audit = false)
        return start
    }

    fun setBooksStart(date: LocalDate) = db.transaction {
        BsDate.fromAd(date)
        val hasActivity = db.long("SELECT COUNT(*) FROM voucher") + db.long("SELECT COUNT(*) FROM journal WHERE type NOT IN ('OPENING_STOCK')") > 0
        ensure(!hasActivity) { "The books start date can only be changed before any transactions or opening balances are entered" }
        ensure(all().none { it.status != FyStatus.OPEN }) { "The books start date cannot be changed after a year has been closed" }
        b.settings.set("books_start_date", date.toString())
        b.journals.regenerateOpeningStock()
    }

    fun setStartMonth(month: Int) = db.transaction {
        ensure(month in 1..12) { "Month must be 1 to 12" }
        ensure(all().none { it.status != FyStatus.OPEN }) { "The financial-year start month cannot be changed after a year has been closed" }
        b.settings.set("fy_start_month", month.toString())
        // Rebuild open year rows from scratch (no posting is linked to them except closing journals, which require closed years).
        db.update("UPDATE journal SET fiscal_year_id = NULL WHERE fiscal_year_id IS NOT NULL")
        db.update("DELETE FROM fiscal_year")
        b.audit.log("UPDATE", "fiscal_year", null, "Financial year start month set to $month")
    }

    /** Recomputes AD boundaries of OPEN years after a calendar correction. */
    fun refreshBoundaries() = db.transaction {
        for (y in all()) {
            val (s, e) = boundsFor(y.start.plusDays(10))
            val ns = s.toAd(); val ne = e.toAd()
            if (ns != y.start || ne != y.end) {
                ensure(y.status == FyStatus.OPEN) { "This calendar change would move the dates of closed year ${y.name}. Reopen it first." }
                db.update("UPDATE fiscal_year SET start_date = ?, end_date = ? WHERE id = ?", ns.toString(), ne.toString(), y.id)
            }
        }
    }

    internal fun setStatus(id: Long, status: FyStatus) {
        db.update("UPDATE fiscal_year SET status = ? WHERE id = ?", status.name, id)
    }

    /**
     * Called at start-up: a year left in CLOSING status means the app stopped while closing.
     * Closing writes its entries in a single transaction, so either they all exist or none do.
     */
    fun recoverInterruptedClosing() {
        for (y in all().filter { it.status == FyStatus.CLOSING }) db.transaction {
            val active = db.long("SELECT COUNT(*) FROM fy_closing WHERE fiscal_year_id = ? AND status = 'ACTIVE'", y.id) > 0
            val newStatus = if (active) FyStatus.CLOSED else FyStatus.OPEN
            setStatus(y.id, newStatus)
            b.audit.log("RECOVER", "fiscal_year", y.id, "Interrupted closing of ${y.name} recovered; year is ${newStatus.label}")
        }
    }

    /** Next document number for a type in the financial year of [date], e.g. SI-2083/84-0001. */
    internal fun nextNumber(type: String, date: LocalDate): String {
        val fy = forDate(date)
        val fmt = b.settings.get("number_format.$type", "$type-{FY}-{N4}")
        val key = "counter.$type.${fy.name}"
        var n = b.settings.int(key, 0)
        while (true) {
            n++
            val num = format(fmt, fy.name, n)
            val table = if (type == "JOURNAL") "journal" else "voucher"
            val clash = if (table == "journal") db.long("SELECT COUNT(*) FROM journal WHERE number = ?", num)
                        else db.long("SELECT COUNT(*) FROM voucher WHERE type = ? AND number = ?", type, num)
            if (clash == 0L) { b.settings.set(key, n.toString(), audit = false); return num }
        }
    }

    fun format(fmt: String, fyName: String, n: Int): String =
        fmt.replace("{FY}", fyName).replace("{N4}", n.toString().padStart(4, '0')).replace("{N}", n.toString())

    fun previewNumber(type: String, date: LocalDate): String {
        val fy = forDate(date)
        val fmt = b.settings.get("number_format.$type", "$type-{FY}-{N4}")
        return format(fmt, fy.name, b.settings.int("counter.$type.${fy.name}", 0) + 1)
    }
}
