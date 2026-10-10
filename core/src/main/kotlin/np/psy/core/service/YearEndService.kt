package np.psy.core.service

import np.psy.core.Books
import np.psy.core.cal.BsDate
import np.psy.core.db.*
import np.psy.core.model.*
import np.psy.core.report.*
import np.psy.core.util.Json
import np.psy.core.util.Money
import java.time.LocalDate

/** Result of a backup made by the app before closing/reopening. */
data class SafetyBackup(val fileName: String, val verified: Boolean, val error: String? = null)

/**
 * Financial-year closing, locking and controlled reopening.
 *
 * Closing: checks -> status "Closing in Progress" -> verified safety backup -> one database
 * transaction that writes the balanced closing entry, the closing record and sets "Closed".
 * If anything fails the year returns to "Open" and no closing entry remains.
 */
class YearEndService(private val b: Books) {
    private val db get() = b.db

    data class Check(val critical: Boolean, val message: String)

    data class Summary(
        val fy: FiscalYear, val pnl: np.psy.core.report.Reports.Pnl, val inventoryQtyLines: List<Triple<Product, Long, Long>>, val inventoryValue: Long,
        val inventoryLedger: Long, val vat: VatReports.Summary, val tbDebit: Long, val tbCredit: Long, val balanceSheet: np.psy.core.report.Reports.BsResult,
        val checks: List<Check>,
    ) {
        val canClose get() = checks.none { it.critical }
    }

    fun summary(fyId: Long): Summary {
        val fy = b.years.get(fyId)
        val p = b.reports.pnl(fy.start, fy.end)
        val inv = b.masters.products().map { pr -> val (q, v) = b.costing.balanceAt(pr.id, fy.end); Triple(pr, q, v) }.filter { it.second != 0L || it.third != 0L }
        val invValue = inv.sumOf { it.third }
        val invLedger = b.reports.accountBalance(b.masters.accountId("INVENTORY"), fy.end)
        val vat = b.reports.vat.summary(fy.start, fy.end, withCarry = false)
        val tb = b.reports.trialBalanceLines(fy.end, fy.id)
        val bs = b.reports.balanceSheetData(fy.end)
        val checks = ArrayList<Check>()
        val earlierOpen = b.years.all().filter { it.end.isBefore(fy.start) && it.status != FyStatus.CLOSED && hasActivityIn(it) }
        earlierOpen.forEach { checks.add(Check(true, "Close the earlier year ${it.name} first.")) }
        if (b.today().isBefore(fy.end) || b.today() == fy.end) checks.add(Check(true, "The year ends on ${BsDate.fromAd(fy.end)} BS. It can be closed only after its last day."))
        val unbalanced = db.list("""SELECT j.number FROM journal j JOIN journal_line l ON l.journal_id = j.id GROUP BY j.id HAVING SUM(l.debit) <> SUM(l.credit)""") { it.string("number") }
        if (unbalanced.isNotEmpty()) checks.add(Check(true, "Unbalanced journal entries: ${unbalanced.take(10).joinToString()}"))
        val tbd = tb.sumOf { it.debit }; val tbc = tb.sumOf { it.credit }
        if (tbd != tbc) checks.add(Check(true, "Trial balance does not balance (difference ${Money.format(tbd - tbc)})."))
        if (!bs.balanced) checks.add(Check(true, "Balance Sheet does not balance at year-end."))
        val negative = inv.filter { it.second < 0 }
        negative.forEach { checks.add(Check(true, "${it.first.name} has negative stock at year-end (${np.psy.core.util.Qty.format(it.second)}). Enter the missing purchase or a stock adjustment dated within the year.")) }
        val prov = db.long("SELECT COUNT(*) FROM stock_move WHERE provisional = 1 AND date >= ? AND date <= ?", fy.start.toString(), fy.end.toString())
        if (prov > 0 && negative.isEmpty()) checks.add(Check(true, "Some sales in this year still have provisional cost because of uncovered stock shortages."))
        if (invValue != invLedger) checks.add(Check(true, "Stock valuation (${Money.format(invValue)}) does not match the Inventory account (${Money.format(invLedger)})."))
        val pending = db.long("SELECT COUNT(*) FROM voucher WHERE status='POSTED' AND vat_class='PENDING' AND date >= ? AND date <= ?", fy.start.toString(), fy.end.toString())
        if (pending > 0) checks.add(Check(false, "$pending bill(s) in this year have VAT pending verification."))
        val obe = b.reports.accountBalance(b.masters.accountId("OPENING_EQUITY"), fy.end)
        if (obe != 0L) checks.add(Check(false, "Opening Balance Equity has a balance of ${Money.format(-obe)}. Consider transferring it to capital/retained earnings with a journal entry."))
        checks.add(Check(false, "VAT filing and payment are not verified by this app. Confirm with your filed returns."))
        return Summary(fy, p, inv, invValue, invLedger, vat, tbd, tbc, bs, checks)
    }

