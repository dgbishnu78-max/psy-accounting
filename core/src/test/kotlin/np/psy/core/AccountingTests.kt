package np.psy.core

import np.psy.core.T.eq
import np.psy.core.T.fails
import np.psy.core.T.ok
import np.psy.core.T.test
import np.psy.core.db.UserError
import np.psy.core.model.*

object AccountingTests {
    fun register() {
        test("A06", "Purchase invoice increases stock and inventory value (VAT excluded)") {
            val fx = Fx(); val p = fx.product("Ghee 1kg Jar")
            val r = fx.purchase("2083-05-01", "Himal Dairy", fx.L(p, 10, 50000, disc = 5000), bill = "HD-1")
            eq(10_000L to 495_000L, fx.stock(p))                // 10 x 500 - 50 discount = 4,950.00
            val v = fx.books.docs.voucher(r.voucherId)
            eq(495_000L, v.taxable); eq(64_350L, v.vat); eq(559_350L, v.total)
            eq(495_000L, fx.bal("INVENTORY")); eq(64_350L, fx.bal("VAT_INPUT")); eq(-559_350L, fx.bal("PAYABLES"))
            eq(VatClass.ELIGIBLE, v.vatClass)
            fx.assertBalanced()
        }
        test("A07", "Sales invoice decreases stock at weighted-average cost") {
            val fx = Fx(); val p = fx.product("Sugar", unit = fx.kg)
            fx.purchase("2083-05-01", "S", fx.L(p, 100, 9000), bill = "1")
            val s = fx.sale("2083-05-02", "Ram Traders", fx.L(p, 30, 12000))
            eq(70_000L to 630_000L, fx.stock(p))
            val v = fx.books.docs.voucher(s.voucherId)
            eq(360_000L, v.taxable); eq(46_800L, v.vat); eq(406_800L, v.total)
            eq(270_000L, fx.books.docs.lines(s.voucherId)[0].cost)
            eq(270_000L, fx.bal("COGS")); eq(-360_000L, fx.bal("SALES")); eq(-46_800L, fx.bal("VAT_OUTPUT")); eq(406_800L, fx.bal("RECEIVABLES"))
            // fractional kg
            fx.sale("2083-05-03", "Ram Traders", LineInput(p, 2_500, 12000))
            eq(67_500L, fx.stock(p).first)
            fx.assertBalanced()
        }
        test("A08", "Insufficient stock warns, needs explicit confirmation, and records negative stock") {
            val fx = Fx(); val p = fx.product("Lentils")
            fx.purchase("2083-05-01", "S", fx.L(p, 5, 10000), bill = "1")
            val e = fails<NeedsStockConfirmation> { fx.sale("2083-05-02", "C", fx.L(p, 8, 15000)) }
            eq(1, e.shortages.size); eq(5_000L, e.shortages[0].available); eq(8_000L, e.shortages[0].required)
            eq(0L, fx.db.query("SELECT COUNT(*) AS n FROM voucher WHERE type='SALE'", arrayOf()) { it.long("n") }.first(), "nothing saved without confirmation")
            val r = fx.sale("2083-05-02", "C", fx.L(p, 8, 15000), allowNeg = true)
            ok(r.warnings.any { it.contains("shortage") }, "warning returned")
            eq(-3_000L, fx.stock(p).first)
            ok(fx.books.costing.moves(p).last().provisional, "oversold cost is provisional")
            ok(fx.books.audit.recent().any { it.action == "NEGATIVE_STOCK" }, "audit records the confirmed shortage")
            // provisional cost = last average 100.00; 8 units -> 800.00
            eq(80_000L, fx.books.docs.lines(r.voucherId)[0].cost)
            // shortage resolved by a purchase at 120.00: 3 oversold units re-costed at 120
            fx.purchase("2083-05-05", "S", fx.L(p, 10, 12000), bill = "2")
            eq(50_000L + 36_000L, fx.books.docs.lines(r.voucherId)[0].cost, "sale cost corrected when shortage covered")
            eq(7_000L to 84_000L, fx.stock(p))
            ok(fx.books.costing.moves(p).none { it.provisional }, "no provisional costs remain")
            fx.assertBalanced()
        }
        test("A09", "Moving weighted-average cost recalculates after each purchase") {
            val fx = Fx(); val p = fx.product("Soap", openingQty = 10_000, openingValue = 100_000)
            fx.purchase("2083-05-01", "S", fx.L(p, 10, 13000), bill = "1")     // avg (1000+1300)/20 = 115
            val s1 = fx.sale("2083-05-02", "C", fx.L(p, 5, 20000))             // 575
            eq(57_500L, fx.books.docs.lines(s1.voucherId)[0].cost)
            fx.purchase("2083-05-03", "S", fx.L(p, 15, 10000), bill = "2")     // 15 @115 = 1725 + 1500 = 3225 / 30 = 107.5
            val s2 = fx.sale("2083-05-04", "C", fx.L(p, 10, 20000))
            eq(107_500L, fx.books.docs.lines(s2.voucherId)[0].cost)
            eq(20_000L to 215_000L, fx.stock(p))
            fx.assertBalanced()
        }
        test("A10", "Editing/cancelling a past transaction recalculates later costs") {
            val fx = Fx(); val p = fx.product("Tea")
            val p1 = fx.purchase("2083-05-01", "S", fx.L(p, 10, 10000), bill = "1")
            fx.purchase("2083-05-03", "S", fx.L(p, 10, 20000), bill = "2")
            val s = fx.sale("2083-05-04", "C", fx.L(p, 10, 30000))
            eq(150_000L, fx.books.docs.lines(s.voucherId)[0].cost)
            // Edit the first purchase rate 100 -> 160: avg becomes 180
            val res = fx.books.docs.amend(p1.voucherId, DocInput(VoucherType.PURCHASE, fx.d("2083-05-01"), "S", "123456789", refNo = "1",
                lines = listOf(fx.L(p, 10, 16000))), "Wrong rate entered")
            eq(VoucherStatus.SUPERSEDED, fx.books.docs.voucher(p1.voucherId).status)
            eq(fx.books.docs.voucher(p1.voucherId).number, fx.books.docs.voucher(res.voucherId).number, "keeps number")
            eq(180_000L, fx.books.docs.lines(s.voucherId)[0].cost, "sale re-costed")
            eq(180_000L, fx.bal("COGS"))
            // Backdated purchase before the sale changes the sale's cost again
            fx.purchase("2083-05-02", "S", fx.L(p, 20, 6000), bill = "3")         // (1600+2000+1200)/40 = 120
            eq(120_000L, fx.books.docs.lines(s.voucherId)[0].cost)
            // Cancel that purchase: back to 180
            val b3 = fx.books.docs.search(listOf(VoucherType.PURCHASE), text = "3").first { it.refNo == "3" }
            fx.books.docs.cancel(b3.id, "Entered by mistake")
            eq(180_000L, fx.books.docs.lines(s.voucherId)[0].cost)
            eq(VoucherStatus.CANCELLED, fx.books.docs.voucher(b3.id).status)
            ok(fx.books.journals.forVoucher(b3.id).any { it.type == "REVERSAL" }, "reversal journal kept")
            ok(fx.books.audit.recent().any { it.action == "AMEND" && it.reason == "Wrong rate entered" }, "amend audited with reason")
            fx.assertBalanced()
        }
        test("A11", "Recoverable VAT is excluded from inventory cost") {
            val fx = Fx(); val p = fx.product("Biscuits")
            fx.purchase("2083-05-01", "S", fx.L(p, 100, 1000), bill = "1", vatClass = VatClass.ELIGIBLE)
            eq(100_000L, fx.stock(p).second); eq(13_000L, fx.bal("VAT_INPUT"))
            val p2 = fx.product("Noodles")
            fx.purchase("2083-05-01", "S", fx.L(p2, 100, 1000), bill = "2", vatClass = VatClass.PENDING)
            eq(100_000L, fx.stock(p2).second, "pending VAT kept out of cost"); eq(13_000L, fx.bal("VAT_INPUT_PENDING"))
            fx.assertBalanced()
        }
        test("A12", "Non-recoverable VAT is added to inventory cost") {
            val fx = Fx(); val p = fx.product("Diesel additive")
            fx.purchase("2083-05-01", "S", fx.L(p, 10, 10000), bill = "1", vatClass = VatClass.INELIGIBLE)
            eq(113_000L, fx.stock(p).second); eq(0L, fx.bal("VAT_INPUT"))
            val s = fx.sale("2083-05-02", "C", fx.L(p, 1, 20000))
            eq(11_300L, fx.books.docs.lines(s.voucherId)[0].cost)
            val vat = fx.books.reports.vat.summary(fx.d("2083-05-01"), fx.d("2083-05-31"), false)
            eq(0L, vat.purchaseVatEligible); eq(13_000L, vat.purchaseVatIneligible)
            fx.assertBalanced()
        }
        test("A13", "Expense VAT eligibility: only eligible VAT is input credit") {
            val fx = Fx()
            fx.expense("2083-05-10", "Electricity", 1_000_000, 130_000, VatClass.ELIGIBLE, "NEA", "E1")
            fx.expense("2083-05-11", "Office Expenses", 500_000, 65_000, VatClass.INELIGIBLE, "Hotel", "H1")
            fx.expense("2083-05-12", "Repairs", 200_000, 26_000, VatClass.PENDING, "Fixit", "F1")
            fx.expense("2083-05-13", "Salaries", 3_000_000, 0, VatClass.NONE)
            val vat = fx.books.reports.vat.summary(fx.d("2083-05-01"), fx.d("2083-05-31"), false)
            eq(130_000L, vat.expenseVatEligible); eq(65_000L, vat.expenseVatIneligible); eq(26_000L, vat.expenseVatPending)
            eq(130_000L, vat.eligibleInputVat)
            eq(130_000L, fx.bal("VAT_INPUT")); eq(26_000L, fx.bal("VAT_INPUT_PENDING"))
            val pnl = fx.books.reports.pnl(fx.d("2083-05-01"), fx.d("2083-05-31"))
            eq(1_000_000L + 565_000L + 200_000L + 3_000_000L, pnl.opex.values.sum(), "ineligible VAT charged to expense; recoverable VAT not an expense")
            fails<UserError>("VAT without classification") { fx.expense("2083-05-14", "Rent", 100_000, 13_000, VatClass.NONE) }
            fx.assertBalanced()
        }
        test("A14", "Sales, purchases, returns and expenses generate balanced journals") {
            val fx = Fx(); val p = fx.product("Flour", unit = fx.kg)
            val pu = fx.purchase("2083-05-01", "Mill", fx.L(p, 50, 6000, disc = 1000), bill = "M1")
            val sa = fx.sale("2083-05-02", "Shop", fx.L(p, 20, 8000, disc = 500))
            fx.books.docs.post(DocInput(VoucherType.SALES_RETURN, fx.d("2083-05-03"), "Shop", originalVoucherId = sa.voucherId,
                lines = listOf(LineInput(p, 5_000, 0, originalLineId = fx.books.docs.lines(sa.voucherId)[0].id))))
            fx.books.docs.post(DocInput(VoucherType.PURCHASE_RETURN, fx.d("2083-05-04"), "Mill", originalVoucherId = pu.voucherId,
                lines = listOf(LineInput(p, 5_000, 0, originalLineId = fx.books.docs.lines(pu.voucherId)[0].id))))
            fx.expense("2083-05-05", "Transport", 100_000, 0, VatClass.NONE)
            fx.books.docs.post(DocInput(VoucherType.STOCK_ADJUSTMENT, fx.d("2083-05-06"), description = "Damaged", adjustmentIn = false, lines = listOf(LineInput(p, 1_000, 0))))
            for (j in fx.books.journals.list(null, null)) {
                val ls = fx.books.journals.lines(j.id)
                eq(ls.sumOf { it.debit }, ls.sumOf { it.credit }, "journal ${j.number}")
                ok(ls.isNotEmpty(), "journal has lines")
            }
            fx.assertBalanced()
        }
        test("A15", "Trial balance debits equal credits") {
            val fx = scenario()
            val tb = fx.books.reports.trialBalance(fx.d("2083-06-31"))
            val total = tb.rows.last()
            eq((total.cells[2] as np.psy.core.report.Amt).paisa, (total.cells[3] as np.psy.core.report.Amt).paisa)
            ok(tb.warnings.isEmpty(), "no warnings")
        }
        test("A16", "Balance Sheet balances") {
            val fx = scenario()
            for (d in listOf("2083-04-01", "2083-05-15", "2083-06-31")) {
                val bs = fx.books.reports.balanceSheetData(fx.d(d))
                ok(bs.balanced, "balanced at $d")
            }
            ok(fx.books.reports.balanceSheet(fx.d("2083-06-31")).warnings.isEmpty(), "no warning")
        }
        test("A17", "Profit or loss flows into equity") {
            val fx = scenario()
            val start = fx.books.years.booksStart()
            val pnl = fx.books.reports.pnl(start, fx.d("2083-06-31"))
            val bs = fx.books.reports.balanceSheetData(fx.d("2083-06-31"))
            eq(pnl.netProfit, bs.unclosedProfit, "net profit appears in equity")
            ok(pnl.netProfit != 0L, "scenario has a profit")
        }
        test("A18", "Bill-wise and product-wise profit reconcile with sales and COGS") {
            val fx = scenario()
            val from = fx.books.years.booksStart(); val to = fx.d("2083-06-31")
            val pnl = fx.books.reports.pnl(from, to)
            val bw = fx.books.reports.billWiseProfit(from, to)
            val pw = fx.books.reports.productWiseProfit(from, to)
            fun tot(t: np.psy.core.report.ReportTable, i: Int) = (t.rows.last().cells[i] as np.psy.core.report.Amt).paisa
            eq(pnl.netSales, tot(bw, 3), "bill-wise revenue = net sales")
            eq(pnl.cogs, tot(bw, 4), "bill-wise COGS = COGS account")
            eq(pnl.netSales, tot(pw, 4), "product-wise revenue")
            eq(pnl.cogs, tot(pw, 5), "product-wise COGS")
            // Historical reports do not change when prices change
            val before = fx.books.reports.billWiseProfit(from, to).rows.map { it.cells.toString() }
            val p = fx.m.products().first()
            fx.m.saveProduct(p.id, p.code, p.name, p.category, p.unitId, p.vatCategoryId, 999_999, p.openingQty, p.openingValue, p.minStock)
            eq(before, fx.books.reports.billWiseProfit(from, to).rows.map { it.cells.toString() })
        }
        test("A19", "Duplicate posting is prevented") {
            val fx = Fx(); val p = fx.product("Salt")
            fx.purchase("2083-05-01", "Supplier X", fx.L(p, 10, 1000), bill = "INV-77")
            val e = fails<UserError> { fx.purchase("2083-05-02", "supplier x", fx.L(p, 10, 1000), bill = "inv-77") }
            ok(e.message!!.contains("already entered"), e.message!!)
            fails<UserError>("same bill as expense") { fx.expense("2083-05-03", "Rent", 1000, 0, VatClass.NONE, "Supplier X", "INV-77") }
            val r1 = fx.sale("2083-05-02", "C", fx.L(p, 1, 2000), token = "tok-1")
            val r2 = fx.sale("2083-05-02", "C", fx.L(p, 1, 2000), token = "tok-1")
            eq(r1.voucherId, r2.voucherId, "double tap returns the same invoice")
            ok(r2.warnings.any { it.contains("not saved twice") }, "user told")
            val n = fx.books.docs.voucher(r1.voucherId).number
            fails<UserError>("manual duplicate invoice number") { fx.books.docs.post(DocInput(VoucherType.SALE, fx.d("2083-05-02"), "C", number = n, lines = listOf(fx.L(p, 1, 2000)))) }
            fx.books.docs.cancel(r1.voucherId, "test")
            fails<UserError>("cancelled numbers are not reused") { fx.books.docs.post(DocInput(VoucherType.SALE, fx.d("2083-05-02"), "C", number = n, lines = listOf(fx.L(p, 1, 2000)))) }
        }
        test("A20", "Opening balances are not duplicated") {
            val fx = Fx()
            fx.product("Item A", openingQty = 10_000, openingValue = 500_000)
            val cash = fx.m.accountId("CASH"); val cap = fx.m.accountId("CAPITAL"); val loan = fx.m.accounts().first { it.code == "2300" }.id
            fx.books.journals.setOpeningBalances(listOf(JournalLineInput(cash, 1_000_000, 0), JournalLineInput(loan, 0, 300_000), JournalLineInput(cap, 0, 1_200_000)))
            eq(500_000L, fx.bal("INVENTORY"))
            fails<UserError>("inventory must not be entered again") { fx.books.journals.setOpeningBalances(listOf(JournalLineInput(fx.m.accountId("INVENTORY"), 500_000, 0))) }
            // Replace opening balances: old set is reversed, not added twice
            fx.books.journals.setOpeningBalances(listOf(JournalLineInput(cash, 1_000_000, 0), JournalLineInput(loan, 0, 300_000), JournalLineInput(cap, 0, 1_200_000)))
            eq(1_000_000L, fx.bal("CASH")); eq(-1_200_000L, fx.bal("CAPITAL"))
            eq(1L, fx.db.query("SELECT COUNT(*) AS n FROM journal WHERE type='OPENING' AND status='POSTED'", arrayOf()) { it.long("n") }.first())
            // Opening stock changes replace (not add to) the opening stock entry
            val p = fx.m.products().first()
            fx.m.saveProduct(p.id, p.code, p.name, p.category, p.unitId, p.vatCategoryId, 0, 10_000, 600_000, 0)
            eq(600_000L, fx.bal("INVENTORY"))
            // difference goes to Opening Balance Equity: 1,000,000 + 600,000 - 300,000 - 1,200,000 = 100,000 credit
            eq(-100_000L, fx.bal("OPENING_EQUITY"))
            fx.assertBalanced()
        }
        test("A21x", "Sales return restores stock at original cost; purchase return at average") {
            val fx = Fx(); val p = fx.product("Jam")
            val pu = fx.purchase("2083-05-01", "S", fx.L(p, 10, 10000), bill = "1")
            fx.purchase("2083-05-02", "S", fx.L(p, 10, 20000), bill = "2")
            val s = fx.sale("2083-05-03", "C", fx.L(p, 10, 30000))        // cost 1500
            fx.purchase("2083-05-04", "S", fx.L(p, 10, 40000), bill = "3")  // 10@150 + 10@400 => 20 @ 275
            val sl = fx.books.docs.lines(s.voucherId)[0]
            fx.books.docs.post(DocInput(VoucherType.SALES_RETURN, fx.d("2083-05-05"), "C", originalVoucherId = s.voucherId, lines = listOf(LineInput(p, 2_000, 0, originalLineId = sl.id))))
            eq(22_000L to (550_000L + 30_000L), fx.stock(p), "returned at original cost 150")
            fails<UserError>("cannot return more than sold") { fx.books.docs.post(DocInput(VoucherType.SALES_RETURN, fx.d("2083-05-05"), "C", originalVoucherId = s.voucherId, lines = listOf(LineInput(p, 9_000, 0, originalLineId = sl.id)))) }
            fx.books.docs.post(DocInput(VoucherType.PURCHASE_RETURN, fx.d("2083-05-06"), "S", originalVoucherId = pu.voucherId,
                lines = listOf(LineInput(p, 2_000, 0, originalLineId = fx.books.docs.lines(pu.voucherId)[0].id))))
            fx.assertBalanced()
            val pnl = fx.books.reports.pnl(fx.d("2083-05-01"), fx.d("2083-05-31"))
            ok(pnl.invAdj != 0L, "purchase return price difference recorded in inventory adjustments")
        }
        test("A22x", "Closed-period and customer price rules") {
            val fx = Fx(); val p = fx.product("Honey", price = 50_000)
            val c = fx.m.saveParty(null, "Big Customer", true, false)
            fx.m.setCustomerPrice(c, p, 60_000)
            eq(60_000L, fx.m.suggestedPrice(c, p)); eq(50_000L, fx.m.suggestedPrice(null, p))
            fx.purchase("2083-05-01", "S", fx.L(p, 10, 30000), bill = "1")
            val s = fx.sale("2083-05-02", "Big Customer", fx.L(p, 1, 60_000))
            fx.m.setCustomerPrice(c, p, 70_000)
            eq(60_000L, fx.books.docs.lines(s.voucherId)[0].rate, "invoice keeps the price charged")
            fails<UserError>("before books start") { fx.sale("2083-03-15", "C", fx.L(p, 1, 1000)) }
        }
    }

