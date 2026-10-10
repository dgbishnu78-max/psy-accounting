package np.psy.core.service

import np.psy.core.Books
import np.psy.core.db.*
import np.psy.core.model.*
import np.psy.core.util.Money
import np.psy.core.util.Qty
import java.time.LocalDate

/**
 * Sales, purchases, returns, expenses and stock adjustments.
 * Posting, amending and cancelling each run in one database transaction together with
 * inventory costing, the accounting entries and the audit trail.
 */
class DocumentService(private val b: Books) {
    private val db get() = b.db

    private fun voucher(r: Row) = Voucher(
        r.long("id"), VoucherType.valueOf(r.string("type")), r.string("number"), LocalDate.parse(r.string("date")), r.long("seq"),
        VoucherStatus.valueOf(r.string("status")), r.longOrNull("party_id"), r.string("party_name"), r.string("party_pan"),
        r.string("party_address"), r.string("ref_no"), r.longOrNull("original_voucher_id"), r.longOrNull("settle_account_id"),
        r.longOrNull("expense_category_id"), VatClass.valueOf(r.string("vat_class")), r.string("description"), r.long("gross"),
        r.long("discount"), r.long("taxable"), r.long("vat"), r.long("total"), r.string("notes"), r.string("doc_ref"),
        r.longOrNull("replaces_id"), r.longOrNull("replaced_by_id"), r.string("cancel_reason"), r.stringOrNull("cancelled_at"), r.string("created_at"))

    private fun line(r: Row) = VoucherLine(
        r.long("id"), r.long("voucher_id"), r.int("line_no"), r.longOrNull("product_id"), r.string("description"), r.longOrNull("unit_id"),
        r.long("qty"), r.long("factor"), r.long("base_qty"), r.long("rate"), r.long("gross"), r.long("discount"), r.long("taxable"),
        r.longOrNull("vat_category_id"), VatKind.valueOf(r.string("vat_kind")), r.int("vat_rate_bp"), r.long("vat"), r.long("total"),
        r.long("cost"), r.longOrNull("original_line_id"))

    fun voucher(id: Long): Voucher = db.first("SELECT * FROM voucher WHERE id = ?", id, mapper = ::voucher) ?: throw UserError("Document not found")
    fun lines(voucherId: Long): List<VoucherLine> = db.list("SELECT * FROM voucher_line WHERE voucher_id = ? ORDER BY line_no", voucherId, mapper = ::line)
    fun byNumber(type: VoucherType, number: String): Voucher? =
        db.first("SELECT * FROM voucher WHERE type = ? AND number = ? AND status IN ('POSTED','CANCELLED')", type.name, number, mapper = ::voucher)

    fun search(types: Collection<VoucherType>, from: LocalDate? = null, to: LocalDate? = null, text: String = "",
               partyId: Long? = null, includeInactive: Boolean = true, limit: Int = 2000): List<Voucher> {
        val w = StringBuilder("type IN (${types.joinToString(",") { "?" }})"); val a = ArrayList<Any?>(types.map { it.name })
        if (from != null) { w.append(" AND date >= ?"); a.add(from.toString()) }
        if (to != null) { w.append(" AND date <= ?"); a.add(to.toString()) }
        if (partyId != null) { w.append(" AND party_id = ?"); a.add(partyId) }
        if (!includeInactive) w.append(" AND status = 'POSTED'")
        if (text.isNotBlank()) {
            w.append(" AND (number LIKE ? OR party_name LIKE ? OR ref_no LIKE ? OR description LIKE ? OR party_pan LIKE ?)")
            repeat(5) { a.add("%${text.trim()}%") }
        }
        a.add(limit)
        return db.query("SELECT * FROM voucher WHERE $w ORDER BY date DESC, seq DESC LIMIT ?", a.toTypedArray(), ::voucher)
    }

    /** Remaining quantity (base units) that can still be returned for each line of [originalId]. */
    fun returnableQty(originalId: Long): Map<Long, Long> {
        val orig = lines(originalId)
        val returned = db.list("""SELECT l.original_line_id, SUM(l.base_qty) AS q FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id
            WHERE v.original_voucher_id = ? AND v.status = 'POSTED' GROUP BY l.original_line_id""", originalId) { it.long("original_line_id") to it.long("q") }.toMap()
        return orig.associate { it.id to (it.baseQty - (returned[it.id] ?: 0L)) }
    }

