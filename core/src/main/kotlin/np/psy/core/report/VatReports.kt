package np.psy.core.report

import np.psy.core.Books
import np.psy.core.cal.BsCalendar
import np.psy.core.cal.BsDate
import np.psy.core.db.list
import np.psy.core.model.*
import np.psy.core.util.Money
import java.time.LocalDate

/**
 * Supporting VAT reports for a BS month (or any date range). See docs/VAT_SOURCES.md for the
 * rules used and the points that are not verified. These reports do not file anything.
 */
class VatReports(private val b: Books) {
    private val db get() = b.db

    data class RateRow(val label: String, val value: Long, val vat: Long)
    data class Summary(
        val taxableSales: List<RateRow>, val zeroRatedSales: Long, val exemptSales: Long, val salesVat: Long,
        val returnsTaxable: Long, val returnsExempt: Long, val returnsVat: Long,
        val taxablePurchases: List<RateRow>, val exemptPurchases: Long,
        val purchaseVatEligible: Long, val purchaseVatIneligible: Long, val purchaseVatPending: Long,
        val purchaseReturnsValue: Long, val purchaseReturnsVatEligible: Long, val purchaseReturnsVatOther: Long,
        val expenseValueWithVat: Long, val expenseVatEligible: Long, val expenseVatIneligible: Long, val expenseVatPending: Long,
        val creditBroughtForward: Long,
    ) {
        val netOutputVat get() = salesVat - returnsVat
        val eligibleInputVat get() = purchaseVatEligible + expenseVatEligible - purchaseReturnsVatEligible
        val netForPeriod get() = netOutputVat - eligibleInputVat
        val payableAfterCredit get() = netForPeriod - creditBroughtForward
    }

    private data class LineAgg(val type: String, val kind: String, val rate: Int, val vatClass: String, val taxable: Long, val vat: Long)

    private fun lineAggs(from: LocalDate, to: LocalDate) = db.list("""
        SELECT v.type, l.vat_kind, l.vat_rate_bp, v.vat_class, SUM(l.taxable) AS t, SUM(l.vat) AS x
        FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id
        WHERE v.status = 'POSTED' AND v.date >= ? AND v.date <= ? AND v.type IN ('SALE','SALES_RETURN','PURCHASE','PURCHASE_RETURN')
        GROUP BY v.type, l.vat_kind, l.vat_rate_bp, v.vat_class""", from.toString(), to.toString()) {
        LineAgg(it.string("type"), it.string("vat_kind"), it.int("vat_rate_bp"), it.string("vat_class"), it.long("t"), it.long("x")) }

    fun summary(from: LocalDate, to: LocalDate, withCarry: Boolean = true): Summary {
        val ag = lineAggs(from, to)
        fun rateLabel(bp: Int) = "Taxable at ${Money.format(bp.toLong()).removeSuffix(".00")}%"
        val sales = ag.filter { it.type == "SALE" }
        val ret = ag.filter { it.type == "SALES_RETURN" }
        val pur = ag.filter { it.type == "PURCHASE" }
        val pret = ag.filter { it.type == "PURCHASE_RETURN" }
        val taxableSales = sales.filter { it.kind == "TAXABLE" }.groupBy { it.rate }.map { (r, l) -> RateRow(rateLabel(r), l.sumOf { it.taxable }, l.sumOf { it.vat }) }
        val taxablePur = pur.filter { it.kind == "TAXABLE" }.groupBy { it.rate }.map { (r, l) -> RateRow(rateLabel(r), l.sumOf { it.taxable }, l.sumOf { it.vat }) }
        val exp = db.list("""SELECT vat_class, SUM(taxable) AS t, SUM(vat) AS x FROM voucher WHERE type = 'EXPENSE' AND status = 'POSTED' AND vat > 0
            AND date >= ? AND date <= ? GROUP BY vat_class""", from.toString(), to.toString()) { Triple(it.string("vat_class"), it.long("t"), it.long("x")) }
        val carry = if (withCarry) creditBroughtForward(from) else 0L
        return Summary(
            taxableSales, sales.filter { it.kind == "ZERO_RATED" }.sumOf { it.taxable }, sales.filter { it.kind == "EXEMPT" }.sumOf { it.taxable }, sales.sumOf { it.vat },
            ret.filter { it.kind != "EXEMPT" }.sumOf { it.taxable }, ret.filter { it.kind == "EXEMPT" }.sumOf { it.taxable }, ret.sumOf { it.vat },
            taxablePur, pur.filter { it.kind != "TAXABLE" }.sumOf { it.taxable },
            pur.filter { it.vatClass == "ELIGIBLE" }.sumOf { it.vat }, pur.filter { it.vatClass == "INELIGIBLE" }.sumOf { it.vat }, pur.filter { it.vatClass == "PENDING" }.sumOf { it.vat },
            pret.sumOf { it.taxable }, pret.filter { it.vatClass == "ELIGIBLE" }.sumOf { it.vat }, pret.filter { it.vatClass != "ELIGIBLE" }.sumOf { it.vat },
            exp.sumOf { it.second }, exp.filter { it.first == "ELIGIBLE" }.sumOf { it.third }, exp.filter { it.first == "INELIGIBLE" }.sumOf { it.third },
            exp.filter { it.first == "PENDING" }.sumOf { it.third }, carry)
    }

