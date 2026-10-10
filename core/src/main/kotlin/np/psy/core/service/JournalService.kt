package np.psy.core.service

import np.psy.core.Books
import np.psy.core.cal.BsDate
import np.psy.core.db.*
import np.psy.core.model.*
import np.psy.core.util.Money
import java.time.LocalDate

/**
 * Double-entry journal. Every journal must balance (total debits = total credits) and is
 * checked before it is written. Posted journals are never deleted: corrections are made with
 * reversal journals that mirror the original and keep both in the audit trail.
 */
class JournalService(private val b: Books) {
    private val db get() = b.db

    private fun journal(r: Row) = Journal(r.long("id"), r.longOrNull("voucher_id"), r.string("type"), r.string("number"),
        LocalDate.parse(r.string("date")), r.long("seq"), r.string("narration"), r.string("status"), r.longOrNull("reversal_of_id"), r.string("created_at"))
    private fun line(r: Row) = JournalLine(r.long("id"), r.long("journal_id"), r.long("account_id"), r.long("debit"), r.long("credit"), r.string("memo"))

    fun get(id: Long): Journal = db.first("SELECT * FROM journal WHERE id = ?", id, mapper = ::journal) ?: throw UserError("Journal not found")
    fun lines(journalId: Long): List<JournalLine> = db.list("SELECT * FROM journal_line WHERE journal_id = ? ORDER BY id", journalId, mapper = ::line)
    fun forVoucher(voucherId: Long): List<Journal> = db.list("SELECT * FROM journal WHERE voucher_id = ? ORDER BY seq", voucherId, mapper = ::journal)

    fun list(from: LocalDate?, to: LocalDate?, types: Collection<String>? = null, search: String = ""): List<Journal> {
        val w = StringBuilder("1=1"); val a = ArrayList<Any?>()
        if (from != null) { w.append(" AND date >= ?"); a.add(from.toString()) }
        if (to != null) { w.append(" AND date <= ?"); a.add(to.toString()) }
        if (types != null && types.isNotEmpty()) { w.append(" AND type IN (${types.joinToString(",") { "?" }})"); a.addAll(types) }
        if (search.isNotBlank()) { w.append(" AND (number LIKE ? OR narration LIKE ?)"); a.add("%$search%"); a.add("%$search%") }
        return db.query("SELECT * FROM journal WHERE $w ORDER BY date, seq", a.toTypedArray(), ::journal)
    }

    /** Validates and writes a journal. Returns its id. */
    internal fun write(type: String, number: String, date: LocalDate, narration: String, lines: List<JournalLineInput>,
                       voucherId: Long? = null, reversalOfId: Long? = null, fiscalYearId: Long? = null, allowClosedYear: Boolean = false): Long {
        val clean = lines.filter { it.debit != 0L || it.credit != 0L }
        validate(clean)
        if (!allowClosedYear) {
            val fy = b.years.forDate(date)
            if (fy.status != FyStatus.OPEN) throw UserError("Financial year ${fy.name} is ${fy.status.label.lowercase()}; entries cannot be posted into it")
        }
        val id = db.insert("""INSERT INTO journal(voucher_id, type, number, date, seq, narration, status, reversal_of_id, fiscal_year_id, created_at)
            VALUES (?,?,?,?,?,?, 'POSTED', ?, ?, ?)""", voucherId, type, number, date.toString(), b.nextSeq(), narration, reversalOfId, fiscalYearId, b.nowIso())
        insertLines(id, clean)
        return id
    }

    private fun insertLines(journalId: Long, lines: List<JournalLineInput>) {
        for (l in lines) db.insert("INSERT INTO journal_line(journal_id, account_id, debit, credit, memo) VALUES (?,?,?,?,?)",
            journalId, l.accountId, l.debit, l.credit, l.memo)
    }