    private fun hasActivityIn(y: FiscalYear) = db.long("SELECT COUNT(*) FROM journal WHERE date >= ? AND date <= ?", y.start.toString(), y.end.toString()) > 0

    /** Closing entry lines: income and expense accounts brought to zero against retained earnings. */
    private fun closingLines(fy: FiscalYear): Pair<List<JournalLineInput>, Long> {
        val totals = b.reports.accountTotals(fy.start, fy.end, excludeClosing = true)
        val lines = ArrayList<JournalLineInput>()
        var net = 0L // credit-positive profit
        for (a in b.masters.accounts().filter { it.type.isPnl }) {
            val (d, c) = totals[a.id] ?: continue
            val bal = d - c
            if (bal == 0L) continue
            lines.add(JournalLineInput(a.id, if (bal < 0) -bal else 0, if (bal > 0) bal else 0, "Close ${a.name}"))
            net -= bal
        }
        val re = b.masters.accountId("RETAINED_EARNINGS")
        if (net != 0L) lines.add(JournalLineInput(re, if (net < 0) -net else 0, if (net > 0) net else 0, if (net >= 0) "Net profit for the year" else "Net loss for the year"))
        return lines to net
    }

    /**
     * Closes [fyId]. [makeBackup] must create a complete backup and verify it (the app does this).
     * Returns the closing journal id (or null if the year had no income/expense activity).
     */
    fun close(fyId: Long, confirmed: Boolean, makeBackup: () -> SafetyBackup): Long? {
        ensure(confirmed) { "Please confirm that you want to close the year" }
        val fy0 = b.years.get(fyId)
        ensure(fy0.status == FyStatus.OPEN) { "Financial year ${fy0.name} is ${fy0.status.label.lowercase()}" }
        ensure(db.long("SELECT COUNT(*) FROM fy_closing WHERE fiscal_year_id = ? AND status = 'ACTIVE'", fyId) == 0L) { "${fy0.name} already has a closing record" }
        val pre = summary(fyId)
        ensure(pre.canClose) { "The year cannot be closed yet:\n" + pre.checks.filter { it.critical }.joinToString("\n") { "- " + it.message } }

        db.transaction { b.years.setStatus(fyId, FyStatus.CLOSING); b.audit.log("CLOSE_START", "fiscal_year", fyId, "Closing of ${fy0.name} started") }
        try {
            val backup = try { makeBackup() } catch (e: Exception) { SafetyBackup("", false, e.message ?: e.toString()) }
            if (!backup.verified) throw UserError("The safety backup failed, so the year was NOT closed. ${backup.error ?: ""}".trim())
            return db.transaction {
                val s = summary(fyId) // re-validate inside the transaction
                val crit = s.checks.filter { it.critical }
                ensure(crit.isEmpty()) { "The year cannot be closed: " + crit.joinToString { it.message } }
                val fy = b.years.get(fyId)
                ensure(fy.status == FyStatus.CLOSING) { "The year status changed while closing. Please try again." }
                val (lines, net) = closingLines(fy)
                val jid = if (lines.isNotEmpty())
                    b.journals.write("CLOSING", "CLOSE-${fy.name}", fy.end, "Year-end closing entry for ${fy.name}: income and expenses transferred to retained earnings",
                        lines, fiscalYearId = fy.id, allowClosedYear = true) else null
                val snapshot = snapshot(fy, s, net)
                db.insert("INSERT INTO fy_closing(fiscal_year_id, journal_id, status, closed_at, net_profit, backup_file, snapshot) VALUES (?,?, 'ACTIVE', ?,?,?,?)",
                    fy.id, jid, b.nowIso(), net, backup.fileName, snapshot)
                b.years.setStatus(fy.id, FyStatus.CLOSED)
                // Post-condition: income and expense accounts are zero for the year.
                val after = b.reports.accountTotals(fy.start, fy.end).filterKeys { id -> b.masters.account(id).type.isPnl }
                check(after.values.all { it.first == it.second }) { "Closing check failed: income/expense accounts not zero" }
                b.audit.log("CLOSE", "fiscal_year", fy.id, "Financial year ${fy.name} closed", "Net ${if (net >= 0) "profit" else "loss"} ${Money.format(Math.abs(net))}; backup ${backup.fileName}")
                jid
            }
        } catch (e: Exception) {
            db.transaction {
                if (b.years.get(fyId).status == FyStatus.CLOSING) b.years.setStatus(fyId, FyStatus.OPEN)
                b.audit.log("CLOSE_FAILED", "fiscal_year", fyId, "Closing of ${fy0.name} failed; year left open", e.message ?: e.toString())
            }
            throw e
        }
    }

