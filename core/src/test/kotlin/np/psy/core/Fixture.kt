package np.psy.core

import np.psy.core.cal.BsDate
import np.psy.core.model.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class FakeClock(var t: LocalDateTime) : Clock {
    override fun now() = t
    fun setBs(bs: String, hour: Int = 10) { t = BsDate.parse(bs).toAd().atTime(hour, 0) }
}

/** A fresh company database with a few products. All money in paisa, quantities in thousandths. */
class Fx(val file: File? = null, start: String = "2083-06-24") {
    val clock = FakeClock(BsDate.parse(start).toAd().atTime(10, 0))
    val db = JdbcDb(file)
    val books = Books(db, clock)
    val m get() = books.masters
    val pcs get() = m.unitByName("Pcs")!!.id
    val kg get() = m.unitByName("Kg")!!.id
    val vat13 get() = m.vatCategories().first { it.rateBp == 1300 }.id
    val exempt get() = m.vatCategories().first { it.kind == VatKind.EXEMPT }.id

    fun d(bs: String): LocalDate = BsDate.parse(bs).toAd()

    fun product(name: String, openingQty: Long = 0, openingValue: Long = 0, unit: Long = pcs, vat: Long = vat13, price: Long = 0, min: Long = 0): Long =
        m.saveProduct(null, m.nextProductCode(), name, "General", unit, vat, price, openingQty, openingValue, min)

    fun purchase(date: String, supplier: String, vararg lines: LineInput, bill: String = "", vatClass: VatClass = VatClass.NONE, pan: String = "123456789"): PostResult =
        books.docs.post(DocInput(VoucherType.PURCHASE, d(date), supplier, pan, refNo = bill, vatClass = vatClass, lines = lines.toList()))

    fun sale(date: String, customer: String, vararg lines: LineInput, allowNeg: Boolean = false, token: String? = null): PostResult =
        books.docs.post(DocInput(VoucherType.SALE, d(date), customer, lines = lines.toList(), allowNegativeStock = allowNeg, postToken = token))

    fun expense(date: String, cat: String, amount: Long, vat: Long, cls: VatClass, payee: String = "", bill: String = ""): PostResult =
        books.docs.post(DocInput(VoucherType.EXPENSE, d(date), payee, if (payee.isEmpty()) "" else "987654321", refNo = bill, vatClass = cls,
            expenseCategoryId = m.expenseCategories().first { it.name == cat }.id, description = "$cat bill", expenseAmount = amount, expenseVat = vat))

    fun L(pid: Long, qty: Long, rate: Long, disc: Long = 0) = LineInput(pid, qty * 1000, rate, disc)

    fun bal(key: String, asOf: LocalDate? = null) = books.reports.accountBalance(m.accountId(key), asOf)
    fun stock(pid: Long) = books.costing.balanceAt(pid, null)

    fun assertBalanced() {
        val tb = books.reports.trialBalanceLines(null)
        T.eq(tb.sumOf { it.debit }, tb.sumOf { it.credit }, "trial balance")
        val un = db.query("SELECT journal_id FROM journal_line GROUP BY journal_id HAVING SUM(debit) <> SUM(credit)", arrayOf()) { it.long("journal_id") }
        T.eq(emptyList<Long>(), un, "unbalanced journals")
        val bs = books.reports.balanceSheetData(LocalDate.of(2099, 1, 1).let { if (it.isAfter(np.psy.core.cal.BsCalendar.maxAd)) np.psy.core.cal.BsCalendar.maxAd else it })
        T.ok(bs.balanced, "balance sheet balanced: ${bs.totalAssets} vs ${bs.totalLiabilities + bs.totalEquity}")
        // inventory ledger = sum of stock values
        val inv = m.products().sumOf { stock(it.id).second }
        T.eq(inv, bal("INVENTORY"), "inventory ledger vs stock valuation")
    }
}
