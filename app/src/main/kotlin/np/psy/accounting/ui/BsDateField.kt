package np.psy.accounting.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import np.psy.core.cal.BsCalendar
import np.psy.core.cal.BsDate
import np.psy.core.db.UserError
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** BS date input: type YYYY-MM-DD or use the BS calendar picker. The AD date is shown underneath. */
class BsDateField(parent: ViewGroup, label: String, initial: LocalDate?) {
    private val ctx = parent.context
    val edit: EditText
    private val ad: TextView
    var onChange: (LocalDate?) -> Unit = {}

    init {
        parent.text("$label (BS)", 13f, false, C.MUTED)
        val row = ctx.hbox()
        edit = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE; hint = "2083-06-24"; textSize = 16f; setSingleLine(true) }
        row.addView(edit, lp(0, WRAP, 1f))
        val btn = Button(ctx).apply { text = "Calendar"; isAllCaps = false; setTextColor(C.PRIMARY_DARK); background = roundBg(C.ACCENT, ctx.dp(8), 1, C.PRIMARY) }
        row.addView(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ctx.dp(6) })
        parent.addView(row, lp())
        ad = parent.text("", 12f, false, C.MUTED)
        edit.onText { refresh() }
        btn.setOnClickListener { BsPicker.show(ctx, valueOrNull() ?: LocalDate.now()) { set(it) } }
        if (initial != null) set(initial)
    }

    private fun refresh() {
        val d = valueOrNull()
        ad.text = if (d != null) "= ${d} AD (${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})" else if (edit.str.isEmpty()) "" else "Not a valid BS date"
        ad.setTextColor(if (d == null && edit.str.isNotEmpty()) C.WARN else C.MUTED)
        onChange(d)
    }

    fun set(d: LocalDate) { edit.setText(BsDate.fromAd(d).toString()); refresh() }

    fun valueOrNull(): LocalDate? = try { BsDate.parse(edit.str).toAd() } catch (e: Exception) { null }

    fun value(): LocalDate = try { BsDate.parse(edit.str).toAd() } catch (e: IllegalArgumentException) { throw UserError(e.message ?: "Invalid BS date") }
}

object BsPicker {
    fun show(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
        var sel = try { BsDate.fromAd(initial) } catch (e: Exception) { BsDate.fromAd(LocalDate.now()) }
        val box = ctx.vbox(12)
        val top = ctx.hbox()
        val years = (BsCalendar.FIRST_YEAR..BsCalendar.LAST_YEAR).toList()
        val ys = Spinner(ctx).apply { adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, years.map { it.toString() }) }
        val ms = Spinner(ctx).apply { adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, BsCalendar.MONTH_NAMES.toList()) }
        top.addView(ys, lp(0, WRAP, 1f)); top.addView(ms, lp(0, WRAP, 1.3f))
        box.addView(top, lp())
        val grid = GridLayout(ctx).apply { columnCount = 7 }
        box.addView(grid, lp(top = ctx.dp(6)))
        val info = box.text("", 14f, true, C.PRIMARY_DARK, Gravity.CENTER)

        fun draw() {
            grid.removeAllViews()
            val w = ctx.resources.displayMetrics.widthPixels
            val cell = ((w * 0.8f) / 7).toInt().coerceAtMost(ctx.dp(48))
            for (d in listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")) grid.addView(TextView(ctx).apply {
                text = d; gravity = Gravity.CENTER; textSize = 12f; setTextColor(if (d == "Sat") C.WARN else C.MUTED)
            }, GridLayout.LayoutParams().apply { width = cell; height = ctx.dp(28) })
            val first = BsDate(sel.year, sel.month, 1).toAd()
            val offset = first.dayOfWeek.value % 7   // Sunday = 0
            repeat(offset) { grid.addView(TextView(ctx), GridLayout.LayoutParams().apply { width = cell; height = cell }) }
            for (day in 1..BsCalendar.daysInMonth(sel.year, sel.month)) {
                val chosen = day == sel.day
                val today = BsDate(sel.year, sel.month, day).toAd() == LocalDate.now()
                grid.addView(TextView(ctx).apply {
                    text = day.toString(); gravity = Gravity.CENTER; textSize = 15f
                    setTextColor(if (chosen) Color.WHITE else C.TEXT)
                    if (chosen) background = roundBg(C.PRIMARY, cell / 2) else if (today) background = roundBg(C.ACCENT, cell / 2, 1, C.PRIMARY)
                    if (today) setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { sel = BsDate(sel.year, sel.month, day); draw() }
                }, GridLayout.LayoutParams().apply { width = cell; height = cell })
            }
            val ad = sel.toAd()
            info.text = "${sel.toLongString()} BS  =  ${ad} AD (${ad.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)})"
        }
        var ready = false
        ys.setSelection(years.indexOf(sel.year)); ms.setSelection(sel.month - 1)
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (!ready) return
                val y = years[ys.selectedItemPosition]; val m = ms.selectedItemPosition + 1
                sel = BsDate(y, m, minOf(sel.day, BsCalendar.daysInMonth(y, m))); draw()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        ys.onItemSelectedListener = listener; ms.onItemSelectedListener = listener
        draw()
        box.post { ready = true }
        val dlg = AlertDialog.Builder(ctx).setTitle("Choose BS date").setView(ctx.scroll(box))
            .setPositiveButton("OK") { _, _ -> onPick(sel.toAd()) }
            .setNeutralButton("Today") { _, _ -> onPick(LocalDate.now()) }
            .setNegativeButton("Cancel", null).create()
        dlg.show()
    }
}