    private fun snapshot(fy: FiscalYear, s: Summary, net: Long): String {
        val tb = b.reports.trialBalanceLines(fy.end, fy.id).map { mapOf("code" to it.account.code, "name" to it.account.name, "debit" to it.debit, "credit" to it.credit) }
        val opening = nextYearOpening(fy).map { mapOf("code" to it.first.code, "name" to it.first.name, "balance" to it.second) }
        val inv = s.inventoryQtyLines.map { mapOf("code" to it.first.code, "name" to it.first.name, "qty" to it.second, "value" to it.third) }
        return Json.write(mapOf("fy" to fy.name, "start" to fy.start.toString(), "end" to fy.end.toString(), "netProfit" to net,
            "netSales" to s.pnl.netSales, "cogs" to s.pnl.totalCogs, "grossProfit" to s.pnl.grossProfit, "trialBalance" to tb,
            "nextYearOpening" to opening, "inventory" to inv, "inventoryValue" to s.inventoryValue))
    }

    /** Balance-sheet account balances at year-end after closing (debit positive) = next year's opening balances. */
    fun nextYearOpening(fy: FiscalYear): List<Pair<Account, Long>> =
        b.masters.accounts().filter { !it.type.isPnl }.map { it to b.reports.accountBalance(it.id, fy.end) }.filter { it.second != 0L }

    fun closingRecord(fyId: Long): Map<String, Any?>? = db.first("SELECT * FROM fy_closing WHERE fiscal_year_id = ? ORDER BY id DESC LIMIT 1", fyId) { r ->
        mapOf("status" to r.string("status"), "closed_at" to r.string("closed_at"), "net_profit" to r.long("net_profit"), "backup_file" to r.string("backup_file"),
            "journal_id" to r.longOrNull("journal_id"), "reopened_at" to r.stringOrNull("reopened_at"), "reopen_reason" to r.string("reopen_reason"),
            "reopen_backup_file" to r.string("reopen_backup_file"))
    }

    fun history(): List<Map<String, Any?>> = db.list("SELECT c.*, f.name FROM fy_closing c JOIN fiscal_year f ON f.id = c.fiscal_year_id ORDER BY c.id") { r ->
        mapOf("year" to r.string("name"), "status" to r.string("status"), "closed_at" to r.string("closed_at"), "net_profit" to r.long("net_profit"),
            "reopened_at" to r.stringOrNull("reopened_at"), "reason" to r.string("reopen_reason"))
    }

