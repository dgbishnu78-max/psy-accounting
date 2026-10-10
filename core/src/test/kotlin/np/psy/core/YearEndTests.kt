package np.psy.core

import np.psy.core.T.eq
import np.psy.core.T.fails
import np.psy.core.T.ok
import np.psy.core.T.test
import np.psy.core.db.UserError
import np.psy.core.model.*
import np.psy.core.service.SafetyBackup

object YearEndTests {
    val goodBackup = { SafetyBackup("PSY_Safety_test.zip", true) }

    /** Activity in FY 2082/83 (books start 2082-04-01), clock moved into 2083/84. */
    fun yearWithActivity(): Fx {
        val fx = Fx(start = "2082-09-10")
        val a = fx.product("Rice", openingQty = 10_000, openingValue = 400_000)
        fx.books.journals.setOpeningBalances(listOf(JournalLineInput(fx.m.accountId("CASH"), 600_000, 0), JournalLineInput(fx.m.accountId("CAPITAL"), 0, 1_000_000)))
        fx.purchase("2082-09-01", "Supplier", fx.L(a, 20, 45000), bill = "P1")
        fx.sale("2082-09-05", "Customer", fx.L(a, 15, 70000))
        fx.expense("2082-09-06", "Rent", 200_000, 0, VatClass.NONE)
        fx.sale("2083-03-20", "Customer", fx.L(a, 5, 72000))
        fx.clock.setBs("2083-04-10")
        return fx
    }

    fun fy(fx: Fx, name: String) = fx.books.years.all().first { it.name == name }

    fun closedYear(): Fx { val fx = yearWithActivity(); fx.books.yearEnd.close(fy(fx, "2082/83").id, true, goodBackup); return fx }

    fun pnlBalances(fx: Fx, f: FiscalYear) = fx.books.reports.accountTotals(f.start, f.end).filterKeys { fx.m.account(it).type.isPnl }

