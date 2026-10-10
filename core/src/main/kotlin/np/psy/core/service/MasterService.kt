package np.psy.core.service

import np.psy.core.Books
import np.psy.core.cal.BsCalendar
import np.psy.core.db.*
import np.psy.core.model.*
import np.psy.core.util.Money
import np.psy.core.util.Qty

/** Products, units, VAT categories, parties, accounts, expense categories, customer prices. */
class MasterService(private val b: Books) {
    private val db get() = b.db

    // ---------------- Units ----------------
    private fun unit(r: Row) = UnitM(r.long("id"), r.string("name"), r.int("decimals"), r.bool("active"))
    fun units(activeOnly: Boolean = false): List<UnitM> =
        db.list("SELECT * FROM unit ${if (activeOnly) "WHERE active = 1" else ""} ORDER BY name", mapper = ::unit)
    fun unit(id: Long): UnitM = db.first("SELECT * FROM unit WHERE id = ?", id, mapper = ::unit) ?: throw UserError("Unit not found")
    fun unitByName(name: String): UnitM? = db.first("SELECT * FROM unit WHERE name = ? COLLATE NOCASE", name.trim(), mapper = ::unit)

    fun saveUnit(id: Long?, name: String, decimals: Int, active: Boolean = true): Long = db.transaction {
        val n = name.trim()
        ensure(n.isNotEmpty()) { "Enter a unit name" }
        ensure(n.length <= 20) { "Unit name is too long" }
        ensure(decimals in 0..3) { "Decimals must be 0 to 3" }
        val other = unitByName(n)
        ensure(other == null || other.id == id) { "A unit named '$n' already exists" }
        if (id == null) {
            val nid = db.insert("INSERT INTO unit(name, decimals, active) VALUES (?,?,?)", n, decimals, if (active) 1 else 0)
            b.audit.log("CREATE", "unit", nid, "Unit $n created"); nid
        } else {
            db.update("UPDATE unit SET name = ?, decimals = ?, active = ? WHERE id = ?", n, decimals, if (active) 1 else 0, id)
            b.audit.log("UPDATE", "unit", id, "Unit $n updated"); id
        }
    }

    // ---------------- VAT categories ----------------
    private fun vatCat(r: Row) = VatCategory(r.long("id"), r.string("name"), r.int("rate_bp"), VatKind.valueOf(r.string("kind")), r.bool("active"))
    fun vatCategories(activeOnly: Boolean = false): List<VatCategory> =
        db.list("SELECT * FROM vat_category ${if (activeOnly) "WHERE active = 1" else ""} ORDER BY id", mapper = ::vatCat)
    fun vatCategory(id: Long): VatCategory = db.first("SELECT * FROM vat_category WHERE id = ?", id, mapper = ::vatCat) ?: throw UserError("VAT category not found")

    fun saveVatCategory(id: Long?, name: String, rateBp: Int, kind: VatKind, active: Boolean = true): Long = db.transaction {
        val n = name.trim()
        ensure(n.isNotEmpty()) { "Enter a VAT category name" }
        ensure(rateBp in 0..10000) { "VAT rate must be between 0% and 100%" }
        ensure(kind == VatKind.TAXABLE || rateBp == 0) { "Exempt and zero-rated categories must have a 0% rate" }
        ensure(kind != VatKind.TAXABLE || rateBp > 0) { "A taxable category needs a rate above 0%" }
        val dup = db.longOrNull("SELECT id FROM vat_category WHERE name = ? COLLATE NOCASE", n)
        ensure(dup == null || dup == id) { "A VAT category named '$n' already exists" }
        if (id == null) {
            val nid = db.insert("INSERT INTO vat_category(name, rate_bp, kind, active) VALUES (?,?,?,?)", n, rateBp, kind.name, if (active) 1 else 0)
            b.audit.log("CREATE", "vat_category", nid, "VAT category $n created (${Money.format(rateBp.toLong())}%)"); nid
        } else {
            val old = vatCategory(id)
            db.update("UPDATE vat_category SET name = ?, rate_bp = ?, kind = ?, active = ? WHERE id = ?", n, rateBp, kind.name, if (active) 1 else 0, id)
            b.audit.log("UPDATE", "vat_category", id, "VAT category $n updated",
                "rate ${old.rateBp} -> $rateBp bp, kind ${old.kind} -> $kind. Posted invoices keep their original rate."); id
        }
    }