    fun validate(lines: List<JournalLineInput>) {
        ensure(lines.isNotEmpty()) { "A journal entry needs at least one line" }
        for (l in lines) {
            ensure(l.debit >= 0 && l.credit >= 0) { "Debit and credit amounts cannot be negative" }
            ensure(!(l.debit > 0 && l.credit > 0)) { "A line cannot have both a debit and a credit" }
            b.masters.account(l.accountId)
        }
        val dr = lines.sumOf { it.debit }; val cr = lines.sumOf { it.credit }
        ensure(dr == cr) { "Debits (${Money.format(dr)}) and credits (${Money.format(cr)}) must be equal. Difference: ${Money.format(dr - cr)}" }
        ensure(dr > 0) { "The journal entry has no amounts" }
    }

    /** Posts a reversal (mirror) of [journalId] dated the same day, and marks the original as reversed. */
    internal fun reverse(journalId: Long, narration: String, type: String = "REVERSAL", allowClosedYear: Boolean = false): Long {
        val j = get(journalId)
        ensure(j.status == "POSTED") { "Journal ${j.number} is already reversed" }
        val mirrored = lines(journalId).map { JournalLineInput(it.accountId, it.credit, it.debit, it.memo) }
        val rid = write(type, "R-" + j.number, j.date, narration, mirrored, j.voucherId, j.id, null, allowClosedYear)
        db.update("UPDATE journal SET status = 'REVERSED' WHERE id = ?", journalId)
        return rid
    }

    // ---------------- Automatic entries for documents ----------------

    /** Accounting lines for a posted document. */
    fun buildForVoucher(v: Voucher, lines: List<VoucherLine>): List<JournalLineInput> {
        val m = b.masters
        val out = ArrayList<JournalLineInput>()
        fun dr(key: String, amt: Long, memo: String = "") { if (amt > 0) out.add(JournalLineInput(m.accountId(key), amt, 0, memo)) else if (amt < 0) out.add(JournalLineInput(m.accountId(key), 0, -amt, memo)) }
        fun cr(key: String, amt: Long, memo: String = "") = dr(key, -amt, memo)
        fun drId(id: Long, amt: Long, memo: String = "") { if (amt > 0) out.add(JournalLineInput(id, amt, 0, memo)) else if (amt < 0) out.add(JournalLineInput(id, 0, -amt, memo)) }
        fun crId(id: Long, amt: Long, memo: String = "") = drId(id, -amt, memo)
        val vatKey = when (v.vatClass) { VatClass.PENDING -> "VAT_INPUT_PENDING"; else -> "VAT_INPUT" }
        val cost = lines.sumOf { it.cost }
        val party = v.partyName
        when (v.type) {
            VoucherType.SALE -> {
                drId(v.settleAccountId ?: m.accountId("RECEIVABLES"), v.total, party)
                cr("SALES", lines.sumOf { it.gross })
                dr("SALES_DISCOUNT", lines.sumOf { it.discount })
                cr("VAT_OUTPUT", v.vat)
                dr("COGS", cost); cr("INVENTORY", cost)
            }
            VoucherType.SALES_RETURN -> {
                dr("SALES_RETURNS", v.taxable)
                dr("VAT_OUTPUT", v.vat)
                crId(v.settleAccountId ?: m.accountId("RECEIVABLES"), v.total, party)
                dr("INVENTORY", cost); cr("COGS", cost)
            }
            VoucherType.PURCHASE -> {
                val recoverable = if (v.vatClass == VatClass.INELIGIBLE) 0L else v.vat
                dr("INVENTORY", cost)
                dr(vatKey, recoverable)
                crId(v.settleAccountId ?: m.accountId("PAYABLES"), v.total, party)
            }
            VoucherType.PURCHASE_RETURN -> {
                val recoverable = if (v.vatClass == VatClass.INELIGIBLE) 0L else v.vat
                val returnValue = v.total - recoverable
                drId(v.settleAccountId ?: m.accountId("PAYABLES"), v.total, party)
                cr(vatKey, recoverable)
                cr("INVENTORY", cost)
                cr("INV_ADJ", returnValue - cost, "Purchase return price difference")
            }
            VoucherType.EXPENSE -> {
                val cat = m.expenseCategory(v.expenseCategoryId ?: throw IllegalStateException("Expense without category"))
                val recoverable = if (v.vatClass == VatClass.ELIGIBLE || v.vatClass == VatClass.PENDING) v.vat else 0L
                drId(cat.accountId, v.total - recoverable, v.description)
                dr(vatKey, recoverable)
                crId(v.settleAccountId ?: m.accountId("PAYABLES"), v.total, party)
            }
            VoucherType.STOCK_ADJUSTMENT -> {
                for (l in lines) {
                    if (l.baseQty > 0) { dr("INVENTORY", l.cost); cr("INV_ADJ", l.cost) }
                    else { dr("INV_ADJ", l.cost); cr("INVENTORY", l.cost) }
                }
            }
        }
        return out
    }

