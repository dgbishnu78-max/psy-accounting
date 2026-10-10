package np.psy.accounting.screens

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import np.psy.accounting.App
import np.psy.accounting.Exporter
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.Books
import np.psy.core.cal.BsCalendar
import np.psy.core.cal.BsDate
import np.psy.core.report.Align
import np.psy.core.report.ReportTable
import np.psy.core.report.RowStyle
import np.psy.core.util.Money
import np.psy.core.util.Qty
import java.time.LocalDate

fun money(p: Long) = Money.format(p)
fun npr(p: Long) = "NPR " + Money.format(p)
fun bs(d: LocalDate) = BsDate.fromAd(d).toString()
fun bsLong(d: LocalDate) = BsDate.fromAd(d).toLongString()
fun bsAd(d: LocalDate) = "${bs(d)} BS ($d AD)"
fun qty(m: Long) = Qty.format(m)

/** Converts paisa to the text shown in an input field ("" for zero when [blankZero]). */
fun moneyInput(p: Long, blankZero: Boolean = true) = if (blankZero && p == 0L) "" else Money.format(p, grouping = false)
fun qtyInput(m: Long, blankZero: Boolean = true) = if (blankZero && m == 0L) "" else Qty.format(m)

/** Adapter that builds each list row with a function. */
class RowsAdapter<T>(private val items: List<T>, private val make: (T, Int) -> View) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): Any? = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = make(items[position], position)
}

/** A simple two-line list row: title + right-hand amount, with a subtitle underneath. */
fun Context.listRow(title: String, right: String, sub: String, rightColor: Int = C.TEXT, sub2: String = "", warn: Boolean = false): View {
    val box = vbox(10)
    box.setBackgroundColor(if (warn) C.WARN_BG else C.CARD)
    val top = hbox()
    top.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(C.TEXT); setTypeface(typeface, Typeface.BOLD) }, lp(0, WRAP, 1f))
    top.addView(TextView(this).apply { text = right; textSize = 15f; setTextColor(rightColor); gravity = Gravity.END }, LinearLayout.LayoutParams(WRAP, WRAP))
    box.addView(top, lp())
    if (sub.isNotEmpty()) box.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(C.MUTED) }, lp())
    if (sub2.isNotEmpty()) box.addView(TextView(this).apply { text = sub2; textSize = 13f; setTextColor(if (warn) C.WARN else C.MUTED) }, lp())
    return box
}

/** Vertical page: fixed header area + a list that fills the rest. */
class ListPage(ctx: Context) {
    val root: LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
    val header: LinearLayout = ctx.vbox(10)
    val list: ListView = ListView(ctx).apply { divider = android.graphics.drawable.ColorDrawable(C.LINE); dividerHeight = 1 }
    val empty: TextView = TextView(ctx).apply { textSize = 15f; setTextColor(C.MUTED); gravity = Gravity.CENTER; setPadding(ctx.dp(16), ctx.dp(32), ctx.dp(16), ctx.dp(32)) }

    init {
        root.addView(header, lp())
        val frame = FrameLayout(ctx)
        frame.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(frame, lp(MATCH, 0, 1f))
    }

    fun <T> show(items: List<T>, emptyText: String, make: (T, Int) -> View, onClick: ((T) -> Unit)? = null) {
        list.adapter = RowsAdapter(items, make)
        empty.text = emptyText
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (onClick != null) list.setOnItemClickListener { _, _, pos, _ -> safely(list.context) { onClick(items[pos]) } }
    }
}

/** Two BS date fields with quick period buttons (month, quarter, financial year). */
class PeriodPicker(parent: LinearLayout, private val books: Books, from: LocalDate, to: LocalDate) {
    val fromField: BsDateField
    val toField: BsDateField