    // ---------------- Accounts ----------------
    private fun account(r: Row) = Account(r.long("id"), r.string("code"), r.string("name"), AccountType.valueOf(r.string("type")),
        r.string("subtype"), r.stringOrNull("system_key"), r.bool("active"))
    fun accounts(activeOnly: Boolean = false): List<Account> =
        db.list("SELECT * FROM account ${if (activeOnly) "WHERE active = 1" else ""} ORDER BY code", mapper = ::account)
    fun account(id: Long): Account = db.first("SELECT * FROM account WHERE id = ?", id, mapper = ::account) ?: throw UserError("Account not found")
    fun accountByKey(key: String): Account = db.first("SELECT * FROM account WHERE system_key = ?", key, mapper = ::account)
        ?: throw IllegalStateException("System account $key is missing")
    fun accountId(key: String): Long = accountByKey(key).id

    fun saveAccount(id: Long?, code: String, name: String, type: AccountType, subtype: String = "", active: Boolean = true): Long = db.transaction {
        val c = code.trim(); val n = name.trim()
        ensure(c.isNotEmpty() && c.length <= 10) { "Enter an account code (up to 10 characters)" }
        ensure(n.isNotEmpty() && n.length <= 80) { "Enter an account name (up to 80 characters)" }
        val dupC = db.longOrNull("SELECT id FROM account WHERE code = ? COLLATE NOCASE", c)
        ensure(dupC == null || dupC == id) { "Account code $c is already used" }
        val dupN = db.longOrNull("SELECT id FROM account WHERE name = ? COLLATE NOCASE", n)
        ensure(dupN == null || dupN == id) { "An account named '$n' already exists" }
        if (id == null) {
            val nid = db.insert("INSERT INTO account(code, name, type, subtype, active) VALUES (?,?,?,?,?)", c, n, type.name, subtype, if (active) 1 else 0)
            b.audit.log("CREATE", "account", nid, "Account $c $n created ($type)"); nid
        } else {
            val old = account(id)
            if (old.type != type) {
                ensure(old.systemKey == null) { "The type of a system account cannot be changed" }
                ensure(db.long("SELECT COUNT(*) FROM journal_line WHERE account_id = ?", id) == 0L) { "This account already has entries, so its type cannot be changed" }
            }
            if (!active && old.active) {
                ensure(old.systemKey == null) { "System accounts cannot be deactivated" }
                ensure(b.reports.accountBalance(id, null) == 0L) { "Only accounts with a zero balance can be deactivated" }
            }
            db.update("UPDATE account SET code = ?, name = ?, type = ?, subtype = ?, active = ? WHERE id = ?", c, n, type.name, subtype, if (active) 1 else 0, id)
            b.audit.log("UPDATE", "account", id, "Account $c $n updated", "was ${old.code} ${old.name} (${old.type})"); id
        }
    }

    // ---------------- Expense categories ----------------
    private fun expCat(r: Row) = ExpenseCategory(r.long("id"), r.string("name"), r.long("account_id"), r.bool("active"))
    fun expenseCategories(activeOnly: Boolean = false): List<ExpenseCategory> =
        db.list("SELECT * FROM expense_category ${if (activeOnly) "WHERE active = 1" else ""} ORDER BY name", mapper = ::expCat)
    fun expenseCategory(id: Long) = db.first("SELECT * FROM expense_category WHERE id = ?", id, mapper = ::expCat) ?: throw UserError("Expense category not found")

