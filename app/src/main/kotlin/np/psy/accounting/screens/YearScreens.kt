package np.psy.accounting.screens

import android.app.AlertDialog
import android.view.View
import np.psy.accounting.App
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.db.UserError
import np.psy.core.model.FyStatus

class FiscalYearScreen(act: MainActivity) : Screen(act) {
    override val title = "Financial years"
    override fun build(): View {
        val b = books
        b.years.current()
        val box = ctx.vbox(12)
        box.note("Nepal's financial year runs from 1 Shrawan to the last day of Asar. Closing a year moves its profit or loss to Retained Earnings and locks the year so it cannot be changed by mistake. A closed year can be reopened with a reason.")
        for (y in b.years.all().reversed()) box.card {
            text("FY ${y.name}", 17f, true, C.PRIMARY_DARK)
            kv("Period", "${bs(y.start)} to ${bs(y.end)} BS")
            note("${y.start} to ${y.end} AD")
            kv("Status", y.status.label, true, if (y.status == FyStatus.CLOSED) C.MUTED else C.PRIMARY_DARK)
            button("Open", false) { push(YearDetailScreen(act, y.id)) }
        }
        val hist = b.yearEnd.history()
        if (hist.isNotEmpty()) {
            box.heading("Closing history")
            for (h in hist) box.note("FY ${h["year"]}: closed ${h["closed_at"]}, net ${money(h["net_profit"] as Long)}" +
                (h["reopened_at"]?.let { "; reopened $it (${h["reason"]})" } ?: ""))
        }
        return ctx.scroll(box)
    }
}

class YearDetailScreen(act: MainActivity, private val fyId: Long) : Screen(act) {
    override val title get() = "FY ${books.years.get(fyId).name}"
    override fun build(): View {
        val b = books
        val fy = b.years.get(fyId)
        val box = ctx.vbox(12)
        box.text("FY ${fy.name}: ${fy.status.label}", 18f, true, C.PRIMARY_DARK)
        box.note("${bsAd(fy.start)} to ${bsAd(fy.end)}")
        val s = try { b.yearEnd.summary(fyId) } catch (e: Exception) { box.warning("Could not prepare the year summary: ${e.message}"); null }
        if (s != null) {
            box.card {
                text("Results", 15f, true)
                kv("Net sales", money(s.pnl.netSales)); kv("Cost of goods sold", money(s.pnl.totalCogs))
                kv("Gross profit", money(s.pnl.grossProfit))
                kv(if (s.pnl.netProfit >= 0) "Net profit" else "Net loss", money(Math.abs(s.pnl.netProfit)), true, if (s.pnl.netProfit < 0) C.WARN else C.TEXT)
                kv("Closing stock value", money(s.inventoryValue))
                kv("VAT: output / eligible input", "${money(s.vat.netOutputVat)} / ${money(s.vat.eligibleInputVat)}")
                kv("Trial balance debit / credit", "${money(s.tbDebit)} / ${money(s.tbCredit)}")
            }
            box.heading("Checks before closing")
            if (s.checks.isEmpty()) box.note("No problems found.")
            for (c in s.checks) if (c.critical) box.warning("Must fix: ${c.message}") else box.note("Note: ${c.message}")
        }
        val r = ctx
        when (fy.status) {
            FyStatus.OPEN -> if (s != null) {
                val btn = box.button("Close this financial year") { close(fy.name) }
                if (!s.canClose) { btn.isEnabled = false; btn.alpha = 0.5f; box.note("The year can be closed after the 'Must fix' items above are resolved.") }
            }
            FyStatus.CLOSED -> {
                box.button("Closing report") { act.working("Preparing...", { b.yearEnd.closingReport(fyId) }) { push(ReportScreen(act, it, fy.start to fy.end)) } }
                box.button("Reopen this year", false) { reopen(fy.name) }
            }
            FyStatus.CLOSING -> box.warning("Closing is in progress. If this stays, restart the app; an interrupted closing is undone automatically.")
        }
        box.button("Profit and Loss for this year", false) { ReportsScreen.runReport(act, ReportKind.PNL, fy.start, fy.end, null, null, null) }
        box.button("Balance Sheet at year-end", false) { ReportsScreen.runReport(act, ReportKind.BS, null, fy.end, null, null, null) }
        b.yearEnd.closingRecord(fyId)?.let { rec ->
            box.heading("Closing record")
            box.note("Status: ${rec["status"]}. Closed at ${rec["closed_at"]}. Safety backup: ${rec["backup_file"]}")
            rec["reopened_at"]?.let { box.note("Reopened at $it. Reason: ${rec["reopen_reason"]}. Safety backup: ${rec["reopen_backup_file"]}") }
        }
        @Suppress("UNUSED_VARIABLE") val unused = r
        return ctx.scroll(box)
    }

    private fun close(name: String) {
        val d = ctx.vbox(16)
        d.note("Before closing, the app makes a full safety backup and checks it. Then, in one step, it:\n- moves all income and expenses for FY $name into Retained Earnings,\n- locks FY $name so no entries can be added, edited or cancelled in it.\n\nBalances carry forward to the next year automatically. You can reopen the year later with a reason.")
        val check = d.check("I have entered all transactions for FY $name and checked the reports.", false)
        AlertDialog.Builder(ctx).setTitle("Close FY $name?").setView(ctx.scroll(d)).setPositiveButton("Close the year") { _, _ ->
            safely(ctx) {
                if (!check.isChecked) throw UserError("Tick the confirmation box to close the year")
                act.working("Making safety backup and closing the year...", { books.yearEnd.close(fyId, true) { App.safetyBackup("before-closing-${name.replace('/', '-')}") } }) {
                    ctx.info("Year closed", "FY $name is now closed. A safety backup was made before closing. You can view the closing report now.") { refresh() }
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun reopen(name: String) {
        val d = ctx.vbox(16)
        d.note("Reopening reverses the closing entry and unlocks FY $name so corrections can be made. A safety backup is made first. Close the year again after the corrections.")
        val reason = d.field("Reason for reopening", "", lines = 2)
        AlertDialog.Builder(ctx).setTitle("Reopen FY $name?").setView(d).setPositiveButton("Reopen") { _, _ ->
            safely(ctx) {
                val rs = reason.str
                if (rs.length < 5) throw UserError("Enter the reason for reopening")
                act.working("Making safety backup and reopening...", { books.yearEnd.reopen(fyId, rs, true) { App.safetyBackup("before-reopening-${name.replace('/', '-')}") } }) {
                    ctx.info("Year reopened", "FY $name is open again. Remember to close it again after making the corrections.") { refresh() }
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }
}