    /** Creates or refreshes the automatic journal of a posted document (e.g. after cost changes). */
    internal fun regenerateForVoucher(voucherId: Long) {
        val v = b.docs.voucher(voucherId)
        if (v.status != VoucherStatus.POSTED) return
        val newLines = buildForVoucher(v, b.docs.lines(voucherId)).filter { it.debit != 0L || it.credit != 0L }
        val existing = forVoucher(voucherId).firstOrNull { it.status == "POSTED" && it.type == v.type.name }
        if (existing == null) {
            write(v.type.name, v.number, v.date, narrationFor(v), newLines, voucherId = voucherId)
            return
        }
        val old = lines(existing.id).map { JournalLineInput(it.accountId, it.debit, it.credit, it.memo) }
        if (old == newLines) return
        validate(newLines)
        val fy = b.years.forDate(existing.date)
        if (fy.status != FyStatus.OPEN) throw IllegalStateException("Internal check failed: entry ${existing.number} is in closed year ${fy.name}")
        db.update("DELETE FROM journal_line WHERE journal_id = ?", existing.id)
        insertLines(existing.id, newLines)
    }

    fun narrationFor(v: Voucher): String = when (v.type) {
        VoucherType.SALE -> "Sales invoice ${v.number} - ${v.partyName}"
        VoucherType.PURCHASE -> "Purchase ${v.number} - ${v.partyName}" + (if (v.refNo.isNotEmpty()) " (bill ${v.refNo})" else "")
        VoucherType.SALES_RETURN -> "Sales return ${v.number} - ${v.partyName}"
        VoucherType.PURCHASE_RETURN -> "Purchase return ${v.number} - ${v.partyName}"
        VoucherType.EXPENSE -> "Expense ${v.number} - ${v.description}" + (if (v.partyName.isNotEmpty()) " (${v.partyName})" else "")
        VoucherType.STOCK_ADJUSTMENT -> "Stock adjustment ${v.number}" + (if (v.description.isNotEmpty()) " - ${v.description}" else "")
    }

    // ---------------- Manual journals ----------------

    fun postManual(date: LocalDate, narration: String, lines: List<JournalLineInput>): Long = db.transaction {
        b.years.checkOpen(date, "This journal entry")
        ensure(narration.isNotBlank()) { "Enter a narration explaining the entry" }
        ensure(lines.size >= 2) { "A journal entry needs at least two lines" }
        val inv = b.masters.accountId("INVENTORY")
        ensure(lines.none { it.accountId == inv }) { "The Inventory account is maintained automatically from stock. Use a Stock Adjustment instead." }
        val number = b.years.nextNumber("JOURNAL", date)
        val id = write("JOURNAL", number, date, narration.trim(), lines)
        b.audit.log("POST", "journal", id, "Journal $number posted", describe(lines))
        id
    }

    fun cancelManual(journalId: Long, reason: String): Long = db.transaction {
        val j = get(journalId)
        ensure(j.type == "JOURNAL") { "Only manual journal entries can be cancelled here. Cancel the source document instead." }
        ensure(reason.isNotBlank()) { "Enter a reason for cancelling" }
        b.years.checkOpen(j.date, "This journal entry")
        val rid = reverse(journalId, "Reversal of ${j.number}: ${reason.trim()}")
        b.audit.log("REVERSE", "journal", journalId, "Journal ${j.number} reversed", "", reason.trim())
        rid
    }