    /**
     * Excess input credit carried into the month starting [from], calculated only from this
     * app's records (month by month since the books start). It does not know about refunds,
     * assessments or what was actually filed.
     */
    fun creditBroughtForward(from: LocalDate): Long {
        val start = b.years.booksStart()
        if (!from.isAfter(start)) return 0L
        var carry = 0L
        var m = BsDate.fromAd(start).firstOfMonth()
        val stop = BsDate.fromAd(from)
        var guard = 0
        while ((m.year < stop.year || (m.year == stop.year && m.month < stop.month)) && guard++ < 1300) {
            val s = summary(m.toAd(), m.lastOfMonth().toAd(), withCarry = false)
            val payable = s.netForPeriod - carry
            carry = if (payable < 0) -payable else 0L
            m = m.lastOfMonth().plusDays(1)
        }
        return carry
    }

    fun summaryReport(from: LocalDate, to: LocalDate): ReportTable {
        val s = summary(from, to)
        val rows = ArrayList<ReportRow>()
        fun r(label: String, value: Long?, vat: Long?, style: RowStyle = RowStyle.NORMAL) = rows.add(ReportRow(listOf(label, value?.let { Amt(it) }, vat?.let { Amt(it) }), style))
        rows.add(ReportRow(listOf("A. Sales", null, null), RowStyle.SECTION))
        s.taxableSales.forEach { r("Taxable sales - ${it.label.removePrefix("Taxable at ")}", it.value, it.vat) }
        if (s.taxableSales.isEmpty()) r("Taxable sales", 0, 0)
        r("Zero-rated sales (exports)", s.zeroRatedSales, 0)
        r("Exempt sales", s.exemptSales, null)
        r("Less: sales returns / credit notes (taxable)", -s.returnsTaxable, -s.returnsVat)
        if (s.returnsExempt != 0L) r("Less: sales returns (exempt)", -s.returnsExempt, null)
        r("Net output VAT", null, s.netOutputVat, RowStyle.SUBTOTAL)
        rows.add(ReportRow(listOf("B. Purchases (stock)", null, null), RowStyle.SECTION))
        s.taxablePurchases.forEach { r("Taxable purchases - ${it.label.removePrefix("Taxable at ")}", it.value, it.vat) }
        if (s.taxablePurchases.isEmpty()) r("Taxable purchases", 0, 0)
        r("Exempt / zero-rated purchases", s.exemptPurchases, null)
        r("   of which VAT eligible for credit", null, s.purchaseVatEligible)
        r("   of which VAT ineligible (added to stock cost)", null, s.purchaseVatIneligible)
        r("   of which VAT pending verification", null, s.purchaseVatPending)
        r("Less: purchase returns / debit notes", -s.purchaseReturnsValue, -(s.purchaseReturnsVatEligible + s.purchaseReturnsVatOther))
        rows.add(ReportRow(listOf("C. Expenses with VAT", null, null), RowStyle.SECTION))
        r("Expenses (amount before VAT)", s.expenseValueWithVat, s.expenseVatEligible + s.expenseVatIneligible + s.expenseVatPending)
        r("   of which VAT eligible for credit", null, s.expenseVatEligible)
        r("   of which VAT ineligible (charged to expense)", null, s.expenseVatIneligible)
        r("   of which VAT pending verification", null, s.expenseVatPending)
        rows.add(ReportRow(listOf("D. Result", null, null), RowStyle.SECTION))
        r("Eligible input VAT (B + C eligible, less purchase returns)", null, s.eligibleInputVat, RowStyle.SUBTOTAL)
        r("Net VAT for the period (output less eligible input)", null, s.netForPeriod)
        r("Credit brought forward from earlier months (calculated from app records)", null, -s.creditBroughtForward)
        val p = s.payableAfterCredit
        r(if (p >= 0) "VAT payable for the period" else "Excess credit to carry forward", null, Math.abs(p), RowStyle.TOTAL)
        val bsTo = BsDate.fromAd(to)
        val due = bsTo.plusDays(25)
        val notes = arrayListOf(
            "Prepared from posted documents for review. This app does not file VAT returns and does not guarantee compliance. Check with your tax adviser.",
            "Pending-verification VAT is NOT counted as input credit until you mark it eligible.",
            "Return/payment due date reminder: within 25 days after the end of the tax period (about $due BS for a period ending $bsTo BS), per VAT Act s.18-19 as read on 2026-10-10. Verify the current rule.",
            "Credit brought forward is calculated only from this app's records; it does not know about refunds or what was actually filed.",
        )
        val warnings = ArrayList<String>()
        if (s.purchaseVatPending + s.expenseVatPending > 0) warnings.add("Some input VAT (${Money.format(s.purchaseVatPending + s.expenseVatPending)}) is pending verification.")
        return ReportTable("VAT Summary", b.reports.period(from, to), listOf(Col("Particulars", weight = 3.5f), Col("Value (NPR)", Align.RIGHT, 1.3f), Col("VAT (NPR)", Align.RIGHT, 1.3f)), rows, notes, warnings)
    }

