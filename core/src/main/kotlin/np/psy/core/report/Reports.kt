package np.psy.core.report

import np.psy.core.Books
import np.psy.core.cal.BsCalendar
import np.psy.core.cal.BsDate
import np.psy.core.db.*
import np.psy.core.model.*
import np.psy.core.util.Money
import java.time.LocalDate

/** All reports. They read only posted accounting/stock data, so every report agrees with the ledger. */
class Reports(private val b: Books) {
    private val db get() = b.db

    // ---------------- helpers ----------------
    fun bs(d: LocalDate) = BsDate.fromAd(d).toString()
    fun period(from: LocalDate?, to: LocalDate?): String {
        val ad = b.settings.printAdDates
        fun one(d: LocalDate) = "${bs(d)} BS" + if (ad) " ($d AD)" else ""
        return when {
            from == null && to == null -> "All dates"
            from == null -> "As at ${one(to!!)}"
            to == null -> "From ${one(from)}"
            else -> "${one(from)} to ${one(to)}"
        }
    }
    private fun dateCols(): List<Col> = if (b.settings.printAdDates) listOf(Col("Date (BS)", weight = 1.1f), Col("Date (AD)", weight = 1.1f)) else listOf(Col("Date (BS)", weight = 1.1f))
    private fun dateCells(d: LocalDate): List<Any?> = if (b.settings.printAdDates) listOf(bs(d), d.toString()) else listOf(bs(d))
    private fun blanksForDates(): List<Any?> = if (b.settings.printAdDates) listOf("", "") else listOf("")

    /** P&L-type journals that must not count as period activity in a profit and loss statement. */
    private val closingTypes = "('CLOSING','CLOSING_REVERSAL')"

    /** Debit-minus-credit balance of an account up to [asOf] (inclusive), optionally from [from]. */
    fun accountBalance(accountId: Long, asOf: LocalDate?, from: LocalDate? = null, excludeClosing: Boolean = false): Long {
        val w = StringBuilder("l.account_id = ?"); val a = ArrayList<Any?>(listOf(accountId))
        if (asOf != null) { w.append(" AND j.date <= ?"); a.add(asOf.toString()) }
        if (from != null) { w.append(" AND j.date >= ?"); a.add(from.toString()) }
        if (excludeClosing) w.append(" AND j.type NOT IN $closingTypes")
        return db.query("SELECT COALESCE(SUM(l.debit - l.credit),0) AS b FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE $w", a.toTypedArray()) { it.long("b") }.first()
    }

    /** accountId -> (debit total, credit total) in range. */
    fun accountTotals(from: LocalDate?, to: LocalDate?, excludeClosing: Boolean = false): Map<Long, Pair<Long, Long>> {
        val w = StringBuilder("1=1"); val a = ArrayList<Any?>()
        if (from != null) { w.append(" AND j.date >= ?"); a.add(from.toString()) }
        if (to != null) { w.append(" AND j.date <= ?"); a.add(to.toString()) }
        if (excludeClosing) w.append(" AND j.type NOT IN $closingTypes")
        return db.query("SELECT l.account_id, SUM(l.debit) AS d, SUM(l.credit) AS c FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE $w GROUP BY l.account_id",
            a.toTypedArray()) { it.long("account_id") to (it.long("d") to it.long("c")) }.toMap()
    }

    // ---------------- Trial balance ----------------
    data class TbLine(val account: Account, val debit: Long, val credit: Long)

    fun trialBalanceLines(asOf: LocalDate?, excludeClosingOf: Long? = null): List<TbLine> {
        val excl = excludeClosingOf?.let { " AND NOT (j.type IN $closingTypes AND j.date = (SELECT end_date FROM fiscal_year WHERE id = $it))" } ?: ""
        val w = if (asOf != null) "j.date <= ?" else "1=1"
        val args: Array<Any?> = if (asOf != null) arrayOf(asOf.toString()) else arrayOf()
        val bal = db.query("SELECT l.account_id, SUM(l.debit - l.credit) AS b FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE $w$excl GROUP BY l.account_id", args) {
            it.long("account_id") to it.long("b") }.toMap()
        return b.masters.accounts().mapNotNull { a ->
            val v = bal[a.id] ?: 0L
            if (v == 0L) null else TbLine(a, if (v > 0) v else 0, if (v < 0) -v else 0)
        }
    }