    init {
        val ctx = parent.context
        val presets = ctx.hbox()
        val today = books.today()
        val fyS = books.years.boundsFor(today).first
        fun setRange(a: LocalDate, b: LocalDate) { fromField.set(a); toField.set(b) }
        fun monthOf(d: LocalDate): Pair<LocalDate, LocalDate> { val b = BsDate.fromAd(d); return b.firstOfMonth().toAd() to b.lastOfMonth().toAd() }
        presets.button("This month", false) { val (a, b) = monthOf(today); setRange(a, b) }
        presets.button("Month...", false) {
            val months = ArrayList<BsDate>()
            var m = BsDate.fromAd(today).firstOfMonth()
            repeat(36) { months.add(m); m = m.plusDays(-1).firstOfMonth() }
            ctx.pick("Choose BS month", months, { "${BsCalendar.monthName(it.month)} ${it.year}" }) { setRange(it.toAd(), it.lastOfMonth().toAd()) }
        }
        parent.addView(presets, lp())
        val p2 = ctx.hbox()
        fun fyBounds(i: Int) = books.years.boundsFor(BsDate(fyS.year - i, fyS.month, 1).toAd())
        fun fyName(i: Int) = fyBounds(i).let { (s, e) -> books.years.nameFor(s, e) }
        p2.button("Quarter...", false) {
            val opts = (0..2).flatMap { i -> (1..4).map { q -> i to q } }
            ctx.pick("Choose quarter", opts, { (i, q) -> "Q$q of FY ${fyName(i)}" }) { (i, q) ->
                val (a, b) = books.reports.fyQuarterRange(fyS.year - i, q); setRange(a, b)
            }
        }
        p2.button("Financial year...", false) {
            val opts = (0..4).toList()
            ctx.pick("Choose financial year", opts, { i -> fyBounds(i).let { (s, e) -> "FY ${fyName(i)} ($s to $e BS)" } }) { i -> fyBounds(i).let { (s, e) -> setRange(s.toAd(), e.toAd()) } }
        }
        parent.addView(p2, lp())
        fromField = BsDateField(parent, "From", from)
        toField = BsDateField(parent, "To", to)
    }

    fun range(): Pair<LocalDate, LocalDate> {
        val a = fromField.value(); val b = toField.value()
        if (b.isBefore(a)) throw np.psy.core.db.UserError("The 'To' date is before the 'From' date")
        return a to b
    }
}

/** Shows one or more report tables, with drill-down, PDF and CSV export. */
class ReportScreen(act: MainActivity, private val tables: List<ReportTable>, private val range: Pair<LocalDate, LocalDate>? = null) : Screen(act) {
    private var index = 0
    override val title get() = tables.getOrNull(index)?.title ?: "Report"
    override val actions get() = listOf<Pair<String, () -> Unit>>(
        "Save / share as PDF" to { Exporter.offerReport(act, tables, true) },
        "Save / share as CSV (Excel)" to { Exporter.offerReport(act, tables, false) },
        "Print" to { act.working("Preparing...", { Exporter.reportPdf(tables) }) { Exporter.print(act, Exporter.safeName(tables.first().title) + ".pdf", it) } },
    )

    override fun build(): View {
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val head = ctx.vbox(10)
        page.addView(head, lp())
        if (tables.size > 1) {
            val c = head.choice("Section", tables.indices.toList(), index) { tables[it].title }
            c.onChange { i -> if (i != null && i != index) { index = i; refresh() } }
        }
        val t = tables.getOrNull(index) ?: return page.apply { head.note("No data") }
        head.text(t.title, 17f, true, C.PRIMARY_DARK)
        head.note(t.period)
        t.warnings.forEach { head.warning(it) }
        if (t.rows.any { it.link != null }) head.note("Tap a line to see its details.")

        val unit = ctx.dp(96)
        val widths = t.columns.map { (it.weight * unit).toInt().coerceAtLeast(ctx.dp(56)) }
        val total = widths.sum()
        val hs = HorizontalScrollView(ctx)
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        hs.addView(inner, FrameLayout.LayoutParams(total, MATCH))
        val hdr = ctx.hbox().apply { setBackgroundColor(C.PRIMARY) }
        t.columns.forEachIndexed { i, col ->
            hdr.addView(cell(col.title, widths[i], col.align, true, android.graphics.Color.WHITE), LinearLayout.LayoutParams(widths[i], WRAP))
        }
        inner.addView(hdr, LinearLayout.LayoutParams(total, WRAP))
        val lv = ListView(ctx).apply { divider = android.graphics.drawable.ColorDrawable(C.LINE); dividerHeight = 1; setBackgroundColor(C.CARD) }
        val rows = t.rows
        lv.adapter = RowsAdapter(rows) { r, _ ->
            val row = ctx.hbox()
            val bold = r.style == RowStyle.SECTION || r.style == RowStyle.SUBTOTAL || r.style == RowStyle.TOTAL
            val color = when (r.style) { RowStyle.WARNING -> C.WARN; RowStyle.MUTED -> C.MUTED; RowStyle.SECTION -> C.PRIMARY_DARK; else -> C.TEXT }
            row.setBackgroundColor(when (r.style) { RowStyle.SECTION -> C.ACCENT; RowStyle.TOTAL -> 0xFFE0E0E0.toInt(); RowStyle.SUBTOTAL -> 0xFFF2F2F2.toInt(); RowStyle.WARNING -> C.WARN_BG; else -> C.CARD })
            t.columns.forEachIndexed { i, col ->
                row.addView(cell(t.displayCell(r.cells.getOrNull(i)), widths[i], col.align, bold, if (r.link != null && i == 0 && r.style == RowStyle.NORMAL) C.PRIMARY else color), LinearLayout.LayoutParams(widths[i], WRAP))
            }
            row
        }
        lv.setOnItemClickListener { _, _, pos, _ -> rows[pos].link?.let { safely(ctx) { drill(it) } } }
        inner.addView(lv, LinearLayout.LayoutParams(total, 0, 1f))
        page.addView(hs, lp(MATCH, 0, 1f))
        if (t.notes.isNotEmpty()) {
            val foot = ctx.vbox(8)
            t.notes.forEach { foot.note(it) }
            page.addView(ScrollView(ctx).apply { addView(foot) }, lp(MATCH, if (t.notes.size > 3) ctx.dp(140) else WRAP))
        }
        return page
    }