    private fun goods(vid: Long): Triple<String, String, String> {
        val ls = b.docs.lines(vid)
        val units = b.masters.units().associateBy { it.id }
        val names = ls.mapNotNull { it.productId?.let { p -> b.masters.product(p).name } }.distinct()
        val unitIds = ls.mapNotNull { it.unitId }.distinct()
        val qty = if (unitIds.size == 1) np.psy.core.util.Qty.format(ls.sumOf { it.qty }) else ""
        val unit = if (unitIds.size == 1) units[unitIds[0]]?.name ?: "" else "Various"
        return Triple(names.joinToString(", "), qty, unit)
    }

    /** Sales book (bikri khata) style register for the period. */
    fun salesBook(from: LocalDate, to: LocalDate): ReportTable {
        val vs = b.docs.search(listOf(VoucherType.SALE, VoucherType.SALES_RETURN), from, to, includeInactive = true, limit = 100000)
            .filter { it.status != VoucherStatus.SUPERSEDED }.sortedWith(compareBy({ it.date }, { it.seq }))
        val rows = ArrayList<ReportRow>()
        var tot = LongArray(5)
        for (v in vs) {
            val sign = if (v.type == VoucherType.SALES_RETURN) -1 else 1
            val ls = b.docs.lines(v.id)
            val cancelled = v.status == VoucherStatus.CANCELLED
            fun s(x: Long) = if (cancelled) 0L else sign * x
            val total = s(v.total); val exempt = s(ls.filter { it.vatKind == VatKind.EXEMPT }.sumOf { it.taxable })
            val taxable = s(ls.filter { it.vatKind == VatKind.TAXABLE }.sumOf { it.taxable }); val vat = s(v.vat)
            val export = s(ls.filter { it.vatKind == VatKind.ZERO_RATED }.sumOf { it.taxable })
            val (g, q, u) = goods(v.id)
            rows.add(ReportRow(listOf(b.reports.bs(v.date), v.number + (if (v.type == VoucherType.SALES_RETURN) " (CN)" else "") + (if (cancelled) " CANCELLED" else ""),
                v.partyName, v.partyPan, g, q, u, Amt(total), Amt(exempt, true), Amt(taxable, true), Amt(vat, true), Amt(export, true)),
                if (cancelled) RowStyle.MUTED else RowStyle.NORMAL, "voucher:${v.id}"))
            tot[0] += total; tot[1] += exempt; tot[2] += taxable; tot[3] += vat; tot[4] += export
        }
        rows.add(ReportRow(listOf("", "Total", "", "", "", "", "", Amt(tot[0]), Amt(tot[1]), Amt(tot[2]), Amt(tot[3]), Amt(tot[4])), RowStyle.TOTAL))
        return ReportTable("Sales Book", b.reports.period(from, to), listOf(Col("Date (BS)", weight = 1f), Col("Invoice No.", weight = 1.5f), Col("Buyer", weight = 1.8f), Col("Buyer PAN", weight = 1f),
            Col("Goods/Services", weight = 2f), Col("Qty", Align.RIGHT, 0.7f), Col("Unit", weight = 0.6f), Col("Total sales", Align.RIGHT, 1.1f), Col("Exempt", Align.RIGHT),
            Col("Taxable value", Align.RIGHT, 1.1f), Col("VAT", Align.RIGHT), Col("Export", Align.RIGHT)), rows,
            listOf("Layout modelled on the IRD sales book. Credit notes (sales returns) are shown as negative amounts; cancelled invoices are listed with zero amounts.",
                "Compare with the current official IRD format before use (see VAT sources document)."), landscape = true)
    }

