package np.psy.accounting.screens

import android.app.AlertDialog
import android.view.View
import android.widget.*
import np.psy.accounting.Exporter
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.Books
import np.psy.core.db.UserError
import np.psy.core.db.longOrNull
import np.psy.core.model.*
import np.psy.core.util.Money
import np.psy.core.util.Qty
import java.time.LocalDate
import java.util.UUID

/** Runs a posting in the background; if stock would go negative, asks the user and retries with confirmation. */
fun <T> MainActivity.postWithStockCheck(attempt: (Boolean) -> T, done: (T) -> Unit) {
    working("Saving...", {
        try { Result.success(attempt(false)) } catch (e: NeedsStockConfirmation) { Result.failure<T>(e) }
    }) { r ->
        r.fold(done) { e ->
            val s = (e as NeedsStockConfirmation).shortages
            val msg = "There is not enough stock for:\n\n" + s.joinToString("\n") { "- ${it.productName}: available ${qty(it.available)} ${it.unit}, needed ${qty(it.required)} ${it.unit}" } +
                "\n\nIf you continue, the stock will show as NEGATIVE until the missing purchase or a stock adjustment is entered. The cost of these items is provisional until then."
            confirm("Insufficient stock", msg, yes = "Continue anyway", no = "Go back") { working("Saving...", { attempt(true) }, done) }
        }
    }
}

/** Accounts that can be used to settle a document immediately (cash, bank, other current accounts). */
fun settleAccounts(b: Books): List<Account> {
    val skip = setOf("INVENTORY", "VAT_INPUT", "VAT_INPUT_PENDING", "VAT_OUTPUT", "RECEIVABLES", "PAYABLES")
    return b.masters.accounts(activeOnly = true).filter { (it.type == AccountType.ASSET || it.type == AccountType.LIABILITY) && it.systemKey !in skip }
        .sortedBy { when (it.systemKey) { "CASH" -> 0; "BANK" -> 1; else -> 2 } }
}

// ======================= Document list =======================

class DocListScreen(act: MainActivity, private val kind: String, private val partyId: Long? = null) : Screen(act) {
    companion object { const val SALES = "SALES"; const val PURCHASES = "PURCHASES"; const val EXPENSES = "EXPENSES"; const val ADJUSTMENTS = "ADJUSTMENTS" }
    private val types = when (kind) {
        SALES -> listOf(VoucherType.SALE, VoucherType.SALES_RETURN)
        PURCHASES -> listOf(VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN)
        EXPENSES -> listOf(VoucherType.EXPENSE)
        else -> listOf(VoucherType.STOCK_ADJUSTMENT)
    }
    private var search = ""
    private var postedOnly = false
    override val title get() = when (kind) { SALES -> "Sales"; PURCHASES -> "Purchases"; EXPENSES -> "Expenses"; else -> "Stock adjustments" } +
        (partyId?.let { " - " + books.masters.party(it).name } ?: "")
    override val actions get() = when (kind) {
        SALES -> listOf<Pair<String, () -> Unit>>("New sales invoice" to { push(DocEntryScreen(act, VoucherType.SALE)) }, "New sales return" to { chooseOriginal(VoucherType.SALE) })
        PURCHASES -> listOf<Pair<String, () -> Unit>>("New purchase" to { push(DocEntryScreen(act, VoucherType.PURCHASE)) }, "New purchase return" to { chooseOriginal(VoucherType.PURCHASE) })
        EXPENSES -> listOf<Pair<String, () -> Unit>>("New expense" to { push(ExpenseEntryScreen(act)) })
        else -> listOf<Pair<String, () -> Unit>>("New stock adjustment" to { push(DocEntryScreen(act, VoucherType.STOCK_ADJUSTMENT)) })
    }

    private fun chooseOriginal(t: VoucherType) {
        ctx.prompt("Return against which ${if (t == VoucherType.SALE) "invoice" else "purchase"}?", "Enter the invoice number, or part of the ${if (t == VoucherType.SALE) "customer" else "supplier"} name.", "Number or name") { q ->
            val found = books.docs.search(listOf(t), text = q, includeInactive = false, limit = 50)
            if (found.isEmpty()) ctx.info("Not found", "No posted document matches '$q'.")
            else ctx.pick("Choose the original", found, { "${it.number}  ${bs(it.date)}  ${it.partyName}  ${money(it.total)}" }) {
                push(DocEntryScreen(act, if (t == VoucherType.SALE) VoucherType.SALES_RETURN else VoucherType.PURCHASE_RETURN, returnOf = it.id))
            }
        }
    }