    fun register() {
        test("Y28", "Closing entries balance and zero income/expense accounts") {
            val fx = yearWithActivity(); val f = fy(fx, "2082/83")
            val profit = fx.books.reports.pnl(f.start, f.end).netProfit
            val jid = fx.books.yearEnd.close(f.id, true, goodBackup)!!
            val ls = fx.books.journals.lines(jid)
            eq(ls.sumOf { it.debit }, ls.sumOf { it.credit })
            ok(pnlBalances(fx, f).values.all { it.first == it.second }, "P&L accounts zero after closing")
            eq(-profit, fx.bal("RETAINED_EARNINGS"), "profit in retained earnings")
            eq(FyStatus.CLOSED, fy(fx, "2082/83").status)
            fx.assertBalanced()
        }
        test("Y29", "Repeated closing attempts do not duplicate closing entries") {
            val fx = closedYear(); val f = fy(fx, "2082/83")
            fails<UserError> { fx.books.yearEnd.close(f.id, true, goodBackup) }
            eq(1L, fx.db.query("SELECT COUNT(*) AS n FROM journal WHERE type='CLOSING'", arrayOf()) { it.long("n") }.first())
            eq(1L, fx.db.query("SELECT COUNT(*) AS n FROM fy_closing", arrayOf()) { it.long("n") }.first())
        }
        test("Y30", "Closing fails safely when the safety backup fails") {
            val fx = yearWithActivity(); val f = fy(fx, "2082/83")
            fails<UserError> { fx.books.yearEnd.close(f.id, true) { SafetyBackup("", false, "storage full") } }
            fails<UserError> { fx.books.yearEnd.close(f.id, true) { throw RuntimeException("crash") } }
            eq(FyStatus.OPEN, fy(fx, "2082/83").status)
            eq(0L, fx.db.query("SELECT COUNT(*) AS n FROM journal WHERE type='CLOSING'", arrayOf()) { it.long("n") }.first())
            ok(fx.books.audit.recent().any { it.action == "CLOSE_FAILED" }, "failure audited")
            fails<UserError>("needs confirmation") { fx.books.yearEnd.close(f.id, false, goodBackup) }
        }
        test("Y31", "Interrupted closing leaves no partial entries") {
            val fx = yearWithActivity(); val f = fy(fx, "2082/83")
            // Simulate a failure after the closing journal is written but before the closing record: whole transaction rolls back.
            fx.db.execute("CREATE TRIGGER fail_close BEFORE INSERT ON fy_closing BEGIN SELECT RAISE(ABORT, 'simulated power loss'); END")
            fails<Exception> { fx.books.yearEnd.close(f.id, true, goodBackup) }
            eq(0L, fx.db.query("SELECT COUNT(*) AS n FROM journal WHERE type='CLOSING'", arrayOf()) { it.long("n") }.first(), "no partial closing entry")
            eq(FyStatus.OPEN, fy(fx, "2082/83").status)
            fx.db.execute("DROP TRIGGER fail_close")
            // Simulate the app being killed while status was 'Closing in Progress'
            fx.db.update("UPDATE fiscal_year SET status = 'CLOSING' WHERE id = ?", f.id)
            val again = Books(fx.db, fx.clock)
            eq(FyStatus.OPEN, again.years.get(f.id).status, "recovered to Open at start-up")
            again.yearEnd.close(f.id, true, goodBackup)
            eq(FyStatus.CLOSED, again.years.get(f.id).status, "can close normally afterwards")
            fx.assertBalanced()
        }
        test("Y32", "Next-year opening balances are correct") {
            val fx = yearWithActivity(); val f = fy(fx, "2082/83")
            val bsBefore = fx.books.reports.balanceSheetData(f.end)
            val profit = fx.books.reports.pnl(f.start, f.end).netProfit
            fx.books.yearEnd.close(f.id, true, goodBackup)
            val next = fx.books.years.forDate(f.end.plusDays(1))
            eq("2083/84", next.name)
            val opening = fx.books.yearEnd.nextYearOpening(fy(fx, "2082/83")).associate { it.first.systemKey to it.second }
            eq(bsBefore.assets.first { it.first.systemKey == "INVENTORY" }.second, opening["INVENTORY"], "inventory carried forward")
            eq(-profit, opening["RETAINED_EARNINGS"], "profit carried as retained earnings")
            val newPnl = fx.books.reports.pnl(next.start, next.end)
            eq(0L, newPnl.netSales); eq(0L, newPnl.netProfit, "new year starts with no income/expense")
            val bsNext = fx.books.reports.balanceSheetData(next.start)
            ok(bsNext.balanced, "balanced at new year start"); eq(0L, bsNext.unclosedProfit)
            eq(bsBefore.totalAssets, bsNext.totalAssets, "assets unchanged by closing")
            // Stock continues with the same quantity and value
            val p = fx.m.products().first()
            eq(fx.books.costing.balanceAt(p.id, f.end), fx.books.costing.balanceAt(p.id, next.start))
        }
        test("Y33", "Closed-year transactions cannot be edited or added") {
            val fx = closedYear()
            val p = fx.m.products().first()
            fails<UserError> { fx.sale("2083-03-25", "Customer", fx.L(p.id, 1, 70000)) }
            val old = fx.books.docs.search(listOf(VoucherType.SALE)).first { it.date.isBefore(fx.books.years.forDate(fx.d("2083-04-01")).start) }
            fails<UserError> { fx.books.docs.cancel(old.id, "try") }
            fails<UserError> { fx.books.docs.amend(old.id, DocInput(VoucherType.SALE, old.date, "Customer", lines = listOf(fx.L(p.id, 1, 1000))), "try") }
            fails<UserError> { fx.books.journals.postManual(fx.d("2083-01-01"), "late", listOf(JournalLineInput(fx.m.accountId("CASH"), 1, 0), JournalLineInput(fx.m.accountId("CAPITAL"), 0, 1))) }
            fails<UserError> { fx.m.saveProduct(p.id, p.code, p.name, p.category, p.unitId, p.vatCategoryId, 0, p.openingQty + 1000, p.openingValue, 0) }
            fails<UserError> { fx.books.journals.setOpeningBalances(emptyList()) }
            // New year still works
            fx.sale("2083-04-12", "Customer", fx.L(p.id, 1, 70000))
            fx.assertBalanced()
        }
        test("Y34", "Controlled reopening needs a reason and backup, and is audited") {
            val fx = closedYear(); val f = fy(fx, "2082/83")
            fails<UserError> { fx.books.yearEnd.reopen(f.id, "", true, goodBackup) }
            fails<UserError> { fx.books.yearEnd.reopen(f.id, "Missing purchase bill found", true) { SafetyBackup("", false, "fail") } }
            eq(FyStatus.CLOSED, fy(fx, "2082/83").status)
            fx.books.yearEnd.reopen(f.id, "Missing purchase bill found", true, goodBackup)
            eq(FyStatus.OPEN, fy(fx, "2082/83").status)
            val a = fx.books.audit.recent().first { it.action == "REOPEN" }
            eq("Missing purchase bill found", a.reason)
            val rec = fx.books.yearEnd.closingRecord(f.id)!!
            eq("REOPENED", rec["status"]); ok(rec["reopened_at"] != null, "reopen time recorded")
            ok(fx.books.journals.list(null, null).any { it.type == "CLOSING_REVERSAL" }, "closing entry reversed, not deleted")
            ok(fx.books.journals.list(null, null).any { it.type == "CLOSING" && it.status == "REVERSED" }, "original closing entry kept")
        }
        test("Y35", "Reopening and reclosing do not duplicate balances or closing entries") {
            val fx = closedYear(); val f = fy(fx, "2082/83")
            val re1 = fx.bal("RETAINED_EARNINGS")
            fx.books.yearEnd.reopen(f.id, "Add missing expense", true, goodBackup)
            eq(0L, fx.bal("RETAINED_EARNINGS"), "profit no longer in retained earnings while open")
            fx.expense("2083-03-30", "Repairs", 50_000, 0, VatClass.NONE)
            fx.books.yearEnd.close(f.id, true, goodBackup)
            eq(re1 + 50_000, fx.bal("RETAINED_EARNINGS"), "retained earnings counted once, with the correction")
            eq(1L, fx.db.query("SELECT COUNT(*) AS n FROM journal WHERE type='CLOSING' AND status='POSTED'", arrayOf()) { it.long("n") }.first())
            ok(pnlBalances(fx, fy(fx, "2082/83")).values.all { it.first == it.second }, "P&L zero again")
            fx.assertBalanced()
        }
        test("Y36", "Historical reports stay accessible and consistent after closing") {
            val fx = yearWithActivity(); val f = fy(fx, "2082/83")
            val pnl = fx.books.reports.profitAndLoss(f.start, f.end).rows.map { it.cells.toString() }
            val bw = fx.books.reports.billWiseProfit(f.start, f.end).rows.map { it.cells.toString() }
            val vat = fx.books.reports.vat.summaryReport(f.start, f.end).rows.map { it.cells.toString() }
            fx.books.yearEnd.close(f.id, true, goodBackup)
            eq(pnl, fx.books.reports.profitAndLoss(f.start, f.end).rows.map { it.cells.toString() }, "P&L unchanged")
            eq(bw, fx.books.reports.billWiseProfit(f.start, f.end).rows.map { it.cells.toString() }, "bill-wise unchanged")
            eq(vat, fx.books.reports.vat.summaryReport(f.start, f.end).rows.map { it.cells.toString() }, "VAT unchanged")
            val rep = fx.books.yearEnd.closingReport(f.id)
            ok(rep.size >= 7, "closing report has all sections")
            ok(rep.first().rows.any { it.cells[0] == "Status" && it.cells[1] == "Closed" }, "shows closed")
            ok(fx.books.docs.search(listOf(VoucherType.SALE), f.start, f.end).size == 2, "invoices searchable")
        }
    }
}