    /** Creates/updates an expense category. If accountId is null a new expense account with the same name is created. */
    fun saveExpenseCategory(id: Long?, name: String, accountId: Long?, active: Boolean = true): Long = db.transaction {
        val n = name.trim()
        ensure(n.isNotEmpty()) { "Enter a category name" }
        val dup = db.longOrNull("SELECT id FROM expense_category WHERE name = ? COLLATE NOCASE", n)
        ensure(dup == null || dup == id) { "Expense category '$n' already exists" }
        val acc = accountId ?: run {
            val existing = db.longOrNull("SELECT id FROM account WHERE name = ? COLLATE NOCASE", n)
            if (existing != null) existing else {
                var code = 6100
                while (db.longOrNull("SELECT id FROM account WHERE code = ?", code.toString()) != null) code++
                saveAccount(null, code.toString(), n, AccountType.EXPENSE, "OPERATING")
            }
        }
        val a = account(acc)
        ensure(a.type == AccountType.EXPENSE || a.type == AccountType.COGS) { "An expense category must point to an expense account" }
        if (id == null) {
            val nid = db.insert("INSERT INTO expense_category(name, account_id, active) VALUES (?,?,?)", n, acc, if (active) 1 else 0)
            b.audit.log("CREATE", "expense_category", nid, "Expense category $n created"); nid
        } else {
            db.update("UPDATE expense_category SET name = ?, account_id = ?, active = ? WHERE id = ?", n, acc, if (active) 1 else 0, id)
            b.audit.log("UPDATE", "expense_category", id, "Expense category $n updated"); id
        }
    }

    // ---------------- Parties (customers / suppliers) ----------------
    private fun party(r: Row) = Party(r.long("id"), r.string("name"), r.bool("is_customer"), r.bool("is_supplier"), r.string("pan"),
        r.string("address"), r.string("phone"), r.string("email"), r.bool("active"))
    fun parties(customers: Boolean? = null, suppliers: Boolean? = null, search: String = ""): List<Party> {
        val w = StringBuilder("1=1"); val a = ArrayList<Any?>()
        if (customers == true) w.append(" AND is_customer = 1")
        if (suppliers == true) w.append(" AND is_supplier = 1")
        if (search.isNotBlank()) { w.append(" AND (name LIKE ? OR pan LIKE ? OR phone LIKE ?)"); repeat(3) { a.add("%${search.trim()}%") } }
        return db.query("SELECT * FROM party WHERE $w ORDER BY name", a.toTypedArray(), ::party)
    }
    fun party(id: Long): Party = db.first("SELECT * FROM party WHERE id = ?", id, mapper = ::party) ?: throw UserError("Party not found")
    fun partyByName(name: String): Party? = db.first("SELECT * FROM party WHERE name = ? COLLATE NOCASE", name.trim(), mapper = ::party)

    fun validatePan(pan: String): String {
        val p = pan.trim()
        if (p.isEmpty()) return p
        ensure(p.all { it.isDigit() } && p.length == 9) { "PAN/VAT number must be 9 digits" }
        return p
    }

