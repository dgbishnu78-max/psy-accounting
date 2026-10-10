package np.psy.core

import np.psy.core.cal.BsCalendar
import np.psy.core.db.Schema
import np.psy.core.db.SqlDb
import np.psy.core.db.list
import np.psy.core.report.Reports
import np.psy.core.service.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

interface Clock {
    fun now(): LocalDateTime
    fun today(): LocalDate = now().toLocalDate()
}

object SystemClock : Clock {
    override fun now(): LocalDateTime = LocalDateTime.now()
}

/**
 * Entry point to all business logic for one company database.
 * Every write goes through these services so that validation, accounting entries,
 * inventory costing and the audit trail are always applied together in one transaction.
 */
class Books(val db: SqlDb, val clock: Clock = SystemClock) {
    val settings = SettingsService(this)
    val audit = AuditService(this)
    val masters = MasterService(this)
    val years = FiscalYearService(this)
    val costing = CostingService(this)
    val journals = JournalService(this)
    val docs = DocumentService(this)
    val yearEnd = YearEndService(this)
    val reports = Reports(this)

    init {
        Schema.migrate(db, nowIso())
        loadCalendarCorrections()
        years.recoverInterruptedClosing()
    }

    fun nowIso(): String = clock.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    fun today(): LocalDate = clock.today()

    fun loadCalendarCorrections() {
        val map = db.list("SELECT bs_year, month_lengths FROM calendar_correction") { r ->
            r.int("bs_year") to r.string("month_lengths").split(",").map { it.trim().toInt() }.toIntArray()
        }.toMap()
        BsCalendar.applyCorrections(map)
    }

    /** Next ordering number shared by vouchers and journals (deterministic tie-break within a day). */
    internal fun nextSeq(): Long {
        val a = db.list("SELECT COALESCE(MAX(seq),0) AS m FROM voucher") { it.long("m") }.first()
        val b = db.list("SELECT COALESCE(MAX(seq),0) AS m FROM journal") { it.long("m") }.first()
        return maxOf(a, b) + 1
    }
}