    fun trialBalance(asOf: LocalDate, preClosingFyId: Long? = null): ReportTable {
        val lines = trialBalanceLines(asOf, preClosingFyId)
        val rows = ArrayList<ReportRow>()
        for (t in AccountType.values()) {
            val ls = lines.filter { it.account.type == t }
            if (ls.isEmpty()) continue
            rows.add(ReportRow(listOf("", t.label, null, null), RowStyle.SECTION))
            ls.forEach { rows.add(ReportRow(listOf(it.account.code, it.account.name, Amt(it.debit, true), Amt(it.credit, true)), link = "account:${it.account.id}")) }
        }
        val dr = lines.sumOf { it.debit }; val cr = lines.sumOf { it.credit }
        rows.add(ReportRow(listOf("", "Total", Amt(dr), Amt(cr)), RowStyle.TOTAL))
        val warn = if (dr != cr) listOf("Trial balance does not balance: difference ${Money.format(dr - cr)}. Please contact support with a backup.") else emptyList()
        return ReportTable(if (preClosingFyId != null) "Trial Balance (before closing entries)" else "Trial Balance", period(null, asOf),
            listOf(Col("Code", weight = 0.6f), Col("Account", weight = 3f), Col("Debit", Align.RIGHT, 1.3f), Col("Credit", Align.RIGHT, 1.3f)), rows, warnings = warn)
    }

    // ---------------- Profit and loss ----------------
    data class Pnl(
        val sales: Long, val discounts: Long, val returns: Long, val netSales: Long, val cogs: Long, val invAdj: Long, val otherCogs: Long,
        val totalCogs: Long, val grossProfit: Long, val otherIncome: Map<Account, Long>, val opex: Map<Account, Long>, val otherExp: Map<Account, Long>,
        val netProfit: Long,
    )

    fun pnl(from: LocalDate?, to: LocalDate?): Pnl {
        val t = accountTotals(from, to, excludeClosing = true)
        val accs = b.masters.accounts()
        fun cr(a: Account) = t[a.id]?.let { it.second - it.first } ?: 0L
        fun dr(a: Account) = t[a.id]?.let { it.first - it.second } ?: 0L
        val byKey = accs.filter { it.systemKey != null }.associateBy { it.systemKey!! }
        val sales = cr(byKey.getValue("SALES")); val disc = dr(byKey.getValue("SALES_DISCOUNT")); val ret = dr(byKey.getValue("SALES_RETURNS"))
        val cogs = dr(byKey.getValue("COGS")); val adj = dr(byKey.getValue("INV_ADJ"))
        val otherCogs = accs.filter { it.type == AccountType.COGS && it.systemKey == null }.sumOf { dr(it) }
        val otherIncome = accs.filter { it.type == AccountType.REVENUE && it.subtype != "SALES" }.associateWith { cr(it) }.filterValues { it != 0L }
        val otherSalesAccts = accs.filter { it.type == AccountType.REVENUE && it.subtype == "SALES" && it.systemKey == null }.sumOf { cr(it) }
        val opex = accs.filter { it.type == AccountType.EXPENSE && it.subtype != "OTHER" }.associateWith { dr(it) }.filterValues { it != 0L }
        val otherExp = accs.filter { it.type == AccountType.EXPENSE && it.subtype == "OTHER" }.associateWith { dr(it) }.filterValues { it != 0L }
        val netSales = sales + otherSalesAccts - disc - ret
        val totalCogs = cogs + adj + otherCogs
        val gp = netSales - totalCogs
        val np = gp + otherIncome.values.sum() - opex.values.sum() - otherExp.values.sum()
        return Pnl(sales + otherSalesAccts, disc, ret, netSales, cogs, adj, otherCogs, totalCogs, gp, otherIncome, opex, otherExp, np)
    }

