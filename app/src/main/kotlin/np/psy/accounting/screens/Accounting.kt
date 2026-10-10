package np.psy.accounting.screens

import android.app.AlertDialog
import android.view.View
import android.widget.*
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.db.UserError
import np.psy.core.model.*
import np.psy.core.util.Money

class AccountingScreen(act: MainActivity) : Screen(act) {
    override val title = "Accounting"
    override fun build(): View {
        val box = ctx.vbox(12)
        box.note("Sales, purchases, expenses and stock adjustments create their accounting entries automatically. Use journal entries for everything else, for example money received from customers, payments to suppliers, bank deposits, capital, loans and depreciation.")
        box.button("Chart of accounts") { push(AccountsScreen(act)) }
        box.button("Journal entries (manual)") { push(JournalListScreen(act, manualOnly = true)) }
        box.button("New journal entry") { push(JournalEntryScreen(act)) }
        box.button("All accounting entries", false) { push(JournalListScreen(act, manualOnly = false)) }
        box.button("Opening balances and books start date", false) { push(OpeningBalancesScreen(act)) }
        box.button("Expense categories", false) { push(ExpenseCategoriesScreen(act)) }
        box.button("Trial balance (today)", false) { ReportsScreen.runReport(act, ReportKind.TB, null, books.today(), null, null, null) }
        box.button("Balance sheet (today)", false) { ReportsScreen.runReport(act, ReportKind.BS, null, books.today(), null, null, null) }
        return ctx.scroll(box)
    }
}

class AccountsScreen(act: MainActivity) : Screen(act) {
    override val title = "Chart of accounts"
    override val actions get() = listOf<Pair<String, () -> Unit>>("New account" to { edit(null) })
    override fun build(): View {
        val b = books
        val page = ListPage(ctx)
        page.header.button("+ New account") { edit(null) }
        page.header.note("Balances as at today. Tap an account for its ledger or to edit it.")
        val accs = b.masters.accounts()
        val today = b.today()
        page.show(accs, "", { a, _ ->
            val bal = b.reports.accountBalance(a.id, today)
            val shown = if (a.type.debitNormal) bal else -bal
            ctx.listRow(a.label, money(shown), a.type.label + (if (a.systemKey != null) "  |  system account" else "") + if (!a.active) "  |  inactive" else "", if (shown < 0) C.WARN else C.TEXT)
        }) { a ->
            ctx.pick(a.label, listOf("View ledger (this financial year)", "View ledger (choose dates)", "Edit account"), { it }) { c ->
                when (c) {
                    "Edit account" -> edit(a)
                    "View ledger (choose dates)" -> push(ReportParamScreen(act, ReportKind.LEDGER))
                    else -> { val fy = b.years.current(); ReportsScreen.runReport(act, ReportKind.LEDGER, fy.start, fy.end, a.id, null, null) }
                }
            }
        }
        return page.root
    }

    private fun edit(a: Account?) {
        val d = ctx.vbox(16)
        val code = d.field("Code", a?.code ?: "", type = INT)
        val name = d.field("Name", a?.name ?: "", type = CAPS)
        val type = d.choice("Type", AccountType.values().toList(), a?.type ?: AccountType.EXPENSE) { it.label }
        val subtypes = listOf("", "CURRENT", "NON_CURRENT", "OPERATING", "OTHER", "SALES")
        val sub = d.choice("Group (for the balance sheet / P&L layout)", subtypes, a?.subtype ?: "") {
            when (it) { "" -> "(none)"; "CURRENT" -> "Current (assets/liabilities)"; "NON_CURRENT" -> "Non-current (assets/liabilities)"; "OPERATING" -> "Operating expense"; "OTHER" -> "Other income/expense"; else -> "Sales" }
        }
        val active = d.check("Active", a?.active ?: true)
        if (a?.systemKey != null) d.note("This is a system account used automatically by the app. Its type cannot be changed.")
        AlertDialog.Builder(ctx).setTitle(if (a == null) "New account" else "Edit account").setView(ctx.scroll(d)).setPositiveButton("Save") { _, _ ->
            safely(ctx) { books.masters.saveAccount(a?.id, code.str, name.str, type.selected!!, sub.selected ?: "", active.isChecked); refresh() }
        }.setNegativeButton("Cancel", null).show()
    }
}

