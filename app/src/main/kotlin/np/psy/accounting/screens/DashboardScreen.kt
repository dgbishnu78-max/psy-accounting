package np.psy.accounting.screens

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import np.psy.accounting.App
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.model.VoucherType
import java.time.LocalDate
import java.time.LocalDateTime

class DashboardScreen(act: MainActivity) : Screen(act) {
    override val title get() = books.settings.companyName.ifEmpty { "P.S.Y. Accounts" }
    override val actions get() = listOf<Pair<String, () -> Unit>>(
        "Back up now" to { BackupScreen.backupNow(act) { refresh() } },
        "Settings" to { push(SettingsScreen(act)) },
        "About" to { ctx.info("About", "P.S.Y. Accounts ${App.version}\nOffline accounting for ${books.settings.companyName}.\n\nAll data is stored only on this phone. Keep exported backups in a safe place.") },
    )

    override fun build(): View {
        val b = books
        val box = ctx.vbox(12)
        val s = b.settings
        if (s.get("vat_registration_status") == "NOT_SET" || s.companyPan.isEmpty())
            box.warning("Company setup is incomplete: please enter the PAN/VAT number and VAT registration status in Settings > Company.").setOnClickListener { push(CompanySettingsScreen(act)) }

        val today = b.today()
        box.text("${bsLong(today)} BS", 18f, true, C.PRIMARY_DARK)
        box.note("$today AD  |  ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}")

        // Quick actions
        val q = ctx.hbox()
        q.button("+ Sale") { push(DocEntryScreen(act, VoucherType.SALE)) }
        q.button("+ Purchase") { push(DocEntryScreen(act, VoucherType.PURCHASE)) }
        q.button("+ Expense") { push(ExpenseEntryScreen(act)) }
        box.addView(q, lp(top = ctx.dp(6)))

        // Figures
        try {
            val d = b.reports.dashboard.data(today)
            val g = GridLayout(ctx).apply { columnCount = 2 }
            fun tile(label: String, value: String, sub: String = "", warn: Boolean = false, onTap: (() -> Unit)? = null) {
                val t = ctx.vbox(10)
                t.background = roundBg(if (warn) C.WARN_BG else C.CARD, ctx.dp(10), 1)
                t.addView(TextView(ctx).apply { text = label; textSize = 12f; setTextColor(C.MUTED) })
                t.addView(TextView(ctx).apply { text = value; textSize = 17f; setTextColor(if (warn) C.WARN else C.TEXT); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                if (sub.isNotEmpty()) t.addView(TextView(ctx).apply { text = sub; textSize = 11f; setTextColor(C.MUTED) })
                if (onTap != null) t.setOnClickListener { safely(ctx) { onTap() } }
                val p = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                p.width = 0; p.setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
                g.addView(t, p)
            }
            tile("Today's sales (excl. VAT)", money(d.todaySales), "${d.todayInvoices} invoice(s)") { push(DocListScreen(act, DocListScreen.SALES)) }
            tile("Sales this month", money(d.monthSales), d.monthLabel)
            tile("Gross profit this month", money(d.monthGrossProfit), d.monthLabel, d.monthGrossProfit < 0)
            tile("Net profit this month", money(d.monthNetProfit), "after expenses", d.monthNetProfit < 0)
            tile("Expenses this month", money(d.monthExpenses), d.monthLabel) { push(DocListScreen(act, DocListScreen.EXPENSES)) }
            tile("Stock value", money(d.inventoryValue), "at weighted average cost") { push(ProductListScreen(act)) }
            tile(if (d.vatNet >= 0) "VAT payable this month" else "VAT credit this month", money(Math.abs(d.vatNet)), "output ${money(d.vatOutput)} - input ${money(d.vatInputEligible)}") {
                val (a, e) = b.reports.bsMonthRange(np.psy.core.cal.BsDate.fromAd(today).year, np.psy.core.cal.BsDate.fromAd(today).month)
                act.working("Preparing VAT report...", { listOf(b.reports.vat.summaryReport(a, e), b.reports.vat.salesBook(a, e), b.reports.vat.purchaseBook(a, e)) }) { push(ReportScreen(act, it, a to e)) }
            }
            tile("Financial year", d.fyName, d.fyStatus) { push(FiscalYearScreen(act)) }
            box.addView(g, lp(top = ctx.dp(8)))
            if (d.negativeStockCount > 0) box.warning("${d.negativeStockCount} product(s) have NEGATIVE stock. Enter the missing purchases or a stock adjustment.").setOnClickListener { push(ProductListScreen(act)) }
            if (d.lowStock.isNotEmpty()) box.card {
                text("Low stock", 15f, true, C.WARN)
                d.lowStock.take(8).forEach { note(it) }
                if (d.lowStock.size > 8) note("...and ${d.lowStock.size - 8} more")
            }
            if (d.pendingVatCount > 0) box.note("${d.pendingVatCount} bill(s) have VAT marked 'pending verification'.")
        } catch (e: Exception) {
            box.warning("Could not calculate the summary: ${e.message}")
        }

        // Backup status
        val st = App.backups.status()
        box.card {
            text("Backup", 15f, true)
            val last = st.lastSuccessAt
            if (last == null) warning("No backup has been made yet. Tap 'Back up now'.")
            else {
                val at = try { LocalDateTime.parse(last) } catch (e: Exception) { null }
                val old = at == null || at.toLocalDate().isBefore(LocalDate.now().minusDays(1))
                val label = "Last backup: " + (at?.let { "${bs(it.toLocalDate())} BS ${"%02d:%02d".format(it.hour, it.minute)}" } ?: last)
                if (old) warning("$label - more than a day ago") else note(label)
            }
            if (st.lastError != null) warning("Last automatic backup failed: ${st.lastError}")
            button("Backup and restore", false) { push(BackupScreen(act)) }
        }

        box.heading("Menu")
        val menu = listOf<Triple<String, String, () -> Unit>>(
            Triple("Sales", "Invoices and sales returns") { push(DocListScreen(act, DocListScreen.SALES)) },
            Triple("Purchases", "Purchase bills and purchase returns") { push(DocListScreen(act, DocListScreen.PURCHASES)) },
            Triple("Expenses", "Rent, electricity, transport...") { push(DocListScreen(act, DocListScreen.EXPENSES)) },
            Triple("Products and stock", "Items, units, stock, adjustments") { push(ProductListScreen(act)) },
            Triple("Customers and suppliers", "Names, PAN, special prices") { push(PartyListScreen(act)) },
            Triple("Reports", "Profit, VAT, stock, ledgers") { push(ReportsScreen(act)) },
            Triple("Accounting", "Accounts, journals, opening balances") { push(AccountingScreen(act)) },
            Triple("Financial year", "Year-end closing and reopening") { push(FiscalYearScreen(act)) },
            Triple("Backup and restore", "Daily backups, export, restore") { push(BackupScreen(act)) },
            Triple("Settings", "Company, VAT, numbering, calendar") { push(SettingsScreen(act)) },
        )
        for ((t, sub, f) in menu) {
            val row = ctx.vbox(12)
            row.background = roundBg(C.CARD, ctx.dp(10), 1)
            row.addView(TextView(ctx).apply { text = t; textSize = 16f; setTextColor(C.PRIMARY_DARK); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            row.addView(TextView(ctx).apply { text = sub; textSize = 13f; setTextColor(C.MUTED) })
            row.setOnClickListener { safely(ctx) { f() } }
            box.addView(row, lp(top = ctx.dp(4), bottom = ctx.dp(4)))
        }
        box.note("Data is stored only on this phone. Export a backup regularly to another place.")
        return ctx.scroll(box)
    }
}