    fun profitAndLoss(from: LocalDate, to: LocalDate, title: String = "Profit and Loss Statement"): ReportTable {
        val p = pnl(from, to)
        val k = { key: String -> "account:${b.masters.accountId(key)}" }
        val rows = ArrayList<ReportRow>()
        rows.add(ReportRow(listOf("Revenue", null), RowStyle.SECTION))
        rows.add(ReportRow(listOf("Sales (excluding VAT)", Amt(p.sales)), link = k("SALES")))
        rows.add(ReportRow(listOf("Less: Sales discounts", Amt(-p.discounts)), link = k("SALES_DISCOUNT")))
        rows.add(ReportRow(listOf("Less: Sales returns", Amt(-p.returns)), link = k("SALES_RETURNS")))
        rows.add(ReportRow(listOf("Net sales", Amt(p.netSales)), RowStyle.SUBTOTAL))
        rows.add(ReportRow(listOf("Cost of goods sold", null), RowStyle.SECTION))
        rows.add(ReportRow(listOf("Cost of goods sold (weighted average)", Amt(p.cogs)), link = k("COGS")))
        if (p.invAdj != 0L) rows.add(ReportRow(listOf("Inventory adjustments", Amt(p.invAdj)), link = k("INV_ADJ")))
        if (p.otherCogs != 0L) rows.add(ReportRow(listOf("Other cost of sales", Amt(p.otherCogs))))
        rows.add(ReportRow(listOf("Total cost of goods sold", Amt(p.totalCogs)), RowStyle.SUBTOTAL))
        rows.add(ReportRow(listOf("Gross profit", Amt(p.grossProfit)), RowStyle.TOTAL))
        if (p.otherIncome.isNotEmpty()) {
            rows.add(ReportRow(listOf("Other income", null), RowStyle.SECTION))
            p.otherIncome.forEach { (a, v) -> rows.add(ReportRow(listOf(a.name, Amt(v)), link = "account:${a.id}")) }
            rows.add(ReportRow(listOf("Total other income", Amt(p.otherIncome.values.sum())), RowStyle.SUBTOTAL))
        }
        rows.add(ReportRow(listOf("Operating expenses", null), RowStyle.SECTION))
        p.opex.forEach { (a, v) -> rows.add(ReportRow(listOf(a.name, Amt(v)), link = "account:${a.id}")) }
        rows.add(ReportRow(listOf("Total operating expenses", Amt(p.opex.values.sum())), RowStyle.SUBTOTAL))
        if (p.otherExp.isNotEmpty()) {
            rows.add(ReportRow(listOf("Other expenses", null), RowStyle.SECTION))
            p.otherExp.forEach { (a, v) -> rows.add(ReportRow(listOf(a.name, Amt(v)), link = "account:${a.id}")) }
            rows.add(ReportRow(listOf("Total other expenses", Amt(p.otherExp.values.sum())), RowStyle.SUBTOTAL))
        }
        rows.add(ReportRow(listOf(if (p.netProfit >= 0) "Net profit" else "Net loss", Amt(p.netProfit)), RowStyle.TOTAL))
        val gpPct = Money.percent(p.grossProfit, p.netSales); val npPct = Money.percent(p.netProfit, p.netSales)
        return ReportTable(title, period(from, to), listOf(Col("Particulars", weight = 3f), Col("Amount (NPR)", Align.RIGHT, 1.3f)), rows,
            notes = listOf("Gross margin: $gpPct. Net margin: $npPct.", "VAT collected on sales is not revenue and recoverable input VAT is not an expense; both are held in VAT accounts.",
                "Year-end closing entries are excluded so each period shows its own activity."))
    }

    // ---------------- Balance sheet ----------------
    data class BsResult(val assets: List<Pair<Account, Long>>, val liabilities: List<Pair<Account, Long>>, val equity: List<Pair<Account, Long>>,
                        val unclosedProfit: Long, val totalAssets: Long, val totalLiabilities: Long, val totalEquity: Long) {
        val balanced get() = totalAssets == totalLiabilities + totalEquity
    }

    fun balanceSheetData(asOf: LocalDate): BsResult {
        val bal = db.query("SELECT l.account_id, SUM(l.debit - l.credit) AS b FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE j.date <= ? GROUP BY l.account_id",
            arrayOf(asOf.toString())) { it.long("account_id") to it.long("b") }.toMap()
        val accs = b.masters.accounts()
        val assets = accs.filter { it.type == AccountType.ASSET }.map { it to (bal[it.id] ?: 0L) }.filter { it.second != 0L || it.first.systemKey != null }
        val liabs = accs.filter { it.type == AccountType.LIABILITY }.map { it to -(bal[it.id] ?: 0L) }.filter { it.second != 0L || it.first.systemKey != null }
        val eq = accs.filter { it.type == AccountType.EQUITY }.map { it to -(bal[it.id] ?: 0L) }.filter { it.second != 0L || it.first.systemKey != null }
        val profit = -accs.filter { it.type.isPnl }.sumOf { bal[it.id] ?: 0L }
        val ta = assets.sumOf { it.second }; val tl = liabs.sumOf { it.second }; val te = eq.sumOf { it.second } + profit
        return BsResult(assets, liabs, eq, profit, ta, tl, te)
    }