    /** Reopens the most recently closed year after a reason and a verified safety backup. */
    fun reopen(fyId: Long, reason: String, confirmed: Boolean, makeBackup: () -> SafetyBackup) {
        ensure(confirmed) { "Please confirm that you want to reopen the year" }
        ensure(reason.trim().length >= 5) { "Enter the reason for reopening (at least a few words)" }
        val fy = b.years.get(fyId)
        ensure(fy.status == FyStatus.CLOSED) { "${fy.name} is not closed" }
        val later = b.years.all().filter { it.start.isAfter(fy.end) && it.status == FyStatus.CLOSED }
        ensure(later.isEmpty()) { "Reopen the later year(s) first: ${later.joinToString { it.name }}" }
        val backup = try { makeBackup() } catch (e: Exception) { SafetyBackup("", false, e.message) }
        ensure(backup.verified) { "The safety backup failed, so the year was NOT reopened. ${backup.error ?: ""}".trim() }
        db.transaction {
            val rec = db.first("SELECT id, journal_id FROM fy_closing WHERE fiscal_year_id = ? AND status = 'ACTIVE'", fyId) { it.long("id") to it.longOrNull("journal_id") }
                ?: throw UserError("No active closing record found for ${fy.name}")
            rec.second?.let { b.journals.reverse(it, "Reopening ${fy.name}: ${reason.trim()}", "CLOSING_REVERSAL", allowClosedYear = true) }
            db.update("UPDATE fy_closing SET status = 'REOPENED', reopened_at = ?, reopen_reason = ?, reopen_backup_file = ? WHERE id = ?",
                b.nowIso(), reason.trim(), backup.fileName, rec.first)
            b.years.setStatus(fyId, FyStatus.OPEN)
            b.audit.log("REOPEN", "fiscal_year", fyId, "Financial year ${fy.name} reopened", "Closing entry reversed; backup ${backup.fileName}", reason.trim())
        }
    }