    override fun build(): View {
        val page = ListPage(ctx)
        val h = page.header
        val bar = ctx.hbox()
        val e = EditText(ctx).apply { hint = "Search number, name, PAN, bill no."; setText(search); setSingleLine(true); textSize = 15f }
        bar.addView(e, lp(0, WRAP, 1f))
        bar.addView(Button(ctx).apply { text = "Search"; isAllCaps = false; setOnClickListener { search = e.str; refresh() } }, LinearLayout.LayoutParams(WRAP, WRAP))
        h.addView(bar, lp())
        val cb = h.check("Hide cancelled and replaced entries", postedOnly)
        cb.setOnCheckedChangeListener { _, c -> postedOnly = c; refresh() }
        val row = ctx.hbox()
        actions.forEach { (label, f) -> row.button(label, true) { f() } }
        h.addView(row, lp())
        val items = books.docs.search(types, text = search, partyId = partyId, includeInactive = !postedOnly, limit = 1000)
        page.show(items, "Nothing recorded yet.", { v, _ ->
            val status = when (v.status) { VoucherStatus.POSTED -> ""; VoucherStatus.CANCELLED -> "CANCELLED"; VoucherStatus.SUPERSEDED -> "REPLACED (edited)" }
            val typeLabel = if (types.size > 1) v.type.label + "  " else ""
            ctx.listRow(v.number, money(v.total), "$typeLabel${bs(v.date)} BS  ${v.partyName.ifEmpty { v.description }}",
                if (v.type == VoucherType.SALES_RETURN || v.type == VoucherType.PURCHASE_RETURN) C.WARN else C.TEXT, status, v.status != VoucherStatus.POSTED)
        }) { push(VoucherViewScreen(act, it.id)) }
        return page.root
    }
}

// ======================= View one document =======================

class VoucherViewScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title get() = books.docs.voucher(id).let { "${it.type.label} ${it.number}" }
    override val actions get() = listOf<Pair<String, () -> Unit>>("PDF: save / share / print" to { pdf() })

    private fun pdf() {
        val v = books.docs.voucher(id)
        act.working("Preparing PDF...", { Exporter.documentPdf(id) }) { Exporter.offer(act, Exporter.safeName("${v.number}") + ".pdf", "application/pdf", it) }
    }

    override fun build(): View {
        val b = books
        val v = b.docs.voucher(id)
        val box = ctx.vbox(12)
        when (v.status) {
            VoucherStatus.CANCELLED -> box.warning("CANCELLED" + (v.cancelledAt?.let { " on ${bs(LocalDate.parse(it.substring(0, 10)))} BS" } ?: "") + ". Reason: ${v.cancelReason}")
            VoucherStatus.SUPERSEDED -> box.warning("This is an OLD VERSION. It was edited and replaced.")
            else -> {}
        }
        box.card {
            text(v.number, 20f, true, C.PRIMARY_DARK)
            text(v.type.label, 14f, false, C.MUTED)
            kv("Date", bsAd(v.date))
            if (v.partyName.isNotEmpty()) kv(if (v.type == VoucherType.SALE || v.type == VoucherType.SALES_RETURN) "Customer" else "Supplier", v.partyName)
            if (v.partyPan.isNotEmpty()) kv("PAN", v.partyPan)
            if (v.partyAddress.isNotEmpty()) kv("Address", v.partyAddress)
            if (v.refNo.isNotEmpty()) kv(if (v.type == VoucherType.PURCHASE || v.type == VoucherType.EXPENSE) "Supplier bill no." else "Reference", v.refNo)
            v.originalVoucherId?.let { oid -> val o = b.docs.voucher(oid); kv("Against", "${o.number} (${bs(o.date)})") }
            if (v.type == VoucherType.EXPENSE) v.expenseCategoryId?.let { kv("Category", b.masters.expenseCategory(it).name) }
            if (v.description.isNotEmpty()) kv(if (v.type == VoucherType.STOCK_ADJUSTMENT) "Reason" else "Description", v.description)
            if (v.type != VoucherType.STOCK_ADJUSTMENT) kv("Settled through", v.settleAccountId?.let { b.masters.account(it).name } ?: when (v.type) {
                VoucherType.SALE, VoucherType.SALES_RETURN -> "Customer account (receivable)"; else -> "Supplier account (payable)" })
            if (v.vatClass != VatClass.NONE) kv("Input VAT", v.vatClass.label)
        }
        val lines = b.docs.lines(id)
        if (lines.isNotEmpty()) {
            box.heading("Items")
            for (l in lines) box.card {
                val p = l.productId?.let { b.masters.product(it) }
                val u = l.unitId?.let { b.masters.unit(it).name } ?: ""
                text(p?.name ?: l.description, 15f, true)
                if (v.type == VoucherType.STOCK_ADJUSTMENT) {
                    kv(if (l.baseQty >= 0) "Added" else "Removed", "${qty(Math.abs(l.qty))} $u")
                    kv("Value", money(l.cost))
                } else {
                    kv("Quantity", "${qty(l.qty)} $u @ ${money(l.rate)}")
                    if (l.discount != 0L) kv("Discount", money(l.discount))
                    kv("Taxable / VAT", "${money(l.taxable)} / ${money(l.vat)}" + if (l.vatKind != VatKind.TAXABLE) " (${l.vatKind.label})" else "")
                    kv("Line total", money(l.total), true)
                    if (v.type == VoucherType.SALE && v.status == VoucherStatus.POSTED) kv("Cost / profit", "${money(l.cost)} / ${money(l.taxable - l.cost)}", color = if (l.taxable < l.cost) C.WARN else C.TEXT)
                }
            }
        }
        box.card {
            if (v.type != VoucherType.STOCK_ADJUSTMENT) {
                kv("Gross", money(v.gross)); if (v.discount != 0L) kv("Discount", money(v.discount))
                kv("Taxable amount", money(v.taxable)); kv("VAT", money(v.vat))
                kv("Total", npr(v.total), true)
                note(Money.words(v.total))
            } else kv("Net value", money(lines.sumOf { it.cost }), true)
        }
        if (v.notes.isNotEmpty()) box.note("Notes: ${v.notes}")

        if (v.status == VoucherStatus.POSTED) {
            box.button("PDF: save / share / print") { pdf() }
            box.button("Edit (correct this entry)", false) {
                if (v.type == VoucherType.EXPENSE) push(ExpenseEntryScreen(act, amendOf = id))
                else push(DocEntryScreen(act, v.type, amendOf = id, returnOf = v.originalVoucherId))
            }
            if (v.type == VoucherType.SALE) box.button("Record a sales return against this", false) { push(DocEntryScreen(act, VoucherType.SALES_RETURN, returnOf = id)) }
            if (v.type == VoucherType.PURCHASE) box.button("Record a purchase return against this", false) { push(DocEntryScreen(act, VoucherType.PURCHASE_RETURN, returnOf = id)) }
            box.button("Cancel this entry", false) {
                ctx.prompt("Cancel ${v.number}?", "The entry stays on record marked CANCELLED and its accounting effect is reversed. This needs a reason.", "Reason for cancelling") { reason ->
                    if (reason.isBlank()) throw UserError("Enter a reason for cancelling")
                    act.postWithStockCheck({ allow -> b.docs.cancel(id, reason, allow) }) { w ->
                        ctx.info("Cancelled", "${v.number} has been cancelled." + if (w.isNotEmpty()) "\n\n" + w.joinToString("\n") else "") { refresh() }
                    }
                }
            }
        } else box.button("PDF (marked as not current)", false) { pdf() }
        v.replacedById?.let { nid -> box.button("Open the current version", false) { act.replace(VoucherViewScreen(act, nid)) } }
        v.replacesId?.let { oid -> box.button("Open the previous version", false) { push(VoucherViewScreen(act, oid)) } }
        val returns = b.docs.search(listOf(VoucherType.SALES_RETURN, VoucherType.PURCHASE_RETURN), includeInactive = false).filter { it.originalVoucherId == id }
        for (r in returns) box.button("Return ${r.number} (${money(r.total)})", false) { push(VoucherViewScreen(act, r.id)) }
        val js = b.journals.forVoucher(id)
        if (js.isNotEmpty()) {
            box.heading("Accounting entries")
            for (j in js) box.button("${j.number}  ${j.type.lowercase()}  ${j.status.lowercase()}", false) { push(JournalViewScreen(act, j.id)) }
        }
        box.heading("History")
        for (a in b.audit.recent(50, "voucher", id)) box.note("${a.at.replace('T', ' ')}  ${a.summary}" + if (a.reason.isNotEmpty()) " - ${a.reason}" else "")
        return ctx.scroll(box)
    }
}

// ======================= Sales / purchase / return / adjustment entry =======================

class DocEntryScreen(act: MainActivity, private val type: VoucherType, private val amendOf: Long? = null, private val returnOf: Long? = null) : Screen(act) {
    private class LineState(var productId: Long, var unitId: Long?, var qty: Long, var rate: Long, var discount: Long, var vatCategoryId: Long?,
                            val originalLineId: Long? = null, val maxQty: Long = 0, val origVatRate: Int = 0, val origVatKind: VatKind? = null, val origQty: Long = 0, val origDiscount: Long = 0)

    private val token = UUID.randomUUID().toString()
    private val lines = ArrayList<LineState>()
    private var root: View? = null
    private lateinit var linesBox: LinearLayout
    private lateinit var totalsBox: LinearLayout
    private lateinit var date: BsDateField
    private var partyField: AutoCompleteTextView? = null
    private var panField: EditText? = null
    private var addrField: EditText? = null
    private var refField: EditText? = null
    private var numberField: EditText? = null
    private var settle: Choice<Account?>? = null
    private var vatClass: Choice<VatClass>? = null
    private var adjIn: Choice<Boolean>? = null
    private lateinit var desc: EditText
    private lateinit var notes: EditText
    private val isReturn = type == VoucherType.SALES_RETURN || type == VoucherType.PURCHASE_RETURN
    private val isSale = type == VoucherType.SALE || type == VoucherType.SALES_RETURN
    private val isAdj = type == VoucherType.STOCK_ADJUSTMENT