    private fun activeReturns(id: Long) = db.long("SELECT COUNT(*) FROM voucher WHERE original_voucher_id = ? AND status = 'POSTED'", id)

    // ---------------- Posting ----------------

    fun post(input: DocInput): PostResult = db.transaction { postInternal(input, null, null) }

    private fun postInternal(input: DocInput, replaces: Voucher?, reason: String?): PostResult {
        val t = input.type
        if (input.postToken != null) {
            val dup = db.first("SELECT * FROM voucher WHERE post_token = ?", input.postToken, mapper = ::voucher)
            if (dup != null) return PostResult(dup.id, dup.number, listOf("This entry was already saved as ${dup.number}. It was not saved twice."))
        }
        b.years.checkOpen(input.date, "This ${t.label.lowercase()}")
        val warnings = ArrayList<String>()
        val m = b.masters

        // ---- party ----
        var partyId: Long? = null; var partyName = input.partyName.trim(); var pan = input.partyPan.trim(); var addr = input.partyAddress.trim()
        val original = input.originalVoucherId?.let { voucher(it) }
        when (t) {
            VoucherType.SALE, VoucherType.SALES_RETURN, VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN -> {
                if (original != null) { if (partyName.isEmpty()) partyName = original.partyName; if (pan.isEmpty()) pan = original.partyPan; if (addr.isEmpty()) addr = original.partyAddress }
                ensure(partyName.isNotEmpty()) { if (t == VoucherType.SALE || t == VoucherType.SALES_RETURN) "Enter the customer name" else "Enter the supplier name" }
                pan = m.validatePan(pan)
                val isCustomer = t == VoucherType.SALE || t == VoucherType.SALES_RETURN
                partyId = m.findOrCreateParty(partyName, isCustomer, !isCustomer, pan, addr)
                partyName = m.party(partyId).name
            }
            VoucherType.EXPENSE -> if (partyName.isNotEmpty()) {
                pan = m.validatePan(pan)
                partyId = m.findOrCreateParty(partyName, false, true, pan, addr)
                partyName = m.party(partyId).name
            }
            VoucherType.STOCK_ADJUSTMENT -> { partyName = ""; pan = ""; addr = "" }
        }

        // ---- returns must point to a posted original of the right kind ----
        if (t == VoucherType.SALES_RETURN || t == VoucherType.PURCHASE_RETURN) {
            ensure(original != null) { "Choose the original invoice that is being returned" }
            val needType = if (t == VoucherType.SALES_RETURN) VoucherType.SALE else VoucherType.PURCHASE
            ensure(original!!.type == needType && original.status == VoucherStatus.POSTED) { "The original invoice must be a posted ${needType.label.lowercase()}" }
            ensure(original.partyId == partyId) { "The return must be for the same party as the original invoice" }
            ensure(!input.date.isBefore(original.date)) { "A return cannot be dated before the original invoice" }
        } else ensure(original == null) { "Only returns can refer to an original invoice" }

        // ---- settlement account ----
        val settle = input.settleAccountId?.let { m.account(it) }
        if (settle != null) ensure(settle.type == AccountType.ASSET || settle.type == AccountType.LIABILITY) { "Choose a cash, bank, receivable or payable account" }
        ensure(settle?.systemKey != "INVENTORY" && settle?.systemKey?.startsWith("VAT") != true) { "That account cannot be used here" }

        // ---- lines ----
        val companyCharges = b.settings.get("vat_registration_status") != "PAN_ONLY"
        data class L(val product: Product?, val unitId: Long?, val qty: Long, val factor: Long, val baseQty: Long, val rate: Long, val gross: Long,
                     val discount: Long, val taxable: Long, val cat: VatCategory?, val vat: Long, val origLineId: Long?, val desc: String)
        val built = ArrayList<L>()
        var vatClass = input.vatClass
        var hdrTaxable = 0L; var hdrVat = 0L; var hdrGross = 0L; var hdrDiscount = 0L
        if (t == VoucherType.EXPENSE) {
            ensure(input.lines.isEmpty()) { "Expenses do not have item lines" }
            ensure(input.expenseCategoryId != null) { "Choose an expense category" }
            m.expenseCategory(input.expenseCategoryId!!)
            ensure(input.description.isNotBlank()) { "Enter a description of the expense" }
            ensure(input.expenseAmount > 0) { "Enter the amount before VAT" }
            ensure(input.expenseVat >= 0) { "VAT cannot be negative" }
            if (input.expenseVat > 0) {
                ensure(vatClass != VatClass.NONE) { "Choose whether this VAT is eligible, ineligible or pending verification" }
                val expected = Money.vat(input.expenseAmount, 1300)
                if (Math.abs(expected - input.expenseVat) > 100) warnings.add("VAT ${Money.format(input.expenseVat)} is not 13% of ${Money.format(input.expenseAmount)} (expected about ${Money.format(expected)}). Please check the bill.")
                if (vatClass == VatClass.ELIGIBLE && pan.isEmpty()) warnings.add("Input VAT is marked eligible but the supplier PAN is blank. VAT credit normally needs a valid tax invoice with the supplier's PAN.")
            } else vatClass = VatClass.NONE
            hdrGross = input.expenseAmount; hdrTaxable = input.expenseAmount; hdrVat = input.expenseVat
            // An expense bill must not duplicate a purchase bill already entered.
            if (partyId != null && input.refNo.isNotBlank()) {
                val p = db.string("SELECT number FROM voucher WHERE type = 'PURCHASE' AND status = 'POSTED' AND party_id = ? AND ref_no = ? COLLATE NOCASE", partyId, input.refNo.trim())
                ensure(p == null) { "Bill ${input.refNo.trim()} from $partyName is already recorded as purchase $p. Do not record it again as an expense." }
            }
        } else {
            ensure(input.lines.isNotEmpty()) { "Add at least one item" }
            ensure(input.lines.size <= 500) { "Too many lines" }
            val returnable = if (original != null) returnableQty(original.id).toMutableMap() else null
            val origLines = if (original != null) lines(original.id).associateBy { it.id } else emptyMap()
            for ((i, li) in input.lines.withIndex()) {
                val n = i + 1
                val p = m.product(li.productId)
                if (!p.active && t == VoucherType.SALE) warnings.add("${p.name} is marked inactive")
                val factor = m.factorFor(p, li.unitId)
                val u = m.unit(li.unitId ?: p.unitId)
                ensure(li.qty > 0) { "Line $n (${p.name}): quantity must be greater than zero" }
                ensure(u.decimals > 0 || li.qty % 1000 == 0L) { "Line $n (${p.name}): quantity must be a whole number of ${u.name}" }
                val baseQty = Money.mulDiv(li.qty, factor, 1000)
                ensure(baseQty > 0) { "Line $n (${p.name}): quantity is too small" }
                ensure(li.rate >= 0) { "Line $n (${p.name}): rate cannot be negative" }
                var cat: VatCategory? = null; var rate = li.rate; var origLineId: Long? = null
                if (t == VoucherType.STOCK_ADJUSTMENT) {
                    ensure(li.discount == 0L) { "Stock adjustments have no discount" }
                } else {
                    cat = if (original != null) {
                        val ol = origLines[li.originalLineId] ?: throw UserError("Line $n: choose which line of ${original.number} is being returned")
                        ensure(ol.productId == p.id) { "Line $n: product does not match the original invoice line" }
                        origLineId = ol.id
                        val left = returnable!![ol.id] ?: 0L
                        ensure(baseQty <= left) { "Line $n (${p.name}): only ${Qty.format(left)} ${m.unit(p.unitId).name} can still be returned" }
                        returnable[ol.id] = left - baseQty
                        if (rate == 0L) rate = ol.rate
                        // Returns use the original invoice's VAT rate.
                        VatCategory(ol.vatCategoryId ?: 0, "", ol.vatRateBp, ol.vatKind, true)
                    } else m.vatCategory(li.vatCategoryId ?: p.vatCategoryId)
                    ensure(li.discount >= 0) { "Line $n (${p.name}): discount cannot be negative" }
                }
                val gross = Qty.amount(li.qty, rate)
                ensure(li.discount <= gross) { "Line $n (${p.name}): discount is more than the line amount" }
                val taxable = gross - li.discount
                if (t != VoucherType.STOCK_ADJUSTMENT) ensure(gross > 0) { "Line $n (${p.name}): enter a rate" }
                var vat = if (cat != null && cat.kind == VatKind.TAXABLE) Money.vat(taxable, cat.rateBp) else 0L
                if (!companyCharges && (t == VoucherType.SALE || t == VoucherType.SALES_RETURN)) vat = 0
                built.add(L(p, u.id, li.qty, factor, if (t == VoucherType.STOCK_ADJUSTMENT && !input.adjustmentIn) -baseQty else baseQty,
                    rate, gross, li.discount, taxable, cat, vat, origLineId, li.description.trim()))
            }
            hdrGross = built.sumOf { it.gross }; hdrDiscount = built.sumOf { it.discount }
            hdrTaxable = built.sumOf { it.taxable }; hdrVat = built.sumOf { it.vat }
            when (t) {
                VoucherType.PURCHASE, VoucherType.PURCHASE_RETURN -> {
                    if (t == VoucherType.PURCHASE_RETURN) vatClass = original!!.vatClass
                    if (hdrVat > 0) {
                        if (vatClass == VatClass.NONE) vatClass = VatClass.ELIGIBLE
                        if (vatClass == VatClass.ELIGIBLE && pan.isEmpty()) warnings.add("Input VAT is marked eligible but the supplier PAN is blank. Check the supplier's tax invoice.")
                    } else vatClass = VatClass.NONE
                }
                VoucherType.STOCK_ADJUSTMENT -> {
                    ensure(input.description.isNotBlank()) { "Enter the reason for the stock adjustment" }
                    vatClass = VatClass.NONE
                    if (input.adjustmentIn) for (l in built) if (l.rate == 0L) ensure(b.costing.averageCost(l.product!!.id) > 0) {
                        "${l.product.name} has no average cost yet. Enter the unit cost for the stock being added."
                    }
                }
                else -> vatClass = VatClass.NONE
            }
            if (!companyCharges && t == VoucherType.SALE && built.any { it.cat?.kind == VatKind.TAXABLE })
                warnings.add("The company is set as 'PAN only (not VAT registered)', so no VAT was charged on this invoice.")
        }
        val total = hdrTaxable + hdrVat

        // ---- numbering and duplicate checks ----
        var number: String
        if (t == VoucherType.SALE) {
            number = input.number.trim()
            if (replaces != null && number.isEmpty()) number = replaces.number
            if (number.isEmpty()) number = b.years.nextNumber(t.name, input.date)
            else {
                ensure(number.length <= 40) { "Invoice number is too long" }
                val clash = byNumber(t, number)
                ensure(clash == null) { "Invoice number $number is already used (${clash!!.status.name.lowercase()}). Each invoice number can be used only once." }
            }
        } else number = if (replaces != null) replaces.number else b.years.nextNumber(t.name, input.date)
        val refNo = input.refNo.trim()
        if (t == VoucherType.PURCHASE) {
            ensure(refNo.length <= 40) { "Supplier invoice number is too long" }
            if (refNo.isNotEmpty()) {
                val dup = db.string("SELECT number FROM voucher WHERE type = 'PURCHASE' AND status = 'POSTED' AND party_id = ? AND ref_no = ? COLLATE NOCASE", partyId, refNo)
                ensure(dup == null) { "Supplier invoice $refNo from $partyName is already entered as $dup. It cannot be posted twice." }
                val exp = db.string("SELECT number FROM voucher WHERE type = 'EXPENSE' AND status = 'POSTED' AND party_id = ? AND ref_no = ? COLLATE NOCASE", partyId, refNo)
                ensure(exp == null) { "Bill $refNo from $partyName is already recorded as expense $exp." }
            } else warnings.add("No supplier invoice number entered, so duplicate checking is limited for this bill.")
        }
        if (t == VoucherType.EXPENSE && partyId != null && refNo.isNotEmpty()) {
            val dup = db.string("SELECT number FROM voucher WHERE type = 'EXPENSE' AND status = 'POSTED' AND party_id = ? AND ref_no = ? COLLATE NOCASE", partyId, refNo)
            ensure(dup == null) { "Bill $refNo from $partyName is already recorded as expense $dup." }
        }
        if (t == VoucherType.SALE && pan.isEmpty() && total >= 1000000) warnings.add("Customer PAN is blank. For larger sales, check whether the buyer's PAN must be shown on the tax invoice.")

        // ---- stock position before the change (for shortage warnings) ----
        val fromDate = if (replaces != null && replaces.date.isBefore(input.date)) replaces.date else input.date
        val affected = built.mapNotNull { it.product?.id }.toSet() + (replaces?.let { productsOf(it.id) } ?: emptySet())
        val before = affected.associateWith { minFrom(it, fromDate) }

        // ---- write ----
        val now = b.nowIso()
        val vid = db.insert("""INSERT INTO voucher(type, number, date, seq, status, party_id, party_name, party_pan, party_address, ref_no,
            original_voucher_id, settle_account_id, expense_category_id, vat_class, description, gross, discount, taxable, vat, total, notes, doc_ref,
            post_token, replaces_id, created_at, updated_at) VALUES (?,?,?,?, 'POSTED', ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            t.name, number, input.date.toString(), b.nextSeq(), partyId, partyName, pan, addr, refNo, original?.id, settle?.id,
            if (t == VoucherType.EXPENSE) input.expenseCategoryId else null, vatClass.name, input.description.trim(),
            hdrGross, hdrDiscount, hdrTaxable, hdrVat, total, input.notes.trim(), input.docRef.trim(), input.postToken, replaces?.id, now, now)
        for ((i, l) in built.withIndex()) db.insert("""INSERT INTO voucher_line(voucher_id, line_no, product_id, description, unit_id, qty, factor, base_qty, rate,
            gross, discount, taxable, vat_category_id, vat_kind, vat_rate_bp, vat, total, cost, original_line_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)""",
            vid, i + 1, l.product?.id, l.desc, l.unitId, l.qty, l.factor, l.baseQty, l.rate, l.gross, l.discount, l.taxable,
            l.cat?.id?.takeIf { it > 0 }, (l.cat?.kind ?: VatKind.EXEMPT).name, l.cat?.rateBp ?: 0, l.vat, l.taxable + l.vat, l.origLineId)

        // ---- inventory and accounts ----
        b.costing.recompute(affected)
        b.journals.regenerateForVoucher(vid)
        val shortages = shortages(before, fromDate, built.filter { it.baseQty > 0 && t.stockSign < 0 }.groupBy { it.product!!.id }.mapValues { e -> e.value.sumOf { it.baseQty } })
        if (shortages.isNotEmpty()) {
            val confirmable = t == VoucherType.SALE || replaces != null
            if (!confirmable) throw UserError("Not enough stock: " + shortages.joinToString { "${it.productName} (available ${Qty.format(it.available)} ${it.unit})" })
            if (!input.allowNegativeStock) throw NeedsStockConfirmation(shortages)
            warnings.add("Posted with stock shortage: " + shortages.joinToString { "${it.productName} (available ${Qty.format(it.available)} ${it.unit})" })
            b.audit.log("NEGATIVE_STOCK", "voucher", vid, "${t.label} $number posted with confirmed stock shortage",
                shortages.joinToString("; ") { "${it.productName}: available ${Qty.format(it.available)}, required ${Qty.format(it.required)}" })
        }
        val v = voucher(vid)
        b.audit.log(if (replaces != null) "AMEND" else "POST", "voucher", vid,
            "${t.label} $number posted" + (if (replaces != null) " (replaces earlier version)" else ""),
            "date ${v.date}, party ${v.partyName}, taxable ${Money.format(v.taxable)}, VAT ${Money.format(v.vat)}, total ${Money.format(v.total)}", reason ?: "")
        return PostResult(vid, number, warnings)
    }

    private fun minFrom(pid: Long, date: LocalDate): Long =
        minOf(b.costing.minBalanceFrom(pid, date) ?: Long.MAX_VALUE, b.costing.balanceAt(pid, date.minusDays(1)).first)

    /** Products whose lowest stock balance from [date] onward became negative (or more negative) because of a change. */
    private fun shortages(before: Map<Long, Long>, date: LocalDate, required: Map<Long, Long>): List<StockShortage> {
        val out = ArrayList<StockShortage>()
        for ((pid, oldMin) in before) {
            val nm = minFrom(pid, date)
            if (nm < 0 && nm < oldMin) {
                val p = b.masters.product(pid)
                val req = required[pid] ?: 0L
                out.add(StockShortage(pid, p.name, maxOf(0L, nm + req), req, b.masters.unit(p.unitId).name))
            }
        }
        return out
    }

    private fun productsOf(vid: Long): Set<Long> = db.list("SELECT DISTINCT product_id FROM voucher_line WHERE voucher_id = ? AND product_id IS NOT NULL", vid) { it.long("product_id") }.toSet()

    // ---------------- Amend / cancel ----------------

    /**
     * Replaces a posted document with a corrected version. The original stays on record as
     * "superseded", its accounting entry is reversed, and the new version keeps the same number.
     */
    fun amend(originalId: Long, input: DocInput, reason: String): PostResult = db.transaction {
        val old = voucher(originalId)
        ensure(old.status == VoucherStatus.POSTED) { "Only posted documents can be edited" }
        ensure(old.type == input.type) { "The document type cannot be changed" }
        ensure(reason.isNotBlank()) { "Enter a reason for the change" }
        b.years.checkOpen(old.date, "The original ${old.type.label.lowercase()}")
        ensure(activeReturns(old.id) == 0L) { "This invoice has returns recorded against it. Cancel those returns first, or record another return instead." }
        db.update("UPDATE voucher SET status = 'SUPERSEDED', updated_at = ? WHERE id = ?", b.nowIso(), old.id)
        for (j in b.journals.forVoucher(old.id).filter { it.status == "POSTED" }) b.journals.reverse(j.id, "Reversal of ${old.number} (edited: ${reason.trim()})")
        val res = postInternal(input.copy(number = if (old.type == VoucherType.SALE) old.number else input.number), old, reason.trim())
        db.update("UPDATE voucher SET replaced_by_id = ? WHERE id = ?", res.voucherId, old.id)
        res
    }

    /** Cancels a posted document with a reason. It stays on record; its accounting entry is reversed. */
    fun cancel(id: Long, reason: String, allowNegativeStock: Boolean = false): List<String> = db.transaction {
        val v = voucher(id)
        ensure(v.status == VoucherStatus.POSTED) { "This document is already ${v.status.name.lowercase()}" }
        ensure(reason.isNotBlank()) { "Enter a reason for cancelling" }
        b.years.checkOpen(v.date, "This ${v.type.label.lowercase()}")
        ensure(activeReturns(id) == 0L) { "This invoice has returns recorded against it. Cancel those returns first." }
        val before = productsOf(id).associateWith { minFrom(it, v.date) }
        db.update("UPDATE voucher SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = ?, updated_at = ? WHERE id = ?", reason.trim(), b.nowIso(), b.nowIso(), id)
        for (j in b.journals.forVoucher(id).filter { it.status == "POSTED" }) b.journals.reverse(j.id, "Cancellation of ${v.number}: ${reason.trim()}")
        b.costing.recompute(before.keys)
        val warnings = ArrayList<String>()
        val shortages = shortages(before, v.date, emptyMap())
        if (shortages.isNotEmpty()) {
            if (!allowNegativeStock) throw NeedsStockConfirmation(shortages)
            warnings.add("Cancelling made stock negative for: " + shortages.joinToString { it.productName })
            b.audit.log("NEGATIVE_STOCK", "voucher", id, "Cancellation of ${v.number} left negative stock (confirmed)", shortages.joinToString { it.productName })
        }
        b.audit.log("CANCEL", "voucher", id, "${v.type.label} ${v.number} cancelled", "total ${Money.format(v.total)}", reason.trim())
        warnings
    }
}
