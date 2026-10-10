package np.psy.core.report

import np.psy.core.Books
import np.psy.core.cal.BsDate
import np.psy.core.db.long
import java.time.LocalDate

class Dashboard(private val b: Books) {
    data class Data(
        val today: LocalDate, val todaySales: Long, val todayInvoices: Long, val monthSales: Long, val monthExpenses: Long,
        val monthGrossProfit: Long, val monthNetProfit: Long, val inventoryValue: Long, val lowStock: List<String>,
        val vatOutput: Long, val vatInputEligible: Long, val vatNet: Long, val fyName: String, val fyStatus: String,
        val monthLabel: String, val pendingVatCount: Long, val negativeStockCount: Int,
    )

    fun data(today: LocalDate = b.today()): Data {
        val bs = BsDate.fromAd(today)
        val mStart = bs.firstOfMonth().toAd(); val mEnd = bs.lastOfMonth().toAd()
        val db = b.db
        val todaySales = db.long("SELECT COALESCE(SUM(taxable),0) FROM voucher WHERE type='SALE' AND status='POSTED' AND date = ?", today.toString()) -
            db.long("SELECT COALESCE(SUM(taxable),0) FROM voucher WHERE type='SALES_RETURN' AND status='POSTED' AND date = ?", today.toString())
        val todayCount = db.long("SELECT COUNT(*) FROM voucher WHERE type='SALE' AND status='POSTED' AND date = ?", today.toString())
        val p = b.reports.pnl(mStart, mEnd)
        val monthExp = p.opex.values.sum() + p.otherExp.values.sum()
        var inv = 0L
        val low = ArrayList<String>(); var neg = 0
        for (pr in b.masters.products(activeOnly = true)) {
            val (q, v) = b.costing.balanceAt(pr.id, null)
            inv += v
            if (q < 0) neg++
            if (q < pr.minStock || q < 0) low.add("${pr.name}: ${np.psy.core.util.Qty.format(q)} ${b.masters.unit(pr.unitId).name}")
        }
        val vat = b.reports.vat.summary(mStart, mEnd, withCarry = false)
        val fy = b.years.forDate(today)
        val pending = db.long("SELECT COUNT(*) FROM voucher WHERE status='POSTED' AND vat_class='PENDING'")
        return Data(today, todaySales, todayCount, p.netSales, monthExp, p.grossProfit, p.netProfit, inv, low, vat.netOutputVat, vat.eligibleInputVat,
            vat.netForPeriod, fy.name, fy.status.label, "${np.psy.core.cal.BsCalendar.monthName(bs.month)} ${bs.year}", pending, neg)
    }
}