    private fun cell(s: String, w: Int, align: Align, bold: Boolean, color: Int) = TextView(ctx).apply {
        text = s; textSize = 13f; setTextColor(color); setPadding(dp(6), dp(8), dp(6), dp(8))
        gravity = when (align) { Align.RIGHT -> Gravity.END; Align.CENTER -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START }
        if (bold) setTypeface(typeface, Typeface.BOLD)
        width = w
    }

    private fun drill(link: String) {
        val kind = link.substringBefore(':'); val id = link.substringAfter(':').toLongOrNull() ?: return
        val (from, to) = range ?: books.years.current().let { it.start to it.end }
        when (kind) {
            "voucher" -> push(VoucherViewScreen(act, id))
            "journal" -> push(JournalViewScreen(act, id))
            "account" -> act.working("Preparing ledger...", { books.reports.accountLedger(id, from, to) }) { push(ReportScreen(act, listOf(it), from to to)) }
            "product" -> act.working("Preparing stock movement...", { books.reports.stockMovement(id, from, to) }) { push(ReportScreen(act, listOf(it), from to to)) }
        }
    }
}

/** Shows the accounting entry (journal) with its lines. */
class JournalViewScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "Journal entry"
    override fun build(): View {
        val b = books
        val j = b.journals.get(id)
        val box = ctx.vbox(12)
        box.card {
            text("${j.number}", 18f, true)
            kv("Type", j.type.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
            kv("Date", bsAd(j.date))
            kv("Status", j.status)
            text(j.narration, 14f)
        }
        box.heading("Lines")
        var dr = 0L; var cr = 0L
        for (l in b.journals.lines(id)) {
            val a = b.masters.account(l.accountId)
            dr += l.debit; cr += l.credit
            box.card {
                text(a.label, 15f, true)
                if (l.debit != 0L) kv("Debit", money(l.debit))
                if (l.credit != 0L) kv("Credit", money(l.credit))
                if (l.memo.isNotEmpty()) note(l.memo)
            }
        }
        box.kv("Total debit", money(dr), true); box.kv("Total credit", money(cr), true)
        if (j.voucherId != null) box.button("Open source document", false) { push(VoucherViewScreen(act, j.voucherId!!)) }
        if (j.reversalOfId != null) box.button("Open the entry this reverses", false) { push(JournalViewScreen(act, j.reversalOfId!!)) }
        if (j.type == "JOURNAL" && j.status == "POSTED") box.button("Cancel (reverse) this journal", false) {
            ctx.prompt("Cancel journal ${j.number}", "A reversal entry will be posted. The original stays on record.", "Reason") { reason ->
                b.journals.cancelManual(id, reason); ctx.toast("Journal reversed"); refresh()
            }
        }
        return ctx.scroll(box)
    }
}
