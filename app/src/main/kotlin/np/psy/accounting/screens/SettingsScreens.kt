package np.psy.accounting.screens

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import np.psy.accounting.App
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.cal.BsCalendar
import np.psy.core.cal.BsDate
import np.psy.core.db.UserError
import np.psy.core.model.VatCategory
import np.psy.core.model.VatKind
import np.psy.core.model.VoucherType
import np.psy.core.service.SettingsService
import java.io.ByteArrayOutputStream

class SettingsScreen(act: MainActivity) : Screen(act) {
    override val title = "Settings"
    override fun build(): View {
        val box = ctx.vbox(12)
        box.button("Company details, VAT status, logo") { push(CompanySettingsScreen(act)) }
        box.button("Invoice printing and document numbers") { push(NumberingScreen(act)) }
        box.button("VAT categories (rates)", false) { push(VatCategoriesScreen(act)) }
        box.button("Units", false) { push(UnitsScreen(act)) }
        box.button("Expense categories", false) { push(ExpenseCategoriesScreen(act)) }
        box.button("Financial year start month", false) { fyMonth() }
        box.button("BS calendar check and correction", false) { push(CalendarScreen(act)) }
        box.button("Audit trail (all changes)", false) { ReportsScreen.runReport(act, ReportKind.AUDIT, null, null, null, null, null) }
        box.button("Backup and restore", false) { push(BackupScreen(act)) }
        box.divider()
        box.note("App version ${App.version}. Data file: ${App.dbFile.path}. The app works fully offline and has no internet permission.")
        return ctx.scroll(box)
    }

    private fun fyMonth() {
        val cur = books.years.startMonth
        ctx.pick("Financial year starts on 1st of (current: ${BsCalendar.monthName(cur)})", (1..12).toList(), { BsCalendar.monthName(it) + if (it == 4) " (Nepal standard)" else "" }) { m ->
            if (m == cur) return@pick
            ctx.confirm("Change financial year start?", "Nepal's standard financial year starts on 1 Shrawan. Change only if your accountant says so. This is not allowed after a year has been closed.") {
                books.years.setStartMonth(m); ctx.toast("Saved")
            }
        }
    }
}

class CompanySettingsScreen(act: MainActivity) : Screen(act) {
    override val title = "Company details"
    override fun build(): View {
        val s = books.settings
        val box = ctx.vbox(12)
        box.note("These details are printed on invoices and reports. Enter them exactly as on your PAN/VAT registration certificate.")
        val name = box.field("Company name", s.companyName, type = CAPS)
        val addr = box.field("Address", s.companyAddress)
        val phone = box.field("Phone", s.get("company_phone"), type = PHONE)
        val email = box.field("Email", s.get("company_email"), type = EMAIL)
        val pan = box.field("PAN / VAT number (9 digits)", s.companyPan, type = INT)
        val status = box.choice("VAT registration", SettingsService.VAT_STATUS, SettingsService.VAT_STATUS.firstOrNull { it.first == s.get("vat_registration_status") }) { it.second }
        box.note("VAT registered: invoices are 'Tax Invoice' and VAT is charged. PAN only: no VAT is charged on sales.")
        box.button("Save company details") {
            val p = pan.str
            if (p.isNotEmpty() && (p.length != 9 || !p.all { it.isDigit() })) throw UserError("PAN/VAT number must be 9 digits")
            if (name.str.isEmpty()) throw UserError("Enter the company name")
            s.set("company_name", name.str); s.set("company_address", addr.str); s.set("company_phone", phone.str)
            s.set("company_email", email.str); s.set("company_pan", p); s.set("vat_registration_status", status.selected?.first ?: "NOT_SET")
            ctx.toast("Saved")
            back()
        }
        box.heading("Logo")
        val logo = s.get("logo_jpeg_base64")
        if (logo.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(logo, android.util.Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                box.addView(ImageView(ctx).apply { setImageBitmap(bm); adjustViewBounds = true }, LinearLayout.LayoutParams(ctx.dp(160), ctx.dp(80)))
            } catch (e: Exception) { box.note("Logo could not be shown") }
        } else box.note("No logo. Invoices are printed without a logo.")
        val row = ctx.hbox()
        row.button("Choose logo image", false) { pickLogo() }
        if (logo.isNotEmpty()) row.button("Remove logo", false) { s.set("logo_jpeg_base64", ""); refresh() }
        box.addView(row, lp())
        return ctx.scroll(box)
    }

    private fun pickLogo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        act.startForResult(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@startForResult
            val bm = act.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: throw UserError("That image could not be read")
            val scale = minOf(1f, 400f / bm.width, 200f / bm.height)
            val scaled = Bitmap.createScaledBitmap(bm, (bm.width * scale).toInt().coerceAtLeast(1), (bm.height * scale).toInt().coerceAtLeast(1), true)
            // JPEG has no transparency: draw on white first.
            val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out); c.drawColor(android.graphics.Color.WHITE); c.drawBitmap(scaled, 0f, 0f, null)
            val bos = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            books.settings.set("logo_jpeg_base64", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP))
            ctx.toast("Logo saved"); refresh()
        }
    }
}