    override val title get() = (if (amendOf != null) "Edit " else "New ") + type.label.lowercase()
    override fun onReturn() { root?.let { renderLines() } ?: refresh() }
    override fun build(): View = root ?: create().also { root = it }

    private fun partyId(): Long? = partyField?.str?.takeIf { it.isNotEmpty() }?.let { books.masters.partyByName(it)?.id }

    private fun create(): View {
        val b = books
        val box = ctx.vbox(12)
        val old = amendOf?.let { b.docs.voucher(it) }
        val orig = returnOf?.let { b.docs.voucher(it) }
        if (old != null) box.warning("You are editing ${old.number}. The original stays on record as replaced, and a corrected version is posted" + (if (type == VoucherType.SALE) " with the same number." else "."))

        date = BsDateField(box, "Date", old?.date ?: b.today())
        if (!isAdj && type != VoucherType.EXPENSE) {
            val numberNote = box.note("")
            fun upd() { val d = date.valueOrNull(); numberNote.text = if (old != null) "Number: ${old.number}" else if (d != null) "Number: ${try { b.years.previewNumber(type.name, d) } catch (e: Exception) { "-" }} (automatic)" else "" }
            date.onChange = { upd() }; upd()
            if (type == VoucherType.SALE && old == null)
                numberField = box.field("Invoice number (leave blank for automatic; type it only if using a pre-printed invoice book)", "")
        }

        if (isReturn) {
            box.card {
                text("Against ${orig!!.number} dated ${bs(orig.date)} BS", 15f, true)
                kv(if (isSale) "Customer" else "Supplier", orig.partyName)
                kv("Original total", money(orig.total))
            }
        } else if (!isAdj) {
            val parties = b.masters.parties(customers = if (isSale) true else null, suppliers = if (!isSale) true else null).filter { it.active }
            partyField = box.auto(if (isSale) "Customer name" else "Supplier name", old?.partyName ?: "", parties.map { it.name })
            panField = box.field("PAN / VAT number (9 digits)", old?.partyPan ?: "", type = INT)
            addrField = box.field("Address", old?.partyAddress ?: "")
            partyField!!.setOnItemClickListener { _, _, _, _ ->
                b.masters.partyByName(partyField!!.str)?.let { p -> panField!!.setText(p.pan); addrField!!.setText(p.address) }
            }
            if (type == VoucherType.PURCHASE) refField = box.field("Supplier's invoice / bill number", old?.refNo ?: "")
            else refField = box.field("Customer reference / order no. (optional)", old?.refNo ?: "")
            val accs: List<Account?> = listOf<Account?>(null) + settleAccounts(b)
            settle = box.choice(if (isSale) "Received through" else "Paid through", accs, old?.settleAccountId?.let { id -> accs.firstOrNull { it?.id == id } }) {
                it?.name ?: if (isSale) "On credit (customer owes)" else "On credit (we owe supplier)"
            }
            if (type == VoucherType.PURCHASE) vatClass = box.choice("Input VAT on this bill", listOf(VatClass.ELIGIBLE, VatClass.INELIGIBLE, VatClass.PENDING),
                old?.vatClass?.takeIf { it != VatClass.NONE } ?: VatClass.ELIGIBLE) { it.label }
        }
        if (isAdj) {
            adjIn = box.choice("Adjustment", listOf(true, false), old?.let { o -> b.docs.lines(o.id).firstOrNull()?.baseQty?.let { it > 0 } } ?: true) {
                if (it) "Stock IN (found / excess / received free)" else "Stock OUT (damaged / lost / shortage / own use)"
            }
            adjIn!!.onChange { renderLines() }
        }
        desc = box.field(if (isAdj) "Reason for adjustment" else "Description (optional)", old?.description ?: "")

        box.heading("Items")
        linesBox = ctx.vbox(0); box.addView(linesBox, lp())
        if (!isReturn) box.button("+ Add item", false) { lineDialog(null) }
        totalsBox = ctx.vbox(0); box.addView(totalsBox, lp())
        notes = box.field("Notes (printed on the document)", old?.notes ?: "", lines = 2)

        // initial lines
        if (isReturn) {
            val left = b.docs.returnableQty(orig!!.id)
            val oldLines = old?.let { b.docs.lines(it.id) } ?: emptyList()
            for (ol in b.docs.lines(orig.id)) {
                val mine = oldLines.firstOrNull { it.originalLineId == ol.id }
                val maxBase = (left[ol.id] ?: 0L) + (mine?.baseQty ?: 0L)
                val maxQty = Money.mulDiv(maxBase, 1000, ol.factor)
                lines.add(LineState(ol.productId!!, ol.unitId, mine?.qty ?: 0L, ol.rate, 0, null, ol.id, maxQty, ol.vatRateBp, ol.vatKind, ol.qty, ol.discount))
            }
        } else if (old != null) {
            for (l in b.docs.lines(old.id)) lines.add(LineState(l.productId!!, l.unitId, Math.abs(l.qty), if (isAdj) (if (l.baseQty > 0) l.rate else 0) else l.rate, l.discount, l.vatCategoryId))
        }
        renderLines()

        val btns = ctx.hbox()
        btns.button(if (old != null) "Save correction" else "Save") { save() }
        btns.button("Cancel", false) { ctx.confirm("Discard this entry?", "Nothing will be saved.", "Discard", "Keep editing") { back() } }
        box.addView(btns, lp(top = ctx.dp(12)))
        return ctx.scroll(box)
    }