    fun saveParty(id: Long?, name: String, isCustomer: Boolean, isSupplier: Boolean, pan: String = "", address: String = "",
                  phone: String = "", email: String = "", active: Boolean = true): Long = db.transaction {
        val n = name.trim().replace(Regex("\\s+"), " ")
        ensure(n.isNotEmpty()) { "Enter a name" }
        ensure(n.length <= 100) { "Name is too long" }
        val p = validatePan(pan)
        val e = email.trim()
        ensure(e.isEmpty() || (e.contains("@") && e.contains("."))) { "Email address looks incorrect" }
        val dup = partyByName(n)
        ensure(dup == null || dup.id == id) { "'$n' already exists. Choose it from the list instead." }
        if (id == null) {
            val nid = db.insert("INSERT INTO party(name, is_customer, is_supplier, pan, address, phone, email, active, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                n, if (isCustomer) 1 else 0, if (isSupplier) 1 else 0, p, address.trim(), phone.trim(), e, if (active) 1 else 0, b.nowIso())
            b.audit.log("CREATE", "party", nid, "Party $n created"); nid
        } else {
            db.update("UPDATE party SET name=?, is_customer=?, is_supplier=?, pan=?, address=?, phone=?, email=?, active=? WHERE id=?",
                n, if (isCustomer) 1 else 0, if (isSupplier) 1 else 0, p, address.trim(), phone.trim(), e, if (active) 1 else 0, id)
            b.audit.log("UPDATE", "party", id, "Party $n updated"); id
        }
    }

    /** Finds a party by name or creates it (used when typing a new customer/supplier on an invoice). */
    fun findOrCreateParty(name: String, customer: Boolean, supplier: Boolean, pan: String, address: String): Long {
        val existing = partyByName(name)
        if (existing == null) return saveParty(null, name, customer, supplier, pan, address)
        val needFlag = (customer && !existing.isCustomer) || (supplier && !existing.isSupplier)
        val newPan = if (existing.pan.isEmpty() && pan.isNotBlank()) validatePan(pan) else existing.pan
        val newAddr = if (existing.address.isEmpty() && address.isNotBlank()) address.trim() else existing.address
        if (needFlag || newPan != existing.pan || newAddr != existing.address)
            saveParty(existing.id, existing.name, existing.isCustomer || customer, existing.isSupplier || supplier, newPan, newAddr,
                existing.phone, existing.email, existing.active)
        return existing.id
    }

    // ---------------- Products ----------------
    private fun product(r: Row) = Product(r.long("id"), r.string("code"), r.string("name"), r.string("category"), r.long("unit_id"),
        r.long("vat_category_id"), r.long("sale_price"), r.long("opening_qty"), r.long("opening_value"), r.long("min_stock"), r.bool("active"))
    fun products(activeOnly: Boolean = false, search: String = ""): List<Product> {
        val w = StringBuilder(if (activeOnly) "active = 1" else "1=1"); val a = ArrayList<Any?>()
        if (search.isNotBlank()) { w.append(" AND (name LIKE ? OR code LIKE ? OR category LIKE ?)"); repeat(3) { a.add("%${search.trim()}%") } }
        return db.query("SELECT * FROM product WHERE $w ORDER BY name", a.toTypedArray(), ::product)
    }
    fun product(id: Long): Product = db.first("SELECT * FROM product WHERE id = ?", id, mapper = ::product) ?: throw UserError("Product not found")
    fun productByName(name: String): Product? = db.first("SELECT * FROM product WHERE name = ? COLLATE NOCASE", name.trim(), mapper = ::product)
    fun productByCode(code: String): Product? = db.first("SELECT * FROM product WHERE code = ? COLLATE NOCASE", code.trim(), mapper = ::product)
    fun productCategories(): List<String> = db.list("SELECT DISTINCT category FROM product WHERE category <> '' ORDER BY category") { it.string("category") }

    fun productHasTransactions(id: Long) = db.long("SELECT COUNT(*) FROM voucher_line WHERE product_id = ?", id) > 0

    /** Suggests the next free product code like P0001. */
    fun nextProductCode(): String {
        var n = db.long("SELECT COUNT(*) FROM product") + 1
        while (productByCode("P%04d".format(n)) != null) n++
        return "P%04d".format(n)
    }

    fun saveProduct(id: Long?, code: String, name: String, category: String, unitId: Long, vatCategoryId: Long, salePrice: Long,
                    openingQty: Long, openingValue: Long, minStock: Long, active: Boolean = true): Long = db.transaction {
        val c = code.trim(); val n = name.trim().replace(Regex("\\s+"), " ")
        ensure(c.isNotEmpty() && c.length <= 30) { "Enter a product code / SKU (up to 30 characters)" }
        ensure(n.isNotEmpty() && n.length <= 120) { "Enter a product name" }
        val dupC = productByCode(c); ensure(dupC == null || dupC.id == id) { "Product code $c is already used by ${dupC!!.name}" }
        val dupN = productByName(n); ensure(dupN == null || dupN.id == id) { "A product named '$n' already exists" }
        val u = unit(unitId)
        vatCategory(vatCategoryId)
        ensure(salePrice >= 0 && minStock >= 0) { "Price and minimum stock cannot be negative" }
        ensure(openingQty >= 0 && openingValue >= 0) { "Opening quantity and value cannot be negative" }
        ensure(openingQty > 0 || openingValue == 0L) { "Opening value needs an opening quantity" }
        Qty.format(openingQty).let { s -> ensure(u.decimals > 0 || openingQty % 1000 == 0L) { "Opening quantity must be a whole number of ${u.name}" } }
        if (id == null) {
            if (openingQty > 0) checkOpeningEditable()
            val nid = db.insert("""INSERT INTO product(code, name, category, unit_id, vat_category_id, sale_price, opening_qty, opening_value, min_stock, active, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""", c, n, category.trim(), unitId, vatCategoryId, salePrice, openingQty, openingValue, minStock, if (active) 1 else 0, b.nowIso())
            b.audit.log("CREATE", "product", nid, "Product $c $n created",
                "opening qty ${Qty.format(openingQty)} ${u.name}, value ${Money.format(openingValue)}")
            if (openingQty > 0) { b.costing.recompute(listOf(nid)); b.journals.regenerateOpeningStock() }
            nid
        } else {
            val old = product(id)
            if (old.unitId != unitId) ensure(!productHasTransactions(id) && old.openingQty == 0L) {
                "This product already has stock or transactions, so its base unit cannot be changed. Create a new product or add a unit conversion instead."
            }
            val openingChanged = old.openingQty != openingQty || old.openingValue != openingValue
            if (openingChanged) checkOpeningEditable()
            db.update("""UPDATE product SET code=?, name=?, category=?, unit_id=?, vat_category_id=?, sale_price=?, opening_qty=?, opening_value=?,
                min_stock=?, active=? WHERE id=?""", c, n, category.trim(), unitId, vatCategoryId, salePrice, openingQty, openingValue, minStock, if (active) 1 else 0, id)
            val changes = ArrayList<String>()
            if (old.name != n) changes.add("name '${old.name}' -> '$n'")
            if (old.salePrice != salePrice) changes.add("default price ${Money.format(old.salePrice)} -> ${Money.format(salePrice)}")
            if (openingChanged) changes.add("opening ${Qty.format(old.openingQty)}/${Money.format(old.openingValue)} -> ${Qty.format(openingQty)}/${Money.format(openingValue)}")
            if (old.vatCategoryId != vatCategoryId) changes.add("VAT category changed (posted invoices keep their rate)")
            b.audit.log("UPDATE", "product", id, "Product $c $n updated", changes.joinToString("; "))
            if (openingChanged) { b.costing.recompute(listOf(id)); b.journals.regenerateOpeningStock() }
            id
        }
    }

    private fun checkOpeningEditable() {
        val start = b.years.booksStart()
        val fy = b.years.forDate(start)
        ensure(fy.status == FyStatus.OPEN) { "Opening stock cannot be changed because the first financial year (${fy.name}) is closed. Reopen it first, or use a stock adjustment." }
    }

    // ---------------- Unit conversions ----------------
    fun productUnits(productId: Long): List<ProductUnit> = db.list("SELECT * FROM product_unit WHERE product_id = ? ORDER BY id", productId) {
        ProductUnit(it.long("id"), it.long("product_id"), it.long("unit_id"), it.long("factor"))
    }

    /** Adds an alternate unit: 1 [unitId] = [factor] (thousandths) of the product's base unit. */
    fun saveProductUnit(productId: Long, unitId: Long, factor: Long) = db.transaction {
        val p = product(productId)
        ensure(unitId != p.unitId) { "This is already the base unit" }
        ensure(factor > 0) { "Conversion factor must be greater than zero" }
        val existing = productUnits(productId).firstOrNull { it.unitId == unitId }
        if (existing != null) {
            ensure(db.long("SELECT COUNT(*) FROM voucher_line WHERE product_id = ? AND unit_id = ?", productId, unitId) == 0L || existing.factor == factor) {
                "This conversion is already used on invoices and cannot be changed"
            }
            db.update("UPDATE product_unit SET factor = ? WHERE id = ?", factor, existing.id)
        } else db.insert("INSERT INTO product_unit(product_id, unit_id, factor) VALUES (?,?,?)", productId, unitId, factor)
        b.audit.log("UPDATE", "product", productId, "Unit conversion for ${p.name}: 1 ${unit(unitId).name} = ${Qty.format(factor)} ${unit(p.unitId).name}")
    }

    /** Thousandths of base unit per 1 entered unit. */
    fun factorFor(product: Product, unitId: Long?): Long {
        if (unitId == null || unitId == product.unitId) return 1000
        return productUnits(product.id).firstOrNull { it.unitId == unitId }?.factor
            ?: throw UserError("No conversion is set up between ${unit(unitId).name} and ${unit(product.unitId).name} for ${product.name}. Add it in the product screen first.")
    }

    // ---------------- Customer-specific prices ----------------
    fun customerPrice(partyId: Long, productId: Long): Long? = db.longOrNull("SELECT price FROM customer_price WHERE party_id = ? AND product_id = ?", partyId, productId)
    fun customerPrices(partyId: Long): List<Pair<Long, Long>> = db.list("SELECT product_id, price FROM customer_price WHERE party_id = ?", partyId) { it.long("product_id") to it.long("price") }

    /** Price to suggest on a sales line: customer's own price, otherwise the product's default. */
    fun suggestedPrice(partyId: Long?, productId: Long): Long = (partyId?.let { customerPrice(it, productId) }) ?: product(productId).salePrice

    fun setCustomerPrice(partyId: Long, productId: Long, price: Long?) = db.transaction {
        val p = party(partyId); val pr = product(productId)
        if (price == null) {
            db.update("DELETE FROM customer_price WHERE party_id = ? AND product_id = ?", partyId, productId)
            b.audit.log("UPDATE", "customer_price", partyId, "Special price removed: ${p.name} / ${pr.name}")
        } else {
            ensure(price >= 0) { "Price cannot be negative" }
            db.execute("INSERT OR REPLACE INTO customer_price(party_id, product_id, price, updated_at) VALUES (?,?,?,?)", partyId, productId, price, b.nowIso())
            b.audit.log("UPDATE", "customer_price", partyId, "Special price for ${p.name} / ${pr.name}: ${Money.format(price)}")
        }
    }

    // ---------------- Calendar corrections ----------------
    fun saveCalendarCorrection(bsYear: Int, monthLengths: IntArray, note: String) {
        try { saveCalendarCorrectionTx(bsYear, monthLengths, note) } catch (e: Exception) { b.loadCalendarCorrections(); throw e }
    }

    private fun saveCalendarCorrectionTx(bsYear: Int, monthLengths: IntArray, note: String) = db.transaction {
        try { BsCalendar.validateMonthLengths(bsYear, monthLengths) } catch (e: IllegalArgumentException) { throw UserError(e.message ?: "Invalid") }
        val def = BsCalendar.defaultMonthLengths(bsYear)
        if (def.contentEquals(monthLengths)) db.update("DELETE FROM calendar_correction WHERE bs_year = ?", bsYear)
        else db.execute("INSERT OR REPLACE INTO calendar_correction(bs_year, month_lengths, note, updated_at) VALUES (?,?,?,?)",
            bsYear, monthLengths.joinToString(","), note, b.nowIso())
        b.audit.log("UPDATE", "calendar", bsYear.toLong(), "BS $bsYear month lengths set to ${monthLengths.joinToString(",")}", "", note)
        b.loadCalendarCorrections()
        b.years.refreshBoundaries()
    }
}
