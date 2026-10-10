package np.psy.accounting.screens

import android.view.View
import android.widget.TextView
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.cal.BsDate
import np.psy.core.model.VoucherType
import np.psy.core.report.ReportTable
import java.time.LocalDate

/** Every report the app offers, and which inputs it needs. */
enum class ReportKind(val label: String, val group: String, val needs: String) {
    PNL("Profit and Loss", "Profit", "range"),
    BILL_PROFIT("Bill-wise profit", "Profit", "range,party"),
    PRODUCT_PROFIT("Product-wise profit", "Profit", "range,product?"),
    VAT("VAT monthly report (summary + sales book + purchase book)", "VAT", "month"),
    VAT_SUMMARY("VAT summary only", "VAT", "month"),
    SALES_BOOK("Sales book (VAT)", "VAT", "month"),
    PURCHASE_BOOK("Purchase book (VAT)", "VAT", "month"),
    SALES_REG("Sales register", "Registers", "range"),
    SALES_RET_REG("Sales returns register", "Registers", "range"),
    PURCHASE_REG("Purchase register", "Registers", "range"),
    PURCHASE_RET_REG("Purchase returns register", "Registers", "range"),
    EXPENSE_REG("Expense register", "Registers", "range"),
    ADJ_REG("Stock adjustment register", "Registers", "range"),
    STOCK("Stock on hand", "Stock", "asof"),
    LOW_STOCK("Low and negative stock", "Stock", "asof"),
    VALUATION("Inventory valuation", "Stock", "asof"),
    MOVEMENT("Stock movement of one product", "Stock", "range,product"),
    TB("Trial balance", "Accounts", "asof"),
    BS("Balance sheet", "Accounts", "asof"),
    LEDGER("Account ledger", "Accounts", "range,account"),
    GL("General ledger (all accounts)", "Accounts", "range"),
    JOURNALS("Journal register (day book)", "Accounts", "range"),
    AUDIT("Audit trail (all changes)", "Other", ""),
}

class ReportsScreen(act: MainActivity) : Screen(act) {
    override val title = "Reports"
    override fun build(): View {
        val box = ctx.vbox(12)
        for ((g, kinds) in ReportKind.values().groupBy { it.group }) {
            box.heading(g)
            for (k in kinds) {
                val row = ctx.vbox(12)
                row.background = roundBg(C.CARD, ctx.dp(8), 1)
                row.addView(TextView(ctx).apply { text = k.label; textSize = 15f; setTextColor(C.TEXT) })
                row.setOnClickListener { safely(ctx) { if (k.needs.isEmpty()) run(k, null, null, null, null, null) else push(ReportParamScreen(act, k)) } }
                box.addView(row, lp(top = ctx.dp(3), bottom = ctx.dp(3)))
            }
        }
        box.note("VAT reports are prepared from your entries to help you fill in the IRD return. They do not file anything and do not guarantee compliance. Check them before filing.")
        return ctx.scroll(box)
    }

    private fun run(k: ReportKind, from: LocalDate?, to: LocalDate?, a: Long?, p: Long?, party: Long?) = runReport(act, k, from, to, a, p, party)