    /** A month of mixed activity used by several tests. */
    fun scenario(): Fx {
        val fx = Fx()
        val a = fx.product("Basmati Rice 5kg", openingQty = 20_000, openingValue = 1_200_000)
        val b = fx.product("Mustard Oil 1L")
        val c = fx.product("Exempt Lentils", vat = fx.exempt)
        fx.books.journals.setOpeningBalances(listOf(JournalLineInput(fx.m.accountId("CASH"), 2_000_000, 0), JournalLineInput(fx.m.accountId("CAPITAL"), 0, 3_200_000)))
        fx.purchase("2083-04-05", "Annapurna Suppliers", fx.L(a, 30, 65000), fx.L(b, 50, 22000), bill = "AS-101")
        fx.purchase("2083-04-20", "Lentil House", fx.L(c, 40, 15000), bill = "LH-9")
        val s1 = fx.sale("2083-05-02", "Kathmandu Mart", fx.L(a, 10, 90000, disc = 5000), fx.L(b, 20, 30000))
        fx.sale("2083-05-10", "Bhaktapur Store", fx.L(a, 5, 95000), fx.L(c, 10, 20000))
        fx.books.docs.post(DocInput(VoucherType.SALES_RETURN, fx.d("2083-05-12"), "Kathmandu Mart", originalVoucherId = s1.voucherId,
            lines = listOf(LineInput(b, 2_000, 0, originalLineId = fx.books.docs.lines(s1.voucherId)[1].id))))
        fx.expense("2083-05-15", "Rent", 2_500_000, 0, VatClass.NONE, "Landlord")
        fx.expense("2083-05-16", "Electricity", 300_000, 39_000, VatClass.ELIGIBLE, "NEA", "N-55")
        fx.books.journals.postManual(fx.d("2083-05-20"), "Collection from Kathmandu Mart", listOf(
            JournalLineInput(fx.m.accountId("BANK"), 1_000_000, 0), JournalLineInput(fx.m.accountId("RECEIVABLES"), 0, 1_000_000)))
        fx.sale("2083-06-03", "Kathmandu Mart", fx.L(a, 8, 92000))
        fx.assertBalanced()
        return fx
    }
}