    private fun companyChargesVat() = books.settings.get("vat_registration_status") != "PAN_ONLY"

    private fun lineCalc(l: LineState): LongArray {
        val gross = Qty.amount(l.qty, l.rate)
        val disc = if (l.originalLineId != null && l.origQty > 0) Money.mulDiv(l.origDiscount, l.qty, l.origQty) else l.discount
        val taxable = gross - disc
        val (kind, rate) = if (l.originalLineId != null) (l.origVatKind ?: VatKind.EXEMPT) to l.origVatRate
            else books.masters.vatCategory(l.vatCategoryId ?: books.masters.product(l.productId).vatCategoryId).let { it.kind to it.rateBp }
        var vat = if (kind == VatKind.TAXABLE) Money.vat(taxable, rate) else 0L
        if (isSale && !companyChargesVat()) vat = 0
        return longArrayOf(gross, disc, taxable, vat)
    }

    private fun renderLines() {
        val b = books
        linesBox.removeAllViews()
        if (lines.isEmpty()) linesBox.note("No items yet. Tap '+ Add item'.")
        var g = 0L; var d = 0L; var t = 0L; var v = 0L
        for ((i, l) in lines.withIndex()) {
            val p = b.masters.product(l.productId)
            val u = b.masters.unit(l.unitId ?: p.unitId)
            if (isReturn) {
                linesBox.card {
                    text(p.name, 15f, true)
                    note("Sold/bought at ${money(l.rate)} per ${u.name}. Can return up to ${qty(l.maxQty)} ${u.name}.")
                    val e = field("Quantity to return (${u.name})", qtyInput(l.qty), type = NUM)
                    e.onText { s -> l.qty = try { Qty.parse(s, u.decimals) } catch (ex: Exception) { 0 }; renderTotals() }
                }
                continue
            }
            val c = lineCalc(l)
            g += c[0]; d += c[1]; t += c[2]; v += c[3]
            linesBox.card {
                text("${i + 1}. ${p.name}", 15f, true)
                if (isAdj) {
                    kv("Quantity", "${qty(l.qty)} ${u.name}")
                    if (adjIn?.selected == true) kv("Unit cost", if (l.rate == 0L) "current average" else money(l.rate))
                } else {
                    kv("Quantity x rate", "${qty(l.qty)} ${u.name} x ${money(l.rate)}")
                    if (c[1] != 0L) kv("Discount", money(c[1]))
                    kv("VAT", money(c[3]))
                    kv("Amount", money(c[2] + c[3]), true)
                }
                val r = context.hbox()
                r.button("Edit", false) { lineDialog(i) }
                r.button("Remove", false) { lines.removeAt(i); renderLines() }
                addView(r, lp())
            }
        }
        renderTotals()
    }

    private fun renderTotals() {
        totalsBox.removeAllViews()
        if (isAdj) return
        var g = 0L; var d = 0L; var t = 0L; var v = 0L
        for (l in lines) { if (l.qty == 0L) continue; val c = lineCalc(l); g += c[0]; d += c[1]; t += c[2]; v += c[3] }
        totalsBox.card {
            kv("Gross", money(g)); if (d != 0L) kv("Discount", money(d))
            kv("Taxable amount", money(t)); kv("VAT", money(v))
            kv("Total", npr(t + v), true)
            if (isSale && !companyChargesVat()) note("Company is set as PAN only, so no VAT is charged.")
        }
    }

    private fun productLabel(p: Product) = "${p.name}  [${p.code}]"

    private fun resolveProduct(text: String, all: List<Product>): Product? {
        val t = text.trim(); if (t.isEmpty()) return null
        return all.firstOrNull { productLabel(it) == t } ?: all.firstOrNull { it.name.equals(t, true) } ?: all.firstOrNull { it.code.equals(t, true) }
    }

    private fun lastPurchaseRate(pid: Long): Long? = books.db.longOrNull(
        "SELECT l.rate * 1000 / l.factor FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id WHERE v.type = 'PURCHASE' AND v.status = 'POSTED' AND l.product_id = ? ORDER BY v.date DESC, v.seq DESC LIMIT 1", pid)