    private fun describe(lines: List<JournalLineInput>) = lines.joinToString("; ") {
        val a = b.masters.account(it.accountId)
        "${a.code} ${if (it.debit > 0) "Dr " + Money.format(it.debit) else "Cr " + Money.format(it.credit)}"
    }

    // ---------------- Opening balances ----------------

    fun openingJournal(): Journal? = db.first("SELECT * FROM journal WHERE type = 'OPENING' AND status = 'POSTED'", mapper = ::journal)
    fun openingStockJournal(): Journal? = db.first("SELECT * FROM journal WHERE type = 'OPENING_STOCK' AND status = 'POSTED'", mapper = ::journal)

    /**
     * Sets account opening balances as at the books start date. Inventory is excluded because it
     * comes from product opening stock. Any difference is placed in Opening Balance Equity.
     * Replacing the opening balances reverses the previous set (kept in the audit trail).
     */
    fun setOpeningBalances(balances: List<JournalLineInput>, note: String = ""): Long? = db.transaction {
        val start = b.years.booksStart()
        val fy = b.years.forDate(start)
        ensure(fy.status == FyStatus.OPEN) { "Opening balances cannot be changed because ${fy.name} is closed" }
        val inv = b.masters.accountId("INVENTORY"); val obe = b.masters.accountId("OPENING_EQUITY")
        ensure(balances.none { it.accountId == inv }) { "Inventory opening value comes from each product's opening stock and must not be entered again here" }
        ensure(balances.none { it.accountId == obe }) { "Opening Balance Equity is calculated automatically" }
        val seen = HashSet<Long>()
        for (l in balances) {
            ensure(seen.add(l.accountId)) { "Each account can appear only once in opening balances" }
            val a = b.masters.account(l.accountId)
            ensure(!a.type.isPnl) { "${a.name} is an income/expense account. Opening balances are only for balance-sheet accounts." }
        }
        val clean = balances.filter { it.debit != 0L || it.credit != 0L }
        val existing = openingJournal()
        if (existing != null) reverse(existing.id, "Opening balances replaced", "OPENING_REVERSAL")
        if (clean.isEmpty()) {
            b.audit.log("UPDATE", "opening", null, "Opening balances cleared", "", note)
            return@transaction null
        }
        val dr = clean.sumOf { it.debit }; val cr = clean.sumOf { it.credit }
        val lines = ArrayList(clean)
        if (dr != cr) lines.add(JournalLineInput(obe, if (cr > dr) cr - dr else 0, if (dr > cr) dr - cr else 0, "Balancing difference"))
        val id = write("OPENING", "OPENING", start, "Opening balances as at ${BsDate.fromAd(start)} BS", lines)
        b.audit.log("UPDATE", "opening", id, "Opening balances set", describe(lines), note)
        id
    }

    /** Opening stock journal (Dr Inventory / Cr Opening Balance Equity) from product opening values. */
    internal fun regenerateOpeningStock() {
        val total = db.long("SELECT COALESCE(SUM(opening_value),0) FROM product")
        val start = b.years.booksStart()
        val existing = openingStockJournal()
        val lines = if (total > 0) listOf(
            JournalLineInput(b.masters.accountId("INVENTORY"), total, 0, "Opening stock"),
            JournalLineInput(b.masters.accountId("OPENING_EQUITY"), 0, total, "Opening stock")) else emptyList()
        if (existing != null) {
            val old = lines(existing.id).map { JournalLineInput(it.accountId, it.debit, it.credit, it.memo) }
            if (old == lines && existing.date == start) return
            reverse(existing.id, "Opening stock value changed", "OPENING_STOCK_REVERSAL")
        }
        if (lines.isNotEmpty()) write("OPENING_STOCK", "OPENING-STOCK", start, "Opening stock as at ${BsDate.fromAd(start)} BS", lines)
    }
}