    fun balanceSheet(asOf: LocalDate): ReportTable {
        val d = balanceSheetData(asOf)
        val rows = ArrayList<ReportRow>()
        fun section(title: String, items: List<Pair<Account, Long>>) {
            rows.add(ReportRow(listOf(title, null), RowStyle.SECTION))
            items.forEach { (a, v) -> rows.add(ReportRow(listOf("${a.code} ${a.name}", Amt(v)), link = "account:${a.id}")) }
        }
        section("Assets", d.assets.filter { it.first.subtype != "NON_CURRENT" })
        val nc = d.assets.filter { it.first.subtype == "NON_CURRENT" }
        if (nc.isNotEmpty()) section("Non-current assets", nc)
        rows.add(ReportRow(listOf("Total assets", Amt(d.totalAssets)), RowStyle.TOTAL))
        section("Liabilities", d.liabilities)
        rows.add(ReportRow(listOf("Total liabilities", Amt(d.totalLiabilities)), RowStyle.SUBTOTAL))
        section("Equity", d.equity)
        rows.add(ReportRow(listOf("Profit/(loss) not yet closed to retained earnings", Amt(d.unclosedProfit))))
        rows.add(ReportRow(listOf("Total equity", Amt(d.totalEquity)), RowStyle.SUBTOTAL))
        rows.add(ReportRow(listOf("Total liabilities and equity", Amt(d.totalLiabilities + d.totalEquity)), RowStyle.TOTAL))
        val warnings = ArrayList<String>()
        if (!d.balanced) warnings.add("Balance Sheet does not balance: Assets ${Money.format(d.totalAssets)} vs Liabilities + Equity ${Money.format(d.totalLiabilities + d.totalEquity)}.")
        val obe = d.equity.firstOrNull { it.first.systemKey == "OPENING_EQUITY" }?.second ?: 0L
        val notes = ArrayList<String>()
        notes.add(if (d.balanced) "Check: Assets = Liabilities + Equity (balanced)." else "Check failed: see warning.")
        if (obe != 0L) notes.add("Opening Balance Equity holds ${Money.format(obe)} from opening stock/opening balances. Your accountant may transfer it to capital or retained earnings with a journal entry.")
        val vin = d.assets.filter { it.first.systemKey?.startsWith("VAT_INPUT") == true }.sumOf { it.second }
        val vout = d.liabilities.firstOrNull { it.first.systemKey == "VAT_OUTPUT" }?.second ?: 0L
        notes.add("Net VAT position per books: ${if (vout - vin >= 0) "payable" else "credit"} ${Money.format(Math.abs(vout - vin))} (output ${Money.format(vout)} less input ${Money.format(vin)}). VAT paid to the IRD only appears here if you record it with a journal entry.")
        return ReportTable("Balance Sheet", period(null, asOf), listOf(Col("Particulars", weight = 3f), Col("Amount (NPR)", Align.RIGHT, 1.3f)), rows, notes, warnings)
    }

    // ---------------- Ledgers and journals ----------------
    data class LedgerEntry(val journal: Journal, val line: JournalLine)

    fun ledgerEntries(accountId: Long, from: LocalDate?, to: LocalDate?): List<LedgerEntry> {
        val w = StringBuilder("l.account_id = ?"); val a = ArrayList<Any?>(listOf(accountId))
        if (from != null) { w.append(" AND j.date >= ?"); a.add(from.toString()) }
        if (to != null) { w.append(" AND j.date <= ?"); a.add(to.toString()) }
        return db.query("SELECT j.*, l.id AS lid, l.account_id, l.debit, l.credit, l.memo FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE $w ORDER BY j.date, j.seq, l.id",
            a.toTypedArray()) { r ->
            LedgerEntry(Journal(r.long("id"), r.longOrNull("voucher_id"), r.string("type"), r.string("number"), LocalDate.parse(r.string("date")), r.long("seq"),
                r.string("narration"), r.string("status"), r.longOrNull("reversal_of_id"), r.string("created_at")),
                JournalLine(r.long("lid"), r.long("id"), r.long("account_id"), r.long("debit"), r.long("credit"), r.string("memo")))
        }
    }

    private fun ledgerRows(acc: Account, from: LocalDate, to: LocalDate, rows: MutableList<ReportRow>) {
        val opening = accountBalance(acc.id, from.minusDays(1))
        val blanks = blanksForDates()
        fun drcr(v: Long) = if (v >= 0) "${Money.format(v)} Dr" else "${Money.format(-v)} Cr"
        rows.add(ReportRow(blanks + listOf("", "Opening balance", null, null, drcr(opening)), RowStyle.MUTED))
        var run = opening; var td = 0L; var tc = 0L
        for (e in ledgerEntries(acc.id, from, to)) {
            run += e.line.debit - e.line.credit; td += e.line.debit; tc += e.line.credit
            val link = e.journal.voucherId?.let { "voucher:$it" } ?: "journal:${e.journal.id}"
            rows.add(ReportRow(dateCells(e.journal.date) + listOf(e.journal.number, e.journal.narration + (if (e.line.memo.isNotEmpty() && e.line.memo != e.journal.narration) " - ${e.line.memo}" else ""),
                Amt(e.line.debit, true), Amt(e.line.credit, true), drcr(run)), link = link))
        }
        rows.add(ReportRow(blanks + listOf("", "Closing balance", Amt(td), Amt(tc), drcr(run)), RowStyle.SUBTOTAL))
    }

    private fun ledgerCols() = dateCols() + listOf(Col("No.", weight = 1.3f), Col("Particulars", weight = 3f), Col("Debit", Align.RIGHT, 1.1f), Col("Credit", Align.RIGHT, 1.1f), Col("Balance", Align.RIGHT, 1.3f))

    fun accountLedger(accountId: Long, from: LocalDate, to: LocalDate): ReportTable {
        val acc = b.masters.account(accountId)
        val rows = ArrayList<ReportRow>()
        ledgerRows(acc, from, to, rows)
        return ReportTable("Account Ledger: ${acc.code} ${acc.name}", period(from, to), ledgerCols(), rows, landscape = true)
    }