class JournalListScreen(act: MainActivity, private val manualOnly: Boolean) : Screen(act) {
    override val title = if (manualOnly) "Journal entries" else "All accounting entries"
    override val actions get() = listOf<Pair<String, () -> Unit>>("New journal entry" to { push(JournalEntryScreen(act)) })
    override fun build(): View {
        val b = books
        val page = ListPage(ctx)
        page.header.button("+ New journal entry") { push(JournalEntryScreen(act)) }
        val fy = b.years.current()
        page.header.note("Financial year ${fy.name}. Newest first.")
        val items = b.journals.list(fy.start, fy.end, if (manualOnly) listOf("JOURNAL") else null).sortedWith(compareByDescending<Journal> { it.date }.thenByDescending { it.seq })
        page.show(items, "No entries in this financial year.", { j, _ ->
            val amt = b.journals.lines(j.id).sumOf { it.debit }
            ctx.listRow(j.number, money(amt), "${bs(j.date)} BS  ${j.type.lowercase()}  ${j.narration}", C.TEXT, if (j.status != "POSTED") j.status else "", j.status != "POSTED")
        }) { push(JournalViewScreen(act, it.id)) }
        return page.root
    }
}

class JournalEntryScreen(act: MainActivity) : Screen(act) {
    private class L(var account: Account?, var debit: Long, var credit: Long, var memo: String)
    private val lines = arrayListOf(L(null, 0, 0, ""), L(null, 0, 0, ""))
    private var root: View? = null
    private lateinit var linesBox: LinearLayout
    private lateinit var diff: TextView
    override val title = "New journal entry"
    override fun onReturn() {}
    override fun build(): View = root ?: create().also { root = it }

    private fun create(): View {
        val b = books
        val box = ctx.vbox(12)
        box.note("Every entry must balance: total debits = total credits. Examples:\n- Money received from a customer: Debit Cash/Bank, Credit Trade Receivables.\n- Payment to a supplier: Debit Trade Payables, Credit Cash/Bank.\n- Cash deposited in bank: Debit Bank, Credit Cash.")
        val date = BsDateField(box, "Date", b.today())
        val narr = box.field("Narration (what this entry is for)", "", lines = 2)
        box.heading("Lines")
        linesBox = ctx.vbox(0); box.addView(linesBox, lp())
        box.button("+ Add line", false) { lines.add(L(null, 0, 0, "")); render() }
        diff = box.text("", 15f, true)
        render()
        box.button("Post journal entry") {
            val ls = lines.filter { it.account != null || it.debit != 0L || it.credit != 0L }
            ls.forEach { if (it.account == null) throw UserError("Choose an account on every line") }
            val input = ls.map { JournalLineInput(it.account!!.id, it.debit, it.credit, it.memo) }
            b.journals.validate(input)
            val id = b.journals.postManual(date.value(), narr.str, input)
            ctx.toast("Journal ${b.journals.get(id).number} posted")
            act.replace(JournalViewScreen(act, id))
        }
        return ctx.scroll(box)
    }

    private fun render() {
        val accs = books.masters.accounts(activeOnly = true).filter { it.systemKey != "INVENTORY" }
        linesBox.removeAllViews()
        for ((i, l) in lines.withIndex()) linesBox.card {
            val c = choice("Line ${i + 1}: account", listOf<Account?>(null) + accs, l.account) { it?.label ?: "(choose account)" }
            c.onChange { l.account = it }
            val row = context.hbox()
            val dv = ctx.vbox(0); val cv = ctx.vbox(0)
            row.addView(dv, lp(0, WRAP, 1f)); row.addView(cv, lp(0, WRAP, 1f))
            addView(row, lp())
            val d = dv.field("Debit", moneyInput(l.debit), type = NUM)
            val cr = cv.field("Credit", moneyInput(l.credit), type = NUM)
            d.onText { l.debit = try { Money.parse(it) } catch (e: Exception) { 0 }; upd() }
            cr.onText { l.credit = try { Money.parse(it) } catch (e: Exception) { 0 }; upd() }
            val m = field("Memo (optional)", l.memo)
            m.onText { l.memo = it }
            if (lines.size > 2) button("Remove line", false) { lines.removeAt(i); render() }
        }
        upd()
    }