class NumberingScreen(act: MainActivity) : Screen(act) {
    override val title = "Printing and numbering"
    override fun build(): View {
        val s = books.settings
        val box = ctx.vbox(12)
        val footer = box.field("Invoice footer", s.get("invoice_footer"), lines = 2)
        val terms = box.field("Terms and conditions (printed on invoices)", s.get("invoice_terms"), lines = 3)
        val ad = box.check("Show AD dates next to BS dates on reports and invoices", s.printAdDates)
        box.heading("Document numbers")
        box.note("{FY} is replaced by the financial year (e.g. 2083/84), {N4} by a 4-digit running number (0001), {N} by the plain number. Numbering restarts each financial year.")
        val types = listOf(VoucherType.SALE, VoucherType.SALES_RETURN, VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN, VoucherType.EXPENSE, VoucherType.STOCK_ADJUSTMENT)
        val fields = types.map { t -> t.name to box.field(t.label, s.get("number_format.${t.name}")) } + ("JOURNAL" to box.field("Journal entry", s.get("number_format.JOURNAL")))
        box.button("Save") {
            for ((t, f) in fields) if (f.str != s.get("number_format.$t")) s.setNumberFormat(t, f.str)
            s.set("invoice_footer", footer.str); s.set("invoice_terms", terms.str); s.set("print_ad_dates", if (ad.isChecked) "1" else "0")
            ctx.toast("Saved"); back()
        }
        return ctx.scroll(box)
    }
}

class VatCategoriesScreen(act: MainActivity) : Screen(act) {
    override val title = "VAT categories"
    override fun build(): View {
        val box = ctx.vbox(12)
        box.note("Each product has a VAT category. Posted invoices keep the rate they were posted with, even if a category changes later.")
        for (c in books.masters.vatCategories()) box.card {
            kv(c.name + if (!c.active) " (inactive)" else "", "${c.kind.label}  ${c.rateLabel}")
            setOnClickListener { edit(c) }
        }
        box.button("+ New VAT category") { edit(null) }
        return ctx.scroll(box)
    }

    private fun edit(c: VatCategory?) {
        val d = ctx.vbox(16)
        val n = d.field("Name", c?.name ?: "")
        val kind = d.choice("Type", VatKind.values().toList(), c?.kind ?: VatKind.TAXABLE) { it.label }
        val rate = d.field("Rate % (13 for standard VAT; 0 for exempt / zero-rated)", c?.let { np.psy.core.util.Money.format(it.rateBp.toLong(), false) } ?: "13", type = NUM)
        val a = d.check("Active", c?.active ?: true)
        AlertDialog.Builder(ctx).setTitle(if (c == null) "New VAT category" else "Edit VAT category").setView(d).setPositiveButton("Save") { _, _ ->
            safely(ctx) {
                val bp = np.psy.core.util.Money.parse(rate.str, "Rate").toInt()
                books.masters.saveVatCategory(c?.id, n.str, bp, kind.selected!!, a.isChecked); refresh()
            }
        }.setNegativeButton("Cancel", null).show()
    }
}

class CalendarScreen(act: MainActivity) : Screen(act) {
    private var year = BsDate.fromAd(java.time.LocalDate.now()).year
    override val title = "BS calendar"
    override fun build(): View {
        val box = ctx.vbox(12)
        box.note("The app converts BS and AD dates with a built-in table of month lengths for ${BsCalendar.FIRST_YEAR} to ${BsCalendar.LAST_YEAR} BS. Future years are based on published calendars. If the official calendar (Nepal Panchanga Nirnayak Samiti) differs for a year, correct the month lengths here. Closed years cannot be affected.")
        val yc = box.choice("BS year", (BsCalendar.FIRST_YEAR..BsCalendar.LAST_YEAR).toList(), year) { it.toString() }
        yc.onChange { y -> if (y != null && y != year) { year = y; refresh() } }
        val cur = IntArray(12) { BsCalendar.daysInMonth(year, it + 1) }
        val def = BsCalendar.defaultMonthLengths(year)
        if (!cur.contentEquals(def)) box.warning("This year has a correction. Built-in values: ${def.joinToString(", ")}")
        val start = BsDate(year, 1, 1).toAd()
        box.note("1 Baisakh $year BS = $start AD")
        val fields = (1..12).map { m -> box.field("${BsCalendar.monthName(m)} (days)", cur[m - 1].toString(), type = INT) }
        val note = box.field("Source of the correction (e.g. official calendar)", "")
        box.button("Save month lengths for $year") {
            val arr = IntArray(12) { fields[it].str.toIntOrNull() ?: throw UserError("Enter the number of days for ${BsCalendar.monthName(it + 1)}") }
            ctx.confirm("Change the calendar for $year BS?", "All dates in $year BS and later will move accordingly. Entries keep their AD date, so their BS date may change.") {
                books.masters.saveCalendarCorrection(year, arr, note.str); ctx.toast("Calendar saved"); refresh()
            }
        }
        if (!cur.contentEquals(def)) box.button("Restore built-in values for $year", false) {
            books.masters.saveCalendarCorrection(year, def, "Restored built-in values"); refresh()
        }
        return ctx.scroll(box)
    }
}
