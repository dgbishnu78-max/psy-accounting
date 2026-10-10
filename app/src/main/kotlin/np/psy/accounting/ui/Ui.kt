package np.psy.accounting.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import np.psy.core.db.UserError

/** Small helpers for building screens in code with Android's built-in widgets. */
object C {
    const val PRIMARY = 0xFF0B6E4F.toInt()
    const val PRIMARY_DARK = 0xFF07513A.toInt()
    const val ACCENT = 0xFFE8F3EE.toInt()
    const val TEXT = 0xFF1B1B1B.toInt()
    const val MUTED = 0xFF666666.toInt()
    const val WARN = 0xFFB3261E.toInt()
    const val WARN_BG = 0xFFFDECEA.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val BG = 0xFFF4F6F5.toInt()
    const val LINE = 0xFFDDDDDD.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; bottomMargin = bottom }

fun Context.vbox(padding: Int = 12): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply { isFillViewport = true; addView(child); setBackgroundColor(C.BG) }

fun roundBg(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = C.LINE) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius.toFloat(); if (stroke > 0) setStroke(stroke, strokeColor)
}

fun ViewGroup.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int = C.TEXT, gravity: Int = Gravity.START): TextView {
    val t = TextView(context).apply {
        text = s; textSize = size; setTextColor(color); this.gravity = gravity
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(false)
    }
    addView(t, lp(top = dp(2), bottom = dp(2)))
    return t
}

fun ViewGroup.heading(s: String): TextView = text(s, 18f, true, C.PRIMARY_DARK).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) }
fun ViewGroup.note(s: String): TextView = text(s, 13f, false, C.MUTED)

fun ViewGroup.warning(s: String): TextView {
    val t = text(s, 14f, false, C.WARN)
    t.background = roundBg(C.WARN_BG, dp(6)); t.setPadding(dp(10), dp(8), dp(10), dp(8))
    return t
}

fun ViewGroup.card(build: LinearLayout.() -> Unit = {}): LinearLayout {
    val c = context.vbox(12)
    c.background = roundBg(C.CARD, dp(10), 1)
    c.elevation = dp(1).toFloat()
    addView(c, lp(top = dp(6), bottom = dp(6)))
    c.build()
    return c
}

fun ViewGroup.button(s: String, primary: Boolean = true, onClick: () -> Unit): Button {
    val b = Button(context).apply {
        text = s; isAllCaps = false; textSize = 15f
        if (primary) { setTextColor(Color.WHITE); background = roundBg(C.PRIMARY, dp(8)) }
        else { setTextColor(C.PRIMARY_DARK); background = roundBg(C.ACCENT, dp(8), 1, C.PRIMARY) }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { safely(context) { onClick() } }
    }
    addView(b, if (this is LinearLayout && orientation == LinearLayout.HORIZONTAL) lp(0, WRAP, 1f).apply { marginEnd = dp(6) } else lp(top = dp(6)))
    return b
}

/** A label above an input field. */
fun ViewGroup.field(label: String, value: String = "", hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): EditText {
    text(label, 13f, false, C.MUTED)
    val e = EditText(context).apply {
        setText(value); this.hint = hint; inputType = if (lines > 1) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START } else setSingleLine(true)
        textSize = 16f
    }
    addView(e, lp(bottom = dp(4)))
    return e
}

const val NUM = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
const val INT = InputType.TYPE_CLASS_NUMBER
const val PHONE = InputType.TYPE_CLASS_PHONE
const val EMAIL = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
const val CAPS = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

fun ViewGroup.auto(label: String, value: String, suggestions: List<String>, type: Int = CAPS): AutoCompleteTextView {
    text(label, 13f, false, C.MUTED)
    val a = AutoCompleteTextView(context).apply {
        setText(value); inputType = type; setSingleLine(true); textSize = 16f; threshold = 1
        setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, suggestions))
    }
    addView(a, lp(bottom = dp(4)))
    return a
}