    private fun upd() {
        val dr = lines.sumOf { it.debit }; val cr = lines.sumOf { it.credit }
        diff.text = "Debits ${money(dr)}  |  Credits ${money(cr)}" + if (dr != cr) "\nDifference ${money(dr - cr)}" else "  |  Balanced"
        diff.setTextColor(if (dr != cr) C.WARN else C.PRIMARY_DARK)
    }
}

class OpeningBalancesScreen(act: MainActivity) : Screen(act) {
    override val title = "Opening balances"
    override fun build(): View {
        val b = books
        val box = ctx.vbox(12)
        val start = b.years.booksStart()
        box.card {
            text("Books start date: ${bsAd(start)}", 15f, true)
            note("Opening balances are the amounts in each account at the start of this date. Opening stock is entered on each product instead.")
            button("Change books start date", false) {
                val d = ctx.vbox(16)
                val f = BsDateField(d, "Books start date", start)
                AlertDialog.Builder(ctx).setTitle("Books start date").setView(d).setPositiveButton("Save") { _, _ ->
                    safely(ctx) { b.years.setBooksStart(f.value()); refresh() }
                }.setNegativeButton("Cancel", null).show()
            }
        }
        val existing = b.journals.openingJournal()?.let { j -> b.journals.lines(j.id).associateBy { it.accountId } } ?: emptyMap()
        val skip = setOf("INVENTORY", "OPENING_EQUITY")
        val accs = b.masters.accounts(activeOnly = true).filter { !it.type.isPnl && it.systemKey !in skip }
        box.heading("Balances")
        box.note("Assets normally have a DEBIT balance; liabilities and capital normally have a CREDIT balance. Any difference goes to Opening Balance Equity automatically.")
        val fields = ArrayList<Triple<Account, EditText, EditText>>()
        for (a in accs) box.card {
            text(a.label, 14f, true)
            val row = context.hbox()
            val dv = ctx.vbox(0); val cv = ctx.vbox(0)
            row.addView(dv, lp(0, WRAP, 1f)); row.addView(cv, lp(0, WRAP, 1f))
            addView(row, lp())
            val l = existing[a.id]
            fields.add(Triple(a, dv.field("Debit", moneyInput(l?.debit ?: 0), type = NUM), cv.field("Credit", moneyInput(l?.credit ?: 0), type = NUM)))
        }
        val inv = b.reports.accountBalance(b.masters.accountId("INVENTORY"), start)
        box.kv("Opening stock (from products)", money(inv))
        box.button("Save opening balances") {
            val input = fields.map { (a, d, c) -> JournalLineInput(a.id, Money.parse(d.str, a.name), Money.parse(c.str, a.name)) }
            input.forEach { if (it.debit != 0L && it.credit != 0L) throw UserError("${b.masters.account(it.accountId).name}: enter either a debit or a credit, not both") }
            ctx.prompt("Note (optional)", "Opening balances are recorded in the audit trail.", "Note") { note ->
                b.journals.setOpeningBalances(input, note); ctx.toast("Opening balances saved"); refresh()
            }
        }
        return ctx.scroll(box)
    }
}

class ExpenseCategoriesScreen(act: MainActivity) : Screen(act) {
    override val title = "Expense categories"
    override fun build(): View {
        val b = books
        val box = ctx.vbox(12)
        for (c in b.masters.expenseCategories()) box.card {
            kv(c.name + if (!c.active) " (inactive)" else "", b.masters.account(c.accountId).label)
            setOnClickListener { edit(c) }
        }
        box.button("+ New category") { edit(null) }
        return ctx.scroll(box)
    }

    private fun edit(c: ExpenseCategory?) {
        val d = ctx.vbox(16)
        val n = d.field("Name", c?.name ?: "", type = CAPS)
        val accs: List<Account?> = listOf<Account?>(null) + books.masters.accounts(activeOnly = true).filter { it.type == AccountType.EXPENSE }
        val a = d.choice("Expense account", accs, accs.firstOrNull { it?.id == c?.accountId }) { it?.label ?: "Create a new account with this name" }
        val act2 = d.check("Active", c?.active ?: true)
        AlertDialog.Builder(ctx).setTitle(if (c == null) "New category" else "Edit category").setView(d).setPositiveButton("Save") { _, _ ->
            safely(ctx) { books.masters.saveExpenseCategory(c?.id, n.str, a.selected?.id, act2.isChecked); refresh() }
        }.setNegativeButton("Cancel", null).show()
    }
}