    /** Add or edit one item line. */
    private fun lineDialog(index: Int?) {
        val b = books
        val existing = index?.let { lines[it] }
        val products = b.masters.products(activeOnly = true).toMutableList()
        existing?.let { e -> if (products.none { it.id == e.productId }) products.add(b.masters.product(e.productId)) }
        val box = ctx.vbox(16)
        val prodField = box.auto("Product (type name or code)", existing?.let { productLabel(b.masters.product(it.productId)) } ?: "", products.map { productLabel(it) })
        box.button("+ New product", false) {
            ProductQuickCreate.show(act) { p -> products.add(p); prodField.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, products.map { productLabel(it) })); prodField.setText(productLabel(p)); prodField.dismissDropDown() }
        }
        val info = box.note("")
        box.text("Unit", 13f, false, C.MUTED)
        val unitSpinner = Spinner(ctx); box.addView(unitSpinner, lp())
        var unitIds: List<Long> = emptyList()
        val qtyField = box.field("Quantity", existing?.let { qtyInput(it.qty) } ?: "", type = NUM)
        val adjOut = isAdj && adjIn?.selected == false
        val rateField = if (adjOut) null else box.field(if (isAdj) "Unit cost (leave blank to use current average cost)" else "Rate per unit (excluding VAT)", existing?.let { moneyInput(it.rate) } ?: "", type = NUM)
        val discField = if (isAdj) null else box.field("Discount amount (optional)", existing?.let { moneyInput(it.discount) } ?: "", type = NUM)
        val cats = b.masters.vatCategories(activeOnly = true)
        val vatChoice = if (isAdj) null else box.choice("VAT", cats, null) { "${it.name} (${it.kind.label})" }
        val amount = box.text("", 15f, true)
        var current: Product? = null

        fun factor(): Long = current?.let { p -> try { b.masters.factorFor(p, unitIds.getOrNull(unitSpinner.selectedItemPosition)) } catch (e: Exception) { 1000L } } ?: 1000L
        fun suggestRate(): Long? {
            val p = current ?: return null
            val base = when {
                type == VoucherType.SALE -> b.masters.suggestedPrice(partyId(), p.id)
                type == VoucherType.PURCHASE -> lastPurchaseRate(p.id)
                isAdj -> null
                else -> null
            } ?: return null
            return Money.mulDiv(base, factor(), 1000)
        }
        fun updAmount() {
            val q = try { Qty.parse(qtyField.str, 3) } catch (e: Exception) { 0 }
            val r = try { Money.parse(rateField?.str ?: "") } catch (e: Exception) { 0 }
            val d = try { Money.parse(discField?.str ?: "") } catch (e: Exception) { 0 }
            val gross = Qty.amount(q, r)
            val cat = vatChoice?.selected
            var vat = if (cat != null && cat.kind == VatKind.TAXABLE) Money.vat(gross - d, cat.rateBp) else 0L
            if (isSale && !companyChargesVat()) vat = 0
            amount.text = if (isAdj) "" else "Amount: ${money(gross - d)} + VAT ${money(vat)} = ${money(gross - d + vat)}"
        }
        fun onProduct(p: Product?, keepRate: Boolean) {
            current = p
            if (p == null) { info.text = ""; unitIds = emptyList(); unitSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, emptyList<String>()); return }
            val conv = b.masters.productUnits(p.id)
            unitIds = listOf(p.unitId) + conv.map { it.unitId }
            val base = b.masters.unit(p.unitId).name
            unitSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf(base) + conv.map { "${b.masters.unit(it.unitId).name} (= ${qty(it.factor)} $base)" })
            existing?.unitId?.let { u -> unitIds.indexOf(u).takeIf { it >= 0 }?.let { unitSpinner.setSelection(it) } }
            val (sq, sv) = b.costing.balanceAt(p.id, null)
            val avg = b.costing.averageCost(p.id)
            info.text = "In stock: ${qty(sq)} $base" + (if (!isSale) "  |  Average cost ${money(avg)}" else "") + (if (sq < 0) "  (NEGATIVE)" else "") +
                (if (type == VoucherType.SALE && avg > 0) "\nAverage cost per $base: ${money(avg)}" else "")
            info.setTextColor(if (sq <= 0 && (type == VoucherType.SALE || adjOut)) C.WARN else C.MUTED)
            vatChoice?.select(cats.firstOrNull { it.id == (existing?.takeIf { it.productId == p.id }?.vatCategoryId ?: p.vatCategoryId) })
            if (!keepRate && rateField != null) suggestRate()?.let { rateField.setText(moneyInput(it)) }
            @Suppress("UNUSED_VARIABLE") val unused = sv
            updAmount()
        }
        prodField.setOnItemClickListener { _, _, _, _ -> safely(ctx) { onProduct(resolveProduct(prodField.str, products), false) } }
        unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                if (rateField != null) suggestRate()?.let { rateField.setText(moneyInput(it)) }
                updAmount()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        qtyField.onText { updAmount() }; rateField?.onText { updAmount() }; discField?.onText { updAmount() }
        vatChoice?.onChange { updAmount() }
        if (existing != null) onProduct(b.masters.product(existing.productId), true)

        val dlg = AlertDialog.Builder(ctx).setTitle(if (index == null) "Add item" else "Edit item").setView(ctx.scroll(box))
            .setPositiveButton("OK", null).setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely(ctx) {
                val p = resolveProduct(prodField.str, products) ?: throw UserError("Choose a product from the list, or tap '+ New product'")
                if (current?.id != p.id) onProduct(p, true)
                val unitId = unitIds.getOrNull(unitSpinner.selectedItemPosition) ?: p.unitId
                val u = b.masters.unit(unitId)
                val q = Qty.parse(qtyField.str, u.decimals)
                if (q <= 0) throw UserError("Enter the quantity")
                val r = Money.parse(rateField?.str ?: "", "Rate")
                if (!isAdj && r <= 0) throw UserError("Enter the rate")
                val d = Money.parse(discField?.str ?: "", "Discount")
                if (d < 0 || d > Qty.amount(q, r)) throw UserError("Discount must be between zero and the line amount")
                val vatId = vatChoice?.selected?.id
                val ls = LineState(p.id, unitId, q, r, d, if (vatId == p.vatCategoryId) null else vatId)
                if (index == null) lines.add(ls) else lines[index] = ls
                dlg.dismiss()
                renderLines()
            }
        }
    }

    private fun buildInput(allowNeg: Boolean): DocInput {
        val b = books
        val d = date.value()
        val inLines = lines.filter { !isReturn || it.qty > 0 }.map { l ->
            val disc = if (l.originalLineId != null && l.origQty > 0) Money.mulDiv(l.origDiscount, l.qty, l.origQty) else l.discount
            LineInput(l.productId, l.qty, if (isAdj && adjIn?.selected == false) 0 else l.rate, disc, l.unitId, l.vatCategoryId, l.originalLineId)
        }
        if (isReturn) {
            if (inLines.isEmpty()) throw UserError("Enter the quantity returned for at least one item")
            for (l in lines) if (l.qty > l.maxQty) throw UserError("${b.masters.product(l.productId).name}: only ${qty(l.maxQty)} can be returned")
        }
        return DocInput(type, d, partyName = partyField?.str ?: "", partyPan = panField?.str ?: "", partyAddress = addrField?.str ?: "",
            refNo = refField?.str ?: "", number = numberField?.str ?: "", originalVoucherId = returnOf, settleAccountId = settle?.selected?.id,
            vatClass = vatClass?.selected ?: VatClass.NONE, description = desc.str, notes = notes.str, postToken = if (amendOf == null) token else null,
            lines = inLines, adjustmentIn = adjIn?.selected ?: true, allowNegativeStock = allowNeg)
    }

    private fun save() {
        val input = buildInput(false) // read the form on the main thread; posting runs in the background
        if (amendOf != null) {
            ctx.prompt("Reason for the correction", "This is recorded in the audit trail.", "Reason") { reason ->
                if (reason.isBlank()) throw UserError("Enter a reason for the change")
                act.postWithStockCheck({ allow -> books.docs.amend(amendOf, input.copy(allowNegativeStock = allow), reason) }) { done(it) }
            }
        } else act.postWithStockCheck({ allow -> books.docs.post(input.copy(allowNegativeStock = allow)) }) { done(it) }
    }

    private fun done(r: PostResult) {
        val msg = "Saved as ${r.number}." + if (r.warnings.isNotEmpty()) "\n\nPlease note:\n" + r.warnings.joinToString("\n") { "- $it" } else ""
        AlertDialog.Builder(ctx).setTitle("Saved").setMessage(msg).setCancelable(false)
            .setPositiveButton("View") { _, _ -> act.replace(VoucherViewScreen(act, r.voucherId)) }
            .setNeutralButton(if (isReturn) "Done" else "New entry") { _, _ -> if (isReturn) act.replace(VoucherViewScreen(act, r.voucherId)) else act.replace(DocEntryScreen(act, type)) }
            .setNegativeButton("PDF") { _, _ ->
                act.replace(VoucherViewScreen(act, r.voucherId))
                act.working("Preparing PDF...", { Exporter.documentPdf(r.voucherId) }) { Exporter.offer(act, Exporter.safeName(r.number) + ".pdf", "application/pdf", it) }
            }.show()
    }
}