    /** Purchase book (kharid khata) style register: stock purchases, purchase returns and expense bills. */
    fun purchaseBook(from: LocalDate, to: LocalDate): ReportTable {
        val vs = b.docs.search(listOf(VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN, VoucherType.EXPENSE), from, to, includeInactive = false, limit = 100000)
            .sortedWith(compareBy({ it.date }, { it.seq }))
        val rows = ArrayList<ReportRow>()
        val tot = LongArray(4)
        for (v in vs) {
            val sign = if (v.type == VoucherType.PURCHASE_RETURN) -1 else 1
            val (g, q, u) = if (v.type == VoucherType.EXPENSE) Triple(v.description, "", "") else goods(v.id)
            val exempt = if (v.type == VoucherType.EXPENSE) (if (v.vat == 0L) v.taxable else 0L) else b.docs.lines(v.id).filter { it.vatKind != VatKind.TAXABLE }.sumOf { it.taxable }
            val taxable = v.taxable - exempt
            rows.add(ReportRow(listOf(b.reports.bs(v.date), v.refNo, v.number + if (v.type == VoucherType.PURCHASE_RETURN) " (DN)" else "", v.partyName, v.partyPan, g, q, u,
                Amt(sign * v.total), Amt(sign * exempt, true), Amt(sign * taxable, true), Amt(sign * v.vat, true), if (v.vat != 0L) v.vatClass.label else ""), link = "voucher:${v.id}"))
            tot[0] += sign * v.total; tot[1] += sign * exempt; tot[2] += sign * taxable; tot[3] += sign * v.vat
        }
        rows.add(ReportRow(listOf("", "", "Total", "", "", "", "", "", Amt(tot[0]), Amt(tot[1]), Amt(tot[2]), Amt(tot[3]), ""), RowStyle.TOTAL))
        return ReportTable("Purchase Book", b.reports.period(from, to), listOf(Col("Date (BS)"), Col("Supplier Inv.", weight = 1.1f), Col("Our No.", weight = 1.4f), Col("Supplier", weight = 1.8f),
            Col("PAN"), Col("Goods/Services", weight = 2f), Col("Qty", Align.RIGHT, 0.7f), Col("Unit", weight = 0.6f), Col("Total", Align.RIGHT, 1.1f), Col("Exempt", Align.RIGHT),
            Col("Taxable value", Align.RIGHT, 1.1f), Col("VAT", Align.RIGHT), Col("VAT class", weight = 1.3f)), rows,
            listOf("Includes stock purchases, purchase returns (debit notes, negative) and expense bills. Only VAT marked eligible is claimed as input credit in the VAT Summary.",
                "Compare with the current official IRD format before use (see VAT sources document)."), landscape = true)
    }
}