    fun generalLedger(from: LocalDate, to: LocalDate): ReportTable {
        val rows = ArrayList<ReportRow>()
        val active = db.list("SELECT DISTINCT l.account_id FROM journal_line l JOIN journal j ON j.id = l.journal_id WHERE j.date <= ?", to.toString()) { it.long("account_id") }.toSet()
        for (acc in b.masters.accounts().filter { it.id in active }) {
            rows.add(ReportRow(blanksForDates() + listOf("", "${acc.code} ${acc.name}", null, null, null), RowStyle.SECTION, "account:${acc.id}"))
            ledgerRows(acc, from, to, rows)
        }
        return ReportTable("General Ledger", period(from, to), ledgerCols(), rows, landscape = true)
    }

    fun journalRegister(from: LocalDate, to: LocalDate): ReportTable {
        val rows = ArrayList<ReportRow>()
        val accs = b.masters.accounts().associateBy { it.id }
        var td = 0L; var tc = 0L
        for (j in b.journals.list(from, to)) {
            val link = j.voucherId?.let { "voucher:$it" } ?: "journal:${j.id}"
            rows.add(ReportRow(dateCells(j.date) + listOf(j.number, j.narration + (if (j.status == "REVERSED") " [reversed]" else ""), null, null), RowStyle.SECTION, link))
            for (l in b.journals.lines(j.id)) {
                val a = accs.getValue(l.accountId)
                rows.add(ReportRow(blanksForDates() + listOf("", (if (l.credit > 0) "    To " else "") + "${a.code} ${a.name}", Amt(l.debit, true), Amt(l.credit, true))))
                td += l.debit; tc += l.credit
            }
        }
        rows.add(ReportRow(blanksForDates() + listOf("", "Total", Amt(td), Amt(tc)), RowStyle.TOTAL))
        return ReportTable("Journal Register", period(from, to), dateCols() + listOf(Col("No.", weight = 1.3f), Col("Particulars", weight = 3.5f),
            Col("Debit", Align.RIGHT, 1.2f), Col("Credit", Align.RIGHT, 1.2f)), rows, landscape = true,
            warnings = if (td != tc) listOf("Journal register debits and credits differ by ${Money.format(td - tc)}") else emptyList())
    }

    // ---------------- Document registers ----------------
    fun register(type: VoucherType, from: LocalDate, to: LocalDate, includeInactive: Boolean = true): ReportTable {
        val vs = b.docs.search(listOf(type), from, to, includeInactive = includeInactive, limit = 100000).reversed()
        val rows = ArrayList<ReportRow>()
        val isExpense = type == VoucherType.EXPENSE
        val cats = if (isExpense) b.masters.expenseCategories().associateBy { it.id } else emptyMap()
        var tt = 0L; var tv = 0L; var tg = 0L
        for (v in vs) {
            val active = v.status == VoucherStatus.POSTED
            val status = when (v.status) { VoucherStatus.POSTED -> ""; VoucherStatus.CANCELLED -> "Cancelled"; VoucherStatus.SUPERSEDED -> "Edited (old version)" }
            val cells = ArrayList<Any?>(dateCells(v.date)); cells.add(v.number)
            if (isExpense) { cells.add(cats[v.expenseCategoryId]?.name ?: ""); cells.add(v.description); cells.add(v.partyName); cells.add(v.refNo) }
            else { cells.add(v.partyName); cells.add(v.partyPan); if (type == VoucherType.PURCHASE) cells.add(v.refNo) }
            cells.add(Amt(v.taxable)); cells.add(Amt(v.vat)); cells.add(Amt(v.total))
            if (type == VoucherType.PURCHASE || isExpense) cells.add(if (v.vat > 0) v.vatClass.label else "")
            cells.add(status)
            rows.add(ReportRow(cells, if (active) RowStyle.NORMAL else RowStyle.MUTED, "voucher:${v.id}"))
            if (active) { tt += v.taxable; tv += v.vat; tg += v.total }
        }
        val cols = ArrayList(dateCols()); cols.add(Col("No.", weight = 1.4f))
        if (isExpense) { cols.add(Col("Category", weight = 1.3f)); cols.add(Col("Description", weight = 2f)); cols.add(Col("Payee", weight = 1.5f)); cols.add(Col("Bill No.")) }
        else { cols.add(Col(if (type == VoucherType.SALE || type == VoucherType.SALES_RETURN) "Customer" else "Supplier", weight = 2f)); cols.add(Col("PAN"))
            if (type == VoucherType.PURCHASE) cols.add(Col("Supplier Inv.")) }
        cols.add(Col(if (isExpense) "Amount" else "Taxable", Align.RIGHT, 1.2f)); cols.add(Col("VAT", Align.RIGHT)); cols.add(Col("Total", Align.RIGHT, 1.2f))
        if (type == VoucherType.PURCHASE || isExpense) cols.add(Col("VAT class", weight = 1.2f))
        cols.add(Col("Status"))
        val total = MutableList<Any?>(cols.size) { null }
        total[dateCols().size] = "Total (posted)"
        val ti = cols.indexOfFirst { it.title == "Taxable" || it.title == "Amount" }
        total[ti] = Amt(tt); total[ti + 1] = Amt(tv); total[ti + 2] = Amt(tg)
        rows.add(ReportRow(total, RowStyle.TOTAL))
        val title = when (type) { VoucherType.SALE -> "Sales Invoice Register"; VoucherType.PURCHASE -> "Purchase Invoice Register"
            VoucherType.EXPENSE -> "Expense Register"; VoucherType.SALES_RETURN -> "Sales Return Register"; VoucherType.PURCHASE_RETURN -> "Purchase Return Register"
            VoucherType.STOCK_ADJUSTMENT -> "Stock Adjustment Register" }
        return ReportTable(title, period(from, to), cols, rows, landscape = true,
            notes = listOf("Cancelled and edited (old version) documents are listed for the audit trail but are not included in totals."))
    }