    // ---------------- Closing report ----------------
    fun closingReport(fyId: Long): List<ReportTable> {
        val fy = b.years.get(fyId)
        val s = summary(fyId)
        val rec = closingRecord(fyId)
        val out = ArrayList<ReportTable>()
        val bsS = BsDate.fromAd(fy.start); val bsE = BsDate.fromAd(fy.end)
        val rows = ArrayList<ReportRow>()
        fun kv(k: String, v: Any?) = rows.add(ReportRow(listOf(k, v)))
        kv("Financial year", fy.name)
        kv("Start", "$bsS BS (${fy.start} AD)")
        kv("End", "$bsE BS (${fy.end} AD)")
        kv("Status", fy.status.label)
        kv("Sales excluding VAT", Amt(s.pnl.sales))
        kv("Sales discounts", Amt(s.pnl.discounts))
        kv("Sales returns", Amt(s.pnl.returns))
        kv("Net sales", Amt(s.pnl.netSales))
        kv("Cost of goods sold", Amt(s.pnl.totalCogs))
        kv("Gross profit", Amt(s.pnl.grossProfit))
        kv("Expenses (operating and other)", Amt(s.pnl.opex.values.sum() + s.pnl.otherExp.values.sum()))
        kv("Other income", Amt(s.pnl.otherIncome.values.sum()))
        kv(if (s.pnl.netProfit >= 0) "Net profit" else "Net loss", Amt(s.pnl.netProfit))
        kv("Inventory value at year-end", Amt(s.inventoryValue))
        kv("Output VAT (net of returns)", Amt(s.vat.netOutputVat))
        kv("Eligible input VAT", Amt(s.vat.eligibleInputVat))
        kv("Ineligible input VAT", Amt(s.vat.purchaseVatIneligible + s.vat.expenseVatIneligible))
        kv("Pending-verification VAT", Amt(s.vat.purchaseVatPending + s.vat.expenseVatPending))
        kv("Trial balance (pre-closing) debits / credits", "${Money.format(s.tbDebit)} / ${Money.format(s.tbCredit)}")
        kv("Balance Sheet balanced", if (s.balanceSheet.balanced) "Yes" else "NO")
        if (rec != null) {
            kv("Closing record status", rec["status"]); kv("Closed at (device time)", rec["closed_at"].toString().replace('T', ' '))
            kv("Safety backup before closing", rec["backup_file"]); if (rec["reopened_at"] != null) { kv("Reopened at", rec["reopened_at"].toString().replace('T', ' ')); kv("Reopen reason", rec["reopen_reason"]) }
        } else kv("Closing record", "Not closed yet")
        kv("VAT filing/payment", "Not verified by this app")
        out.add(ReportTable("Financial Year Closing Report ${fy.name}", b.reports.period(fy.start, fy.end), listOf(Col("Item", weight = 2.5f), Col("Value", Align.RIGHT, 2f)), rows,
            notes = s.checks.map { (if (it.critical) "BLOCKING: " else "Note: ") + it.message }))
        out.add(b.reports.trialBalance(fy.end, fy.id))
        out.add(b.reports.profitAndLoss(fy.start, fy.end))
        out.add(b.reports.balanceSheet(fy.end))
        out.add(b.reports.inventoryValuation(fy.end))
        val jid = rec?.get("journal_id") as Long?
        val jr = ArrayList<ReportRow>()
        if (jid != null) {
            val j = b.journals.get(jid)
            for (l in b.journals.lines(jid)) { val a = b.masters.account(l.accountId); jr.add(ReportRow(listOf("${a.code} ${a.name}", Amt(l.debit, true), Amt(l.credit, true)))) }
            jr.add(ReportRow(listOf("Total", Amt(b.journals.lines(jid).sumOf { it.debit }), Amt(b.journals.lines(jid).sumOf { it.credit })), RowStyle.TOTAL))
            out.add(ReportTable("Closing Journal Entry ${j.number}" + if (j.status == "REVERSED") " (reversed on reopening)" else "", "Dated ${BsDate.fromAd(j.date)} BS",
                listOf(Col("Account", weight = 3f), Col("Debit", Align.RIGHT), Col("Credit", Align.RIGHT)), jr))
        } else {
            val (lines, _) = closingLines(fy)
            for (l in lines) { val a = b.masters.account(l.accountId); jr.add(ReportRow(listOf("${a.code} ${a.name}", Amt(l.debit, true), Amt(l.credit, true)))) }
            out.add(ReportTable("Proposed Closing Journal Entry", "Will be dated ${BsDate.fromAd(fy.end)} BS", listOf(Col("Account", weight = 3f), Col("Debit", Align.RIGHT), Col("Credit", Align.RIGHT)), jr))
        }
        val ob = if (fy.status == FyStatus.CLOSED) nextYearOpening(fy) else {
            // Before closing, show what the opening balances will be (balance-sheet accounts + profit to retained earnings).
            val (_, net) = closingLines(fy)
            val re = b.masters.accountId("RETAINED_EARNINGS")
            nextYearOpening(fy).map { if (it.first.id == re) it.first to it.second - net else it }.let { l -> if (l.none { it.first.id == re } && net != 0L) l + (b.masters.account(re) to -net) else l }
        }
        out.add(ReportTable("Next Year Opening Balances", "As at the start of the next financial year",
            listOf(Col("Account", weight = 3f), Col("Debit", Align.RIGHT), Col("Credit", Align.RIGHT)),
            ob.map { (a, v) -> ReportRow(listOf("${a.code} ${a.name}", Amt(if (v > 0) v else 0, true), Amt(if (v < 0) -v else 0, true))) } +
                ReportRow(listOf("Total", Amt(ob.sumOf { if (it.second > 0) it.second else 0 }), Amt(ob.sumOf { if (it.second < 0) -it.second else 0 })), RowStyle.TOTAL),
            notes = listOf("Balance-sheet balances carry forward automatically because the ledger is continuous; no separate opening entry is posted for the new year, so nothing is duplicated.",
                "Income and expense accounts start the new year at zero.")))
        return out
    }
}
