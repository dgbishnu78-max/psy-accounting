package np.psy.core

import np.psy.core.T.eq
import np.psy.core.T.fails
import np.psy.core.T.ok
import np.psy.core.T.test
import np.psy.core.cal.*
import np.psy.core.model.*
import np.psy.core.util.Json
import java.io.File
import java.time.LocalDate

object CalendarTests {
    fun register() {
        test("C01", "BS<->AD conversion matches published references and 5 independent datasets") {
            val refs = mapOf(
                "2000-01-01" to "1943-04-14", "2080-01-01" to "2023-04-14", "2081-01-01" to "2024-04-13", "2082-01-01" to "2025-04-14",
                "2083-01-01" to "2026-04-14", "2083-06-01" to "2026-09-17", "2083-06-24" to "2026-10-10", "2083-06-31" to "2026-10-17",
                "2084-01-01" to "2027-04-14", "2084-03-01" to "2027-06-15", "2084-03-32" to "2027-07-16", "2084-05-01" to "2027-08-17",
                "2084-05-31" to "2027-09-16", "2085-01-01" to "2028-04-13")
            for ((bs, ad) in refs) {
                eq(LocalDate.parse(ad), BsDate.parse(bs).toAd(), "BS $bs")
                eq(bs, BsDate.fromAd(LocalDate.parse(ad)).toString(), "AD $ad")
            }
            // Month starts 2000-2083 must agree with every source dataset that covers them.
            val f = listOf(File("docs/calendar/source-comparison.json"), File("../docs/calendar/source-comparison.json")).first { it.exists() }
            @Suppress("UNCHECKED_CAST") val all = Json.parse(f.readText()) as Map<String, Map<String, String>>
            var checked = 0
            for (y in 2000..2083) for (mo in 1..12) {
                val mine = BsDate(y, mo, 1).toAd().toString()
                val votes = all.values.mapNotNull { it["$y-$mo"] }
                ok(votes.count { it == mine } * 2 > votes.size, "BS $y-$mo: $mine vs sources $votes")
                checked++
            }
            eq(1008, checked)
        }
        test("C01b", "Every day round-trips BS->AD->BS over the whole supported range") {
            var d = BsCalendar.minAd
            var n = 0
            while (!d.isAfter(BsCalendar.maxAd)) {
                val bs = BsDate.fromAd(d)
                eq(d, bs.toAd(), "round trip $d")
                ok(bs.day in 1..BsCalendar.daysInMonth(bs.year, bs.month), "day in month")
                d = d.plusDays(1); n++
            }
            ok(n > 36000, "covered $n days")
            fails<IllegalArgumentException> { BsDate.fromAd(LocalDate.of(1900, 1, 1)) }
            fails<IllegalArgumentException> { BsDate.fromAd(LocalDate.of(2050, 1, 1)) }
        }
        test("C02", "Month boundaries and varying month lengths") {
            eq(32, BsCalendar.daysInMonth(2083, 3), "Asar 2083")
            eq(29, BsCalendar.daysInMonth(2083, 8), "Mangsir 2083")
            eq(31, BsCalendar.daysInMonth(2083, 6), "Asoj 2083")
            ok((2000..2099).map { BsCalendar.daysInMonth(it, 1) }.toSet().size > 1, "Baisakh length varies by year")
            eq(BsDate(2083, 7, 1), BsDate(2083, 6, 31).plusDays(1), "next day after last day of Asoj")
            eq(BsDate(2084, 1, 1), BsDate(2083, 12, BsCalendar.daysInMonth(2083, 12)).plusDays(1), "new year")
            fails<IllegalArgumentException> { BsDate.parse("2083-06-32") }
            fails<IllegalArgumentException> { BsDate.parse("2083-13-01") }
            fails<IllegalArgumentException> { BsDate.parse("2083-02-32") }
            eq(BsDate(2083, 3, 32), BsDate.parse("2083/3/32"))
            eq(BsDate(2083, 6, 24), BsDate.parse(" 2083-06-24 BS "))
            fails<IllegalArgumentException> { BsDate.parse("24-06-2083") }
        }
        test("C03", "Direct BS date entry works for every transaction type") {
            val fx = Fx()
            val p = fx.product("Rice 25kg")
            val date = "2083-05-15"
            val ad = Dates.isoFromBs(date)
            val pur = fx.purchase(date, "Supplier A", fx.L(p, 10, 100000), bill = "B1")
            val sal = fx.sale(date, "Customer A", fx.L(p, 2, 150000))
            val ret = fx.books.docs.post(DocInput(VoucherType.SALES_RETURN, fx.d(date), "Customer A", originalVoucherId = sal.voucherId,
                lines = listOf(LineInput(p, 1000, 0, originalLineId = fx.books.docs.lines(sal.voucherId)[0].id))))
            val pret = fx.books.docs.post(DocInput(VoucherType.PURCHASE_RETURN, fx.d(date), "Supplier A", originalVoucherId = pur.voucherId,
                lines = listOf(LineInput(p, 1000, 0, originalLineId = fx.books.docs.lines(pur.voucherId)[0].id))))
            val exp = fx.expense(date, "Rent", 2000000, 0, VatClass.NONE)
            val adj = fx.books.docs.post(DocInput(VoucherType.STOCK_ADJUSTMENT, fx.d(date), description = "Count", adjustmentIn = false, lines = listOf(LineInput(p, 1000, 0))))
            val jv = fx.books.journals.postManual(fx.d(date), "Cash deposited", listOf(
                JournalLineInput(fx.m.accountId("BANK"), 100000, 0), JournalLineInput(fx.m.accountId("CASH"), 0, 100000)))
            for (r in listOf(pur, sal, ret, pret, exp, adj)) {
                val v = fx.books.docs.voucher(r.voucherId)
                eq(ad, v.date.toString()); eq(date, Dates.bs(v.date.toString()))
            }
            eq(ad, fx.books.journals.get(jv).date.toString())
            fx.assertBalanced()
        }
        test("C04", "Chronological sorting and BS reporting periods") {
            val fx = Fx()
            val p = fx.product("Oil 1L")
            fx.purchase("2083-06-01", "S", fx.L(p, 10, 10000), bill = "A")
            fx.purchase("2083-05-31", "S", fx.L(p, 10, 20000), bill = "B")   // last day of Bhadra 2083
            fx.sale("2083-06-31", "C", fx.L(p, 1, 30000))                   // last day of Asoj
            fx.sale("2083-07-01", "C", fx.L(p, 1, 30000))                   // first day of Kartik
            val moves = fx.books.costing.moves(p)
            ok(moves.zipWithNext().all { (a, b) -> !a.date.isAfter(b.date) }, "moves sorted by date")
            eq(fx.d("2083-05-31"), moves.first().date, "earliest first")
            val (s, e) = fx.books.reports.bsMonthRange(2083, 6)
            eq(fx.d("2083-06-01"), s); eq(fx.d("2083-06-31"), e)
            val vat = fx.books.reports.vat.summary(s, e, withCarry = false)
            eq(30000L, vat.taxableSales.sumOf { it.value }, "only the Asoj sale")
            eq(100000L, vat.taxablePurchases.sumOf { it.value }, "only the Asoj purchase")
            val (q1s, q1e) = fx.books.reports.fyQuarterRange(2083, 1)
            eq(fx.d("2083-04-01"), q1s); eq(fx.d("2083-06-31"), q1e)
        }
        test("C05", "Nepal financial year starts Shrawan 1 and ends on the last day of Asar") {
            val fx = Fx()
            val fy = fx.books.years.forDate(fx.d("2083-06-24"))
            eq("2083/84", fy.name)
            eq(BsDate(2083, 4, 1), BsDate.fromAd(fy.start))
            eq(BsDate(2084, 3, BsCalendar.daysInMonth(2084, 3)), BsDate.fromAd(fy.end))
            eq(LocalDate.of(2026, 7, 17), fy.start)
            eq(LocalDate.of(2027, 7, 16), fy.end)
            val prev = fx.books.years.forDate(fx.d("2083-03-32"))
            eq("2082/83", prev.name)
            eq(fy.start.minusDays(1), prev.end)
            eq(fx.d("2084-04-01"), fx.books.years.forDate(fx.d("2084-04-01")).start)
            eq(fy.start, fx.books.years.booksStart(), "books start at FY start by default")
        }
    }
}