    // ---------------- Stock ----------------
    fun stockOnHand(asOf: LocalDate, lowOnly: Boolean = false, title: String = "Stock on Hand"): ReportTable {
        val rows = ArrayList<ReportRow>()
        var tv = 0L
        val units = b.masters.units().associateBy { it.id }
        val warnings = ArrayList<String>()
        for (p in b.masters.products()) {
            val (q, v) = b.costing.balanceAt(p.id, asOf)
            if (q == 0L && v == 0L && !p.active) continue
            val low = q < p.minStock || q < 0
            if (lowOnly && !low) continue
            val u = units.getValue(p.unitId)
            val avg = if (q > 0) Money.mulDiv(v, 1000, q) else 0L
            val status = when { q < 0 -> "NEGATIVE"; q < p.minStock -> "Low"; else -> "" }
            if (q < 0) warnings.add("${p.name} has negative stock (${np.psy.core.util.Qty.format(q)} ${u.name}). Enter the missing purchase or a stock adjustment.")
            rows.add(ReportRow(listOf(p.code, p.name, p.category, u.name, QtyV(q, u.decimals), Amt(avg), Amt(v), QtyV(p.minStock, u.decimals, true), status),
                if (q < 0) RowStyle.WARNING else RowStyle.NORMAL, "product:${p.id}"))
            tv += v
        }
        rows.add(ReportRow(listOf("", "Total", "", "", null, null, Amt(tv), null, ""), RowStyle.TOTAL))
        val inv = accountBalance(b.masters.accountId("INVENTORY"), asOf)
        val notes = arrayListOf("Average cost is per base unit, using moving weighted-average cost.", "Inventory account balance in the ledger: ${Money.format(inv)}.")
        if (!lowOnly && inv != tv) warnings.add("Stock valuation (${Money.format(tv)}) differs from the Inventory ledger balance (${Money.format(inv)}).")
        return ReportTable(title, period(null, asOf), listOf(Col("Code", weight = 0.9f), Col("Product", weight = 2.5f), Col("Category", weight = 1.2f), Col("Unit", weight = 0.7f),
            Col("Qty", Align.RIGHT), Col("Avg cost", Align.RIGHT), Col("Value", Align.RIGHT, 1.3f), Col("Min", Align.RIGHT, 0.8f), Col("Status", weight = 0.9f)), rows, notes, warnings, landscape = true)
    }

    fun inventoryValuation(asOf: LocalDate): ReportTable {
        val base = stockOnHand(asOf, title = "Inventory Valuation")
        // Category subtotals
        val prods = b.masters.products().associateBy { "product:${it.id}" }
        val byCat = base.rows.filter { it.link != null }.groupBy { prods[it.link]?.category?.ifEmpty { "(No category)" } ?: "" }
        val rows = ArrayList<ReportRow>()
        var total = 0L
        for ((cat, rs) in byCat.toSortedMap()) {
            rows.add(ReportRow(listOf("", cat, "", "", null, null, null, null, ""), RowStyle.SECTION))
            rows.addAll(rs)
            val sub = rs.sumOf { (it.cells[6] as Amt).paisa }; total += sub
            rows.add(ReportRow(listOf("", "Subtotal $cat", "", "", null, null, Amt(sub), null, ""), RowStyle.SUBTOTAL))
        }
        rows.add(ReportRow(listOf("", "Total inventory value", "", "", null, null, Amt(total), null, ""), RowStyle.TOTAL))
        return base.copy(rows = rows)
    }