    companion object {
        fun runReport(act: MainActivity, k: ReportKind, from: LocalDate?, to: LocalDate?, accountId: Long?, productId: Long?, partyId: Long?) {
            val b = np.psy.accounting.App.books()
            act.working("Preparing report...", {
                val f = from ?: b.today(); val t = to ?: b.today()
                val r = b.reports
                val tables: List<ReportTable> = when (k) {
                    ReportKind.PNL -> listOf(r.profitAndLoss(f, t))
                    ReportKind.BILL_PROFIT -> listOf(r.billWiseProfit(f, t, partyId))
                    ReportKind.PRODUCT_PROFIT -> listOf(r.productWiseProfit(f, t, productId))
                    ReportKind.VAT -> listOf(r.vat.summaryReport(f, t), r.vat.salesBook(f, t), r.vat.purchaseBook(f, t))
                    ReportKind.VAT_SUMMARY -> listOf(r.vat.summaryReport(f, t))
                    ReportKind.SALES_BOOK -> listOf(r.vat.salesBook(f, t))
                    ReportKind.PURCHASE_BOOK -> listOf(r.vat.purchaseBook(f, t))
                    ReportKind.SALES_REG -> listOf(r.register(VoucherType.SALE, f, t))
                    ReportKind.SALES_RET_REG -> listOf(r.register(VoucherType.SALES_RETURN, f, t))
                    ReportKind.PURCHASE_REG -> listOf(r.register(VoucherType.PURCHASE, f, t))
                    ReportKind.PURCHASE_RET_REG -> listOf(r.register(VoucherType.PURCHASE_RETURN, f, t))
                    ReportKind.EXPENSE_REG -> listOf(r.register(VoucherType.EXPENSE, f, t))
                    ReportKind.ADJ_REG -> listOf(r.register(VoucherType.STOCK_ADJUSTMENT, f, t))
                    ReportKind.STOCK -> listOf(r.stockOnHand(t))
                    ReportKind.LOW_STOCK -> listOf(r.stockOnHand(t, lowOnly = true, title = "Low and Negative Stock"))
                    ReportKind.VALUATION -> listOf(r.inventoryValuation(t))
                    ReportKind.MOVEMENT -> listOf(r.stockMovement(productId!!, f, t))
                    ReportKind.TB -> listOf(r.trialBalance(t))
                    ReportKind.BS -> listOf(r.balanceSheet(t))
                    ReportKind.LEDGER -> listOf(r.accountLedger(accountId!!, f, t))
                    ReportKind.GL -> listOf(r.generalLedger(f, t))
                    ReportKind.JOURNALS -> listOf(r.journalRegister(f, t))
                    ReportKind.AUDIT -> listOf(r.auditTrail())
                }
                tables
            }) { act.push(ReportScreen(act, it, if (from != null && to != null) from to to else b.years.current().let { y -> y.start to y.end })) }
        }
    }
}

class ReportParamScreen(act: MainActivity, private val k: ReportKind) : Screen(act) {
    override val title get() = k.label
    private var root: View? = null
    override fun onReturn() {}
    override fun build(): View = root ?: create().also { root = it }

    private fun create(): View {
        val b = books
        val box = ctx.vbox(12)
        box.text(k.label, 17f, true, C.PRIMARY_DARK)
        val today = b.today()
        val needs = k.needs.split(",").map { it.trim() }
        var period: PeriodPicker? = null
        var asOf: BsDateField? = null
        when {
            "month" in needs -> {
                box.note("VAT returns are monthly. 'This month' and 'Month...' choose a whole BS month.")
                val bsT = BsDate.fromAd(today)
                val prev = bsT.firstOfMonth().plusDays(-1)
                period = PeriodPicker(box, b, prev.firstOfMonth().toAd(), prev.lastOfMonth().toAd())
            }
            "range" in needs -> {
                val bsT = BsDate.fromAd(today)
                period = PeriodPicker(box, b, bsT.firstOfMonth().toAd(), today)
            }
            "asof" in needs -> asOf = BsDateField(box, "As at", today)
        }
        val account = if ("account" in needs) {
            val accs = b.masters.accounts()
            box.choice("Account", accs, accs.firstOrNull { it.systemKey == "CASH" }) { it.label }
        } else null
        val product = if (needs.any { it.startsWith("product") }) {
            val ps = b.masters.products()
            val optional = "product?" in needs
            val items: List<np.psy.core.model.Product?> = (if (optional) listOf<np.psy.core.model.Product?>(null) else emptyList()) + ps
            box.choice("Product", items, items.firstOrNull()) { it?.let { p -> "${p.name} [${p.code}]" } ?: "All products" }
        } else null
        val party = if ("party" in needs) {
            val ps: List<np.psy.core.model.Party?> = listOf<np.psy.core.model.Party?>(null) + b.masters.parties(customers = true)
            box.choice("Customer", ps, null) { it?.name ?: "All customers" }
        } else null

        box.button("Show report") {
            val (f, t) = period?.range() ?: asOf?.value()?.let { it to it } ?: (today to today)
            val acc = account?.selected?.id
            if (account != null && acc == null) throw np.psy.core.db.UserError("Choose an account")
            val pid = product?.selected?.id
            if ("product" in needs && pid == null) throw np.psy.core.db.UserError("Choose a product")
            ReportsScreen.runReport(act, k, if (period != null) f else null, t, acc, pid, party?.selected?.id)
        }
        return ctx.scroll(box)
    }
}
