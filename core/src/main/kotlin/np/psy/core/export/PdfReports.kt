package np.psy.core.export

import np.psy.core.Books
import np.psy.core.cal.BsDate
import np.psy.core.model.*
import np.psy.core.report.*
import np.psy.core.util.Money
import np.psy.core.util.Qty

/** Lays out reports and invoices on a [PdfCanvas]. Shared by the app and the tests. */
class PdfReports(private val b: Books) {
    private val margin = 32f

    private fun fit(c: PdfCanvas, s: String, size: Float, bold: Boolean, w: Float): String {
        if (c.textWidth(s, size, bold) <= w) return s
        var t = s
        while (t.isNotEmpty() && c.textWidth("$t...", size, bold) > w) t = t.dropLast(1)
        return "$t..."
    }

    private fun wrap(c: PdfCanvas, s: String, size: Float, w: Float, bold: Boolean = false): List<String> {
        val out = ArrayList<String>()
        for (para in s.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (c.textWidth(cand, size, bold) <= w) line = cand
                else { if (line.isNotEmpty()) out.add(line); line = fit(c, word, size, bold, w) }
            }
            out.add(line)
        }
        return out
    }

    private fun printedOn(): String {
        val now = b.clock.now()
        return "Printed ${BsDate.fromAd(now.toLocalDate())} BS (${now.toLocalDate()} AD) ${"%02d:%02d".format(now.hour, now.minute)}"
    }

    /** Renders one or more reports into a single PDF. */
    fun reports(c: PdfCanvas, tables: List<ReportTable>): ByteArray {
        for (t in tables) report(c, t)
        return c.finish()
    }

    private fun report(c: PdfCanvas, t: ReportTable) {
        val pw = if (t.landscape) 842f else 595f; val ph = if (t.landscape) 595f else 842f
        val fs = if (t.landscape || t.columns.size > 6) 7.5f else 9f
        val rowH = fs + 5f
        val tableW = pw - 2 * margin
        val totalW = t.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = t.columns.map { it.weight / totalW * tableW }
        var page = 0
        var y = 0f
        val company = b.settings.companyName
        val pan = b.settings.companyPan

        fun header() {
            c.startPage(pw, ph); page++
            y = margin
            c.text(margin, y + 12, company, 13f, bold = true); y += 17
            val sub = b.settings.companyAddress + if (pan.isNotEmpty()) "   PAN/VAT: $pan" else ""
            c.text(margin, y + 9, sub, 8.5f); y += 14
            c.text(margin, y + 11, t.title, 11f, bold = true); y += 14
            c.text(margin, y + 9, t.period, 8.5f); y += 12
            c.line(margin, y + 2, pw - margin, y + 2, 0.8f); y += 6
            // column header
            c.fillRect(margin, y, tableW, rowH + 2, 0.88f)
            var x = margin
            for ((i, col) in t.columns.withIndex()) {
                val s = fit(c, col.title, fs, true, widths[i] - 4)
                val tx = when (col.align) { Align.RIGHT -> x + widths[i] - 2 - c.textWidth(s, fs, true); Align.CENTER -> x + (widths[i] - c.textWidth(s, fs, true)) / 2; else -> x + 2 }
                c.text(tx, y + fs + 1.5f, s, fs, bold = true)
                x += widths[i]
            }
            y += rowH + 4
        }
        fun footer() {
            c.line(margin, ph - margin + 4, pw - margin, ph - margin + 4, 0.3f)
            c.text(margin, ph - margin + 14, "$company - ${t.title}", 7f, gray = true)
            val r = "${printedOn()}   Page $page"
            c.text(pw - margin - c.textWidth(r, 7f), ph - margin + 14, r, 7f, gray = true)
        }
        header()
        for (row in t.rows) {
            if (y + rowH > ph - margin - 6) { footer(); header() }
            val bold = row.style == RowStyle.SECTION || row.style == RowStyle.SUBTOTAL || row.style == RowStyle.TOTAL
            val gray = row.style == RowStyle.MUTED
            if (row.style == RowStyle.SUBTOTAL) c.line(margin, y, pw - margin, y, 0.3f)
            if (row.style == RowStyle.TOTAL) { c.line(margin, y, pw - margin, y, 0.6f) }
            if (row.style == RowStyle.SECTION) c.fillRect(margin, y, tableW, rowH, 0.95f)
            var x = margin
            for ((i, col) in t.columns.withIndex()) {
                val raw = t.displayCell(row.cells.getOrNull(i))
                if (raw.isNotEmpty()) {
                    val s = fit(c, raw, fs, bold, widths[i] - 4)
                    val tx = when (col.align) { Align.RIGHT -> x + widths[i] - 2 - c.textWidth(s, fs, bold); Align.CENTER -> x + (widths[i] - c.textWidth(s, fs, bold)) / 2; else -> x + 2 }
                    c.text(tx, y + fs + 1f, s, fs, bold, gray)
                }
                x += widths[i]
            }
            if (row.style == RowStyle.WARNING) c.text(margin - 8, y + fs + 1f, "!", fs, true)
            y += rowH
            if (row.style == RowStyle.TOTAL) c.line(margin, y, pw - margin, y, 0.6f)
        }
        y += 6
        val extra = t.warnings.map { "WARNING: $it" to true } + t.notes.map { it to false }
        for ((s, bold) in extra) for (l in wrap(c, s, 8f, tableW, bold)) {
            if (y + 11 > ph - margin - 6) { footer(); header() }
            c.text(margin, y + 9, l, 8f, bold); y += 11
        }
        footer()
    }

    /** Printable document: tax invoice, credit note, purchase record, expense voucher. */
    fun document(c: PdfCanvas, voucherId: Long, logoJpeg: ByteArray? = null): ByteArray {
        val v = b.docs.voucher(voucherId)
        val lines = b.docs.lines(voucherId)
        val s = b.settings
        val vatReg = s.get("vat_registration_status") == "VAT_REGISTERED"
        val pw = 595f; val ph = 842f; val w = pw - 2 * margin
        c.startPage(pw, ph)
        var y = margin
        var textX = margin
        if (logoJpeg != null && logoJpeg.isNotEmpty()) {
            val size = SimplePdf.jpegSize(logoJpeg)
            if (size != null) { val h = 48f; val lw = h * size.first / size.second.coerceAtLeast(1); c.jpeg(margin, y, lw.coerceAtMost(120f), h, logoJpeg); textX = margin + lw.coerceAtMost(120f) + 10 }
        }
        c.text(textX, y + 14, s.companyName, 15f, true)
        c.text(textX, y + 28, s.companyAddress, 9f)
        val contact = listOf(s.get("company_phone").takeIf { it.isNotEmpty() }?.let { "Phone: $it" }, s.get("company_email").takeIf { it.isNotEmpty() }?.let { "Email: $it" }).filterNotNull().joinToString("   ")
        if (contact.isNotEmpty()) c.text(textX, y + 40, contact, 9f)
        c.text(textX, y + 52, if (s.companyPan.isNotEmpty()) (if (vatReg) "VAT/PAN No.: " else "PAN No.: ") + s.companyPan else "PAN/VAT No.: (not set)", 9f, true)
        y += 62
        val title = when (v.type) {
            VoucherType.SALE -> if (vatReg) "TAX INVOICE" else "INVOICE"
            VoucherType.SALES_RETURN -> "CREDIT NOTE (SALES RETURN)"
            VoucherType.PURCHASE -> "PURCHASE ENTRY (internal record)"
            VoucherType.PURCHASE_RETURN -> "DEBIT NOTE (PURCHASE RETURN)"
            VoucherType.EXPENSE -> "EXPENSE VOUCHER (internal record)"
            VoucherType.STOCK_ADJUSTMENT -> "STOCK ADJUSTMENT (internal record)"
        }
        c.line(margin, y, pw - margin, y, 0.8f); y += 4
        c.text(margin + (w - c.textWidth(title, 13f, true)) / 2, y + 14, title, 13f, true); y += 22
        if (v.status != VoucherStatus.POSTED) {
            val mark = if (v.status == VoucherStatus.CANCELLED) "CANCELLED - ${v.cancelReason}" else "SUPERSEDED BY A CORRECTED VERSION"
            c.text(margin + (w - c.textWidth(mark, 11f, true)) / 2, y + 12, fit(c, mark, 11f, true, w), 11f, true); y += 18
        }
        // party and document info
        val leftTop = y
        val partyLabel = when (v.type) { VoucherType.SALE, VoucherType.SALES_RETURN -> "Buyer"; VoucherType.STOCK_ADJUSTMENT -> ""; else -> "Supplier / Payee" }
        if (partyLabel.isNotEmpty()) {
            c.text(margin, y + 10, "$partyLabel: ${v.partyName}", 10f, true); y += 13
            if (v.partyAddress.isNotEmpty()) { c.text(margin, y + 9, "Address: ${v.partyAddress}", 9f); y += 12 }
            c.text(margin, y + 9, "PAN/VAT No.: " + v.partyPan.ifEmpty { "-" }, 9f); y += 12
        }
        var ry = leftTop
        val rx = margin + w * 0.6f
        fun right(k: String, value: String) { c.text(rx, ry + 9, "$k: $value", 9f); ry += 12 }
        right(if (v.type == VoucherType.SALE) "Invoice No." else "No.", v.number)
        right("Date", "${BsDate.fromAd(v.date)} BS")
        if (s.printAdDates) right("Date (AD)", v.date.toString())
        if (v.refNo.isNotEmpty()) right(if (v.type == VoucherType.PURCHASE || v.type == VoucherType.EXPENSE) "Supplier bill No." else "Reference", v.refNo)
        if (v.originalVoucherId != null) right("Against invoice", b.docs.voucher(v.originalVoucherId).number)
        y = maxOf(y, ry) + 6
        if (v.type == VoucherType.EXPENSE) {
            val cat = v.expenseCategoryId?.let { b.masters.expenseCategory(it).name } ?: ""
            val rows = listOf("Category" to cat, "Description" to v.description, "Amount before VAT" to Money.format(v.taxable), "VAT" to Money.format(v.vat),
                "VAT treatment" to v.vatClass.label, "Total" to Money.format(v.total))
            for ((k, value) in rows) { c.text(margin, y + 10, k, 10f, true); c.text(margin + 140, y + 10, value, 10f); y += 15 }
        } else {
            val cols = listOf("S.N." to 0.5f, "Particulars" to 3.2f, "Qty" to 0.9f, "Unit" to 0.7f, "Rate" to 1.1f, "Discount" to 1f, "Taxable" to 1.2f, "VAT%" to 0.6f, "Amount" to 1.3f)
            val tw = cols.sumOf { it.second.toDouble() }.toFloat()
            val ws = cols.map { it.second / tw * w }
            val fs = 8.5f
            c.fillRect(margin, y, w, 15f, 0.88f)
            var x = margin
            for ((i, col) in cols.withIndex()) {
                val tx = if (i >= 2 && i != 3) x + ws[i] - 2 - c.textWidth(col.first, fs, true) else x + 2
                c.text(tx, y + 11, col.first, fs, true); x += ws[i]
            }
            y += 17
            val units = b.masters.units().associateBy { it.id }
            for (l in lines) {
                if (y > ph - 200) { c.text(margin, y + 10, "(continued on next page)", 8f, gray = true); c.startPage(pw, ph); y = margin }
                val p = l.productId?.let { b.masters.product(it) }
                val u = l.unitId?.let { units[it] }
                val name = (p?.name ?: "") + (if (l.description.isNotEmpty()) " - ${l.description}" else "")
                val cells = listOf(l.lineNo.toString(), name, Qty.format(Math.abs(l.qty), u?.decimals ?: 3), u?.name ?: "", Money.format(l.rate),
                    if (l.discount == 0L) "" else Money.format(l.discount), Money.format(l.taxable),
                    if (l.vatKind == VatKind.TAXABLE) Money.format(l.vatRateBp.toLong()).removeSuffix(".00") else if (l.vatKind == VatKind.EXEMPT) "Ex" else "0", Money.format(l.total))
                x = margin
                for ((i, cell) in cells.withIndex()) {
                    val t = fit(c, cell, fs, false, ws[i] - 4)
                    val tx = if (i >= 2 && i != 3) x + ws[i] - 2 - c.textWidth(t, fs) else x + 2
                    c.text(tx, y + 10, t, fs); x += ws[i]
                }
                y += 14
                c.line(margin, y, pw - margin, y, 0.2f)
            }
            y += 6
            val tx = margin + w * 0.55f
            fun tot(k: String, value: Long, bold: Boolean = false) {
                c.text(tx, y + 10, k, 9.5f, bold); val sv = Money.format(value); c.text(pw - margin - 2 - c.textWidth(sv, 9.5f, bold), y + 10, sv, 9.5f, bold); y += 14
            }
            tot("Gross amount", v.gross)
            if (v.discount != 0L) tot("Less: discount", v.discount)
            tot("Taxable amount", lines.filter { it.vatKind == VatKind.TAXABLE }.sumOf { it.taxable })
            val nonTax = lines.filter { it.vatKind != VatKind.TAXABLE }.sumOf { it.taxable }
            if (nonTax != 0L) tot("Exempt / zero-rated amount", nonTax)
            lines.filter { it.vatKind == VatKind.TAXABLE }.groupBy { it.vatRateBp }.forEach { (r, ls) -> tot("VAT @ ${Money.format(r.toLong()).removeSuffix(".00")}%", ls.sumOf { it.vat }) }
            c.line(tx, y, pw - margin, y, 0.6f)
            tot(if (v.type == VoucherType.SALE) "Grand total (NPR)" else "Total (NPR)", v.total, true)
        }
        y += 4
        for (l in wrap(c, "In words: " + Money.words(v.total), 9f, w)) { c.text(margin, y + 10, l, 9f, true); y += 12 }
        if (v.notes.isNotEmpty()) for (l in wrap(c, "Notes: " + v.notes, 8.5f, w)) { c.text(margin, y + 10, l, 8.5f); y += 11 }
        if (v.type == VoucherType.SALE || v.type == VoucherType.SALES_RETURN) {
            val terms = s.get("invoice_terms")
            if (terms.isNotEmpty()) { y += 4; for (l in wrap(c, "Terms: $terms", 8f, w)) { c.text(margin, y + 9, l, 8f); y += 10 } }
            y = maxOf(y + 30, ph - 120)
            c.line(pw - margin - 150, y, pw - margin, y, 0.5f)
            c.text(pw - margin - 150, y + 11, "Authorised signature", 8.5f)
            c.line(margin, y, margin + 150, y, 0.5f)
            c.text(margin, y + 11, "Received by", 8.5f)
            val footer = s.get("invoice_footer")
            if (footer.isNotEmpty()) { val fl = fit(c, footer, 9f, false, w); c.text(margin + (w - c.textWidth(fl, 9f)) / 2, y + 36, fl, 9f) }
        }
        c.text(margin, ph - margin + 10, printedOn() + (if (v.status == VoucherStatus.POSTED) "" else "  -  ${v.status.name}"), 7f, gray = true)
        return c.finish()
    }
}