// ======================= Expense entry =======================

class ExpenseEntryScreen(act: MainActivity, private val amendOf: Long? = null) : Screen(act) {
    private val token = UUID.randomUUID().toString()
    private var root: View? = null
    override val title get() = if (amendOf != null) "Edit expense" else "New expense"
    override fun onReturn() { if (root == null) refresh() }
    override fun build(): View = root ?: create().also { root = it }

    private fun create(): View {
        val b = books
        val old = amendOf?.let { b.docs.voucher(it) }
        val box = ctx.vbox(12)
        if (old != null) box.warning("You are editing ${old.number}. The original stays on record as replaced.")
        val date = BsDateField(box, "Date", old?.date ?: b.today())
        val cats = b.masters.expenseCategories(activeOnly = true)
        val cat = box.choice("Expense category", cats, cats.firstOrNull { it.id == old?.expenseCategoryId }) { it.name }
        box.button("+ New category", false) {
            ctx.prompt("New expense category", "An expense account with the same name is created automatically.", "Name") { n ->
                b.masters.saveExpenseCategory(null, n, null); root = null; refresh()
            }
        }
        val desc = box.field("Description", old?.description ?: "")
        val suppliers = b.masters.parties(suppliers = true).filter { it.active }
        val party = box.auto("Supplier / paid to (optional)", old?.partyName ?: "", suppliers.map { it.name })
        val pan = box.field("Supplier PAN / VAT number (optional)", old?.partyPan ?: "", type = INT)
        party.setOnItemClickListener { _, _, _, _ -> b.masters.partyByName(party.str)?.let { pan.setText(it.pan) } }
        val bill = box.field("Bill / invoice number (optional)", old?.refNo ?: "")
        val amt = box.field("Amount before VAT (NPR)", old?.let { moneyInput(it.taxable) } ?: "", type = NUM)
        val vat = box.field("VAT amount on the bill (0 if none)", old?.let { moneyInput(it.vat) } ?: "", type = NUM)
        box.button("Fill VAT at 13%", false) { vat.setText(moneyInput(Money.vat(Money.parse(amt.str), 1300), false)) }
        val vc = box.choice("If the bill has VAT, can it be claimed as input credit?", listOf(VatClass.ELIGIBLE, VatClass.INELIGIBLE, VatClass.PENDING),
            old?.vatClass?.takeIf { it != VatClass.NONE } ?: VatClass.PENDING) { it.label }
        box.note("Eligible: valid tax invoice with supplier's VAT number, used for the business. Ineligible: VAT cannot be claimed (it becomes part of the expense). Pending: not yet verified - it is kept aside until you decide.")
        val accs: List<Account?> = listOf<Account?>(null) + settleAccounts(b)
        val settle = box.choice("Paid through", accs, old?.settleAccountId?.let { id -> accs.firstOrNull { it?.id == id } } ?: accs.firstOrNull { it?.systemKey == "CASH" }) { it?.name ?: "Not paid yet (payable)" }
        val total = box.text("", 16f, true)
        fun upd() { total.text = try { "Total: ${npr(Money.parse(amt.str) + Money.parse(vat.str))}" } catch (e: Exception) { "" } }
        amt.onText { upd() }; vat.onText { upd() }; upd()
        val notes = box.field("Notes", old?.notes ?: "", lines = 2)

        fun input() = DocInput(VoucherType.EXPENSE, date.value(), partyName = party.str, partyPan = pan.str, refNo = bill.str,
            settleAccountId = settle.selected?.id, vatClass = vc.selected ?: VatClass.PENDING, expenseCategoryId = cat.selected?.id ?: throw UserError("Choose a category"),
            description = desc.str, notes = notes.str, postToken = if (amendOf == null) token else null,
            expenseAmount = Money.parse(amt.str, "Amount"), expenseVat = Money.parse(vat.str, "VAT"))

        fun done(r: PostResult) {
            val msg = "Saved as ${r.number}." + if (r.warnings.isNotEmpty()) "\n\nPlease note:\n" + r.warnings.joinToString("\n") { "- $it" } else ""
            AlertDialog.Builder(ctx).setTitle("Saved").setMessage(msg).setCancelable(false)
                .setPositiveButton("View") { _, _ -> act.replace(VoucherViewScreen(act, r.voucherId)) }
                .setNeutralButton("New expense") { _, _ -> act.replace(ExpenseEntryScreen(act)) }.show()
        }
        val btns = ctx.hbox()
        btns.button("Save") {
            val i = input()
            if (amendOf != null) ctx.prompt("Reason for the correction", "", "Reason") { reason ->
                if (reason.isBlank()) throw UserError("Enter a reason for the change")
                act.working("Saving...", { b.docs.amend(amendOf, i, reason) }) { done(it) }
            } else act.working("Saving...", { b.docs.post(i) }) { done(it) }
        }
        btns.button("Cancel", false) { back() }
        box.addView(btns, lp(top = ctx.dp(12)))
        return ctx.scroll(box)
    }
}