    fun stockMovement(productId: Long, from: LocalDate, to: LocalDate): ReportTable {
        val p = b.masters.product(productId)
        val u = b.masters.unit(p.unitId)
        val rows = ArrayList<ReportRow>()
        val (oq, ov) = b.costing.balanceAt(productId, from.minusDays(1))
        rows.add(ReportRow(blanksForDates() + listOf("", "Opening balance", null, null, QtyV(oq, u.decimals), null, Amt(ov)), RowStyle.MUTED))
        val vouchers = HashMap<Long, Voucher>()
        var tin = 0L; var tout = 0L
        for (m in b.costing.moves(productId).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }) {
            val v = m.voucherId?.let { vouchers.getOrPut(it) { b.docs.voucher(it) } }
            val label = when (m.kind) { "OPENING" -> "Opening stock"; else -> VoucherType.valueOf(m.kind).label } + (v?.partyName?.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: "")
            if (m.qty > 0) tin += m.qty else tout -= m.qty
            rows.add(ReportRow(dateCells(m.date) + listOf(v?.number ?: "", label + if (m.provisional) " (provisional cost)" else "",
                QtyV(if (m.qty > 0) m.qty else 0, u.decimals, true), QtyV(if (m.qty < 0) -m.qty else 0, u.decimals, true), QtyV(m.balanceQty, u.decimals), Amt(m.value), Amt(m.balanceValue)),
                if (m.balanceQty < 0) RowStyle.WARNING else RowStyle.NORMAL, v?.let { "voucher:${it.id}" }))
        }
        val (cq, cv) = b.costing.balanceAt(productId, to)
        rows.add(ReportRow(blanksForDates() + listOf("", "Closing balance", QtyV(tin, u.decimals), QtyV(tout, u.decimals), QtyV(cq, u.decimals), null, Amt(cv)), RowStyle.TOTAL))
        return ReportTable("Stock Movement: ${p.code} ${p.name} (${u.name})", period(from, to), dateCols() + listOf(Col("No.", weight = 1.3f), Col("Particulars", weight = 2.6f),
            Col("In", Align.RIGHT), Col("Out", Align.RIGHT), Col("Balance", Align.RIGHT), Col("Value", Align.RIGHT, 1.2f), Col("Bal. value", Align.RIGHT, 1.3f)), rows, landscape = true,
            notes = listOf("Same-day order: opening, purchases/stock-in, sales/purchase returns/stock-out, then sales returns."))
    }

    // ---------------- Profit reports ----------------
    fun billWiseProfit(from: LocalDate, to: LocalDate, partyId: Long? = null, sortBy: String = "date"): ReportTable {
        val vs = b.docs.search(listOf(VoucherType.SALE, VoucherType.SALES_RETURN), from, to, partyId = partyId, includeInactive = false, limit = 100000)
        data class R(val v: Voucher, val revenue: Long, val cogs: Long, val provisional: Boolean)
        var list = vs.map { v ->
            val ls = b.docs.lines(v.id)
            val sign = if (v.type == VoucherType.SALE) 1 else -1
            val prov = db.long("SELECT COUNT(*) FROM stock_move WHERE voucher_id = ? AND provisional = 1", v.id) > 0
            R(v, sign * ls.sumOf { it.taxable }, sign * ls.sumOf { it.cost }, prov)
        }
        list = when (sortBy) {
            "profit" -> list.sortedByDescending { it.revenue - it.cogs }
            "margin" -> list.sortedByDescending { if (it.revenue == 0L) Long.MIN_VALUE else (it.revenue - it.cogs) * 1000000 / it.revenue }
            "customer" -> list.sortedWith(compareBy({ it.v.partyName.lowercase() }, { it.v.date }))
            else -> list.sortedWith(compareBy({ it.v.date }, { it.v.seq }))
        }
        val rows = list.map { r ->
            ReportRow(listOf(bs(r.v.date), r.v.number + (if (r.v.type == VoucherType.SALES_RETURN) " (return)" else ""), r.v.partyName, Amt(r.revenue), Amt(r.cogs),
                Amt(r.revenue - r.cogs), Money.percent(r.revenue - r.cogs, r.revenue) + if (r.provisional) " *" else ""), if (r.provisional) RowStyle.WARNING else RowStyle.NORMAL, "voucher:${r.v.id}")
        }.toMutableList()
        val tr = list.sumOf { it.revenue }; val tc = list.sumOf { it.cogs }
        rows.add(ReportRow(listOf("", "Total", "", Amt(tr), Amt(tc), Amt(tr - tc), Money.percent(tr - tc, tr)), RowStyle.TOTAL))
        val notes = arrayListOf("Revenue is after line discounts and excludes VAT. Cost is the weighted-average cost recorded when the goods left stock.",
            "Sales returns are shown as negative rows so totals match the Profit and Loss statement (excluding inventory adjustments).")
        if (list.any { it.provisional }) notes.add("* Some goods were sold while stock was negative; their cost is provisional until the stock shortage is covered.")
        return ReportTable("Bill-wise Profit", period(from, to), listOf(Col("Date (BS)"), Col("Invoice", weight = 1.5f), Col("Customer", weight = 2f), Col("Revenue", Align.RIGHT, 1.2f),
            Col("COGS", Align.RIGHT, 1.2f), Col("Gross profit", Align.RIGHT, 1.2f), Col("Margin", Align.RIGHT, 0.8f)), rows, notes, landscape = true)
    }

    fun productWiseProfit(from: LocalDate, to: LocalDate, productId: Long? = null, sortBy: String = "name"): ReportTable {
        val w = StringBuilder("v.status = 'POSTED' AND v.type IN ('SALE','SALES_RETURN') AND v.date >= ? AND v.date <= ?")
        val a = ArrayList<Any?>(listOf(from.toString(), to.toString()))
        if (productId != null) { w.append(" AND l.product_id = ?"); a.add(productId) }
        data class R(val p: Product, val qty: Long, val revenue: Long, val cogs: Long)
        val agg = db.query("""SELECT l.product_id, SUM(CASE WHEN v.type='SALE' THEN l.base_qty ELSE -l.base_qty END) AS q,
            SUM(CASE WHEN v.type='SALE' THEN l.taxable ELSE -l.taxable END) AS r, SUM(CASE WHEN v.type='SALE' THEN l.cost ELSE -l.cost END) AS c
            FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id WHERE $w GROUP BY l.product_id""", a.toTypedArray()) {
            R(b.masters.product(it.long("product_id")), it.long("q"), it.long("r"), it.long("c")) }
        val sorted = when (sortBy) {
            "profit" -> agg.sortedByDescending { it.revenue - it.cogs }
            "revenue" -> agg.sortedByDescending { it.revenue }
            "qty" -> agg.sortedByDescending { it.qty }
            "margin" -> agg.sortedByDescending { if (it.revenue == 0L) Long.MIN_VALUE else (it.revenue - it.cogs) * 1000000 / it.revenue }
            else -> agg.sortedBy { it.p.name.lowercase() }
        }
        val units = b.masters.units().associateBy { it.id }
        val rows = sorted.map { r ->
            val u = units.getValue(r.p.unitId)
            ReportRow(listOf(r.p.code, r.p.name, QtyV(r.qty, u.decimals), u.name, Amt(r.revenue), Amt(r.cogs), Amt(r.revenue - r.cogs), Money.percent(r.revenue - r.cogs, r.revenue)), link = "product:${r.p.id}")
        }.toMutableList()
        val tr = agg.sumOf { it.revenue }; val tc = agg.sumOf { it.cogs }
        rows.add(ReportRow(listOf("", "Total", null, "", Amt(tr), Amt(tc), Amt(tr - tc), Money.percent(tr - tc, tr)), RowStyle.TOTAL))
        return ReportTable("Product-wise Profit", period(from, to), listOf(Col("Code", weight = 0.9f), Col("Product", weight = 2.4f), Col("Qty sold (net)", Align.RIGHT), Col("Unit", weight = 0.6f),
            Col("Revenue", Align.RIGHT, 1.2f), Col("COGS", Align.RIGHT, 1.2f), Col("Gross profit", Align.RIGHT, 1.2f), Col("Margin", Align.RIGHT, 0.8f)), rows,
            listOf("Quantities and amounts are net of sales returns. Revenue excludes VAT and is after discounts.", "Uses the actual invoice price and the historical weighted-average cost at the time of sale."),
            landscape = true)
    }

    // ---------------- Audit ----------------
    fun auditTrail(limit: Int = 1000): ReportTable {
        val rows = b.audit.recent(limit).map { ReportRow(listOf(it.at.replace('T', ' '), it.action, it.summary, it.details, it.reason)) }
        return ReportTable("Audit Trail", "Latest ${rows.size} entries", listOf(Col("When (device time)", weight = 1.4f), Col("Action", weight = 0.9f), Col("What", weight = 2.5f),
            Col("Details", weight = 3f), Col("Reason", weight = 1.5f)), rows, landscape = true)
    }

    // ---------------- Periods ----------------
    fun bsMonthRange(year: Int, month: Int): Pair<LocalDate, LocalDate> = BsDate(year, month, 1).toAd() to BsDate(year, month, BsCalendar.daysInMonth(year, month)).toAd()

    /** Quarter 1..4 of the financial year that starts in BS year [fyStartYear]. */
    fun fyQuarterRange(fyStartYear: Int, quarter: Int): Pair<LocalDate, LocalDate> {
        val sm = b.years.startMonth
        var y = fyStartYear; var m = sm + (quarter - 1) * 3
        while (m > 12) { m -= 12; y++ }
        val start = BsDate(y, m, 1)
        var ey = y; var em = m + 2
        while (em > 12) { em -= 12; ey++ }
        return start.toAd() to BsDate(ey, em, BsCalendar.daysInMonth(ey, em)).toAd()
    }

    val vat by lazy { VatReports(b) }
    val dashboard by lazy { Dashboard(b) }
}