/** A labelled drop-down. */
class Choice<T>(val spinner: Spinner, val items: List<T>) {
    val selected: T? get() = items.getOrNull(spinner.selectedItemPosition)
    fun select(item: T?) { val i = items.indexOf(item); if (i >= 0) spinner.setSelection(i) }
    fun onChange(f: (T?) -> Unit) {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f(items.getOrNull(pos))
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

fun <T> ViewGroup.choice(label: String, items: List<T>, selected: T? = null, show: (T) -> String): Choice<T> {
    if (label.isNotEmpty()) text(label, 13f, false, C.MUTED)
    val s = Spinner(context)
    s.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.map(show))
    addView(s, lp(bottom = dp(4)))
    val c = Choice(s, items)
    c.select(selected)
    return c
}

fun ViewGroup.check(label: String, checked: Boolean): CheckBox {
    val c = CheckBox(context).apply { text = label; isChecked = checked; textSize = 15f }
    addView(c, lp())
    return c
}

fun ViewGroup.divider() {
    addView(View(context).apply { setBackgroundColor(C.LINE) }, lp(MATCH, dp(1), top = dp(6), bottom = dp(6)))
}

/** A two-column "label: value" line. */
fun ViewGroup.kv(k: String, v: String, bold: Boolean = false, color: Int = C.TEXT): LinearLayout {
    val row = context.hbox()
    row.addView(TextView(context).apply { text = k; textSize = 14f; setTextColor(C.MUTED) }, lp(0, WRAP, 1f))
    row.addView(TextView(context).apply { text = v; textSize = 15f; setTextColor(color); gravity = Gravity.END; if (bold) setTypeface(typeface, Typeface.BOLD) }, lp(0, WRAP, 1.2f))
    addView(row, lp(top = dp(2), bottom = dp(2)))
    return row
}

fun EditText.onText(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
})

val EditText.str: String get() = text.toString().trim()

// ---------------- Dialogs ----------------

fun Context.info(title: String, msg: String, onOk: () -> Unit = {}) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK") { _, _ -> onOk() }.setCancelable(false).show()
}

fun Context.error(e: Throwable) {
    val msg = when (e) {
        is UserError -> e.message ?: "Please check the entry"
        is np.psy.core.backup.BackupError -> e.message ?: "Backup problem"
        is IllegalArgumentException -> e.message ?: "Invalid value"
        else -> "Something went wrong and nothing was saved.\n\n${e.javaClass.simpleName}: ${e.message}"
    }
    AlertDialog.Builder(this).setTitle(if (e is UserError || e is IllegalArgumentException) "Please check" else "Problem").setMessage(msg)
        .setPositiveButton("OK", null).show()
}

fun Context.confirm(title: String, msg: String, yes: String = "Yes", no: String = "Cancel", onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(yes) { _, _ -> safely(this) { onYes() } }.setNegativeButton(no, null).show()
}

fun Context.prompt(title: String, msg: String, hint: String = "", initial: String = "", type: Int = InputType.TYPE_CLASS_TEXT, onOk: (String) -> Unit) {
    val box = vbox(16)
    if (msg.isNotEmpty()) box.note(msg)
    val e = EditText(this).apply { this.hint = hint; setText(initial); inputType = type }
    box.addView(e, lp())
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> safely(this) { onOk(e.str) } }.setNegativeButton("Cancel", null).show()
}

fun <T> Context.pick(title: String, items: List<T>, show: (T) -> String, onPick: (T) -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setItems(items.map(show).toTypedArray()) { _, i -> safely(this) { onPick(items[i]) } }
        .setNegativeButton("Cancel", null).show()
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

/** Runs an action and shows any error in a dialog instead of crashing. */
inline fun safely(ctx: Context, f: () -> Unit) {
    try { f() } catch (e: Throwable) { android.util.Log.e("PSY", "action failed", e); ctx.error(e) }
}

/** Progress dialog while background work runs. */
fun <T> Context.working(message: String, work: () -> T, done: (T) -> Unit) {
    val box = hbox().apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
    box.addView(ProgressBar(this), LinearLayout.LayoutParams(WRAP, WRAP))
    box.addView(TextView(this).apply { text = message; textSize = 16f; setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(WRAP, WRAP))
    val d = AlertDialog.Builder(this).setView(box).setCancelable(false).show()
    np.psy.accounting.App.bg(work) { r ->
        try { d.dismiss() } catch (ignored: Exception) {}
        r.fold({ v -> safely(this) { done(v) } }, { e -> error(e) })
    }
}
