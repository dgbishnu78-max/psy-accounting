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
import np.psy.core.util.Qty

// ======================= Products =======================

class ProductListScreen(act: MainActivity) : Screen(act) {
    private var search = ""
    private var showInactive = false
    override val title = "Products and stock"
    override val actions get() = listOf<Pair<String, () -> Unit>>(
        "New product" to { push(ProductEditScreen(act, null)) },
        "Stock adjustments" to { push(DocListScreen(act, DocListScreen.ADJUSTMENTS)) },
        "New stock adjustment" to { push(DocEntryScreen(act, VoucherType.STOCK_ADJUSTMENT)) },
        "Units" to { push(UnitsScreen(act)) },
    )

    override fun build(): View {
        val b = books
        val page = ListPage(ctx)
        val bar = ctx.hbox()
        val e = EditText(ctx).apply { hint = "Search name, code or category"; setText(search); setSingleLine(true) }
        bar.addView(e, lp(0, WRAP, 1f))
        bar.addView(Button(ctx).apply { text = "Search"; isAllCaps = false; setOnClickListener { search = e.str; refresh() } }, LinearLayout.LayoutParams(WRAP, WRAP))
        page.header.addView(bar, lp())
        page.header.check("Show inactive products", showInactive).setOnCheckedChangeListener { _, c -> showInactive = c; refresh() }
        val row = ctx.hbox()
        row.button("+ New product") { push(ProductEditScreen(act, null)) }
        row.button("Stock adjustment", false) { push(DocEntryScreen(act, VoucherType.STOCK_ADJUSTMENT)) }
        page.header.addView(row, lp())
        val items = b.masters.products(activeOnly = !showInactive, search = search)
        var total = 0L
        val data = items.map { p -> val (q, v) = b.costing.balanceAt(p.id, null); total += v; Triple(p, q, v) }
        page.header.note("${items.size} product(s). Total stock value ${money(total)}")
        page.show(data, "No products yet. Tap '+ New product'.", { (p, q, v), _ ->
            val u = b.masters.unit(p.unitId).name
            ctx.listRow(p.name, "${qty(q)} $u", "${p.code}${if (p.category.isNotEmpty()) "  |  " + p.category else ""}  |  price ${money(p.salePrice)}  |  value ${money(v)}",
                if (q < 0) C.WARN else C.TEXT, if (q < 0) "Negative stock" else if (q < p.minStock) "Below minimum stock (${qty(p.minStock)})" else if (!p.active) "Inactive" else "",
                q < 0)
        }) { (p, _, _) -> push(ProductEditScreen(act, p.id)) }
        return page.root
    }
}

/** Small dialog to create a product without leaving an invoice. */
object ProductQuickCreate {
    fun show(act: MainActivity, onCreated: (Product) -> Unit) {
        val b = np.psy.accounting.App.books()
        val box = act.vbox(16)
        val code = box.field("Code / SKU", b.masters.nextProductCode())
        val name = box.field("Product name", "", type = CAPS)
        val units = b.masters.units(activeOnly = true)
        val unit = box.choice("Base unit (stock is counted in this unit)", units, units.firstOrNull()) { it.name }
        val cats = b.masters.vatCategories(activeOnly = true)
        val vat = box.choice("VAT", cats, cats.firstOrNull()) { it.name }
        val price = box.field("Default selling price per unit (optional)", "", type = NUM)
        box.note("You can add other units (for example 1 Box = 12 Pcs) later in the product screen.")
        val dlg = AlertDialog.Builder(act).setTitle("New product").setView(act.scroll(box)).setPositiveButton("Create", null).setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely(act) {
                val id = b.masters.saveProduct(null, code.str, name.str, "", unit.selected!!.id, vat.selected!!.id, Money.parse(price.str, "Price"), 0, 0, 0)
                dlg.dismiss(); onCreated(b.masters.product(id))
            }
        }
    }
}

class ProductEditScreen(act: MainActivity, private val id: Long?) : Screen(act) {
    override val title get() = if (id == null) "New product" else "Product"
    override fun build(): View {
        val b = books
        val p = id?.let { b.masters.product(it) }
        val box = ctx.vbox(12)
        if (p != null) {
            val (q, v) = b.costing.balanceAt(p.id, null)
            val u = b.masters.unit(p.unitId).name
            box.card {
                text(p.name, 18f, true, C.PRIMARY_DARK)
                kv("Stock now", "${qty(q)} $u", true, if (q < 0) C.WARN else C.TEXT)
                kv("Stock value", money(v))
                kv("Average cost per $u", money(b.costing.averageCost(p.id)))
                if (q < 0) warning("Negative stock: the cost of recent sales is provisional until the missing purchase or a stock adjustment is entered.")
                button("Stock movement (this financial year)", false) {
                    val fy = b.years.current()
                    act.working("Preparing...", { b.reports.stockMovement(p.id, fy.start, fy.end) }) { push(ReportScreen(act, listOf(it), fy.start to fy.end)) }
                }
            }
        }
        val code = box.field("Code / SKU", p?.code ?: b.masters.nextProductCode())
        val name = box.field("Product name", p?.name ?: "", type = CAPS)
        val category = box.auto("Category (optional)", p?.category ?: "", b.masters.productCategories())
        val units = b.masters.units(activeOnly = true).let { l -> if (p != null && l.none { it.id == p.unitId }) l + b.masters.unit(p.unitId) else l }
        val unit = box.choice("Base unit (stock is counted in this unit)", units, units.firstOrNull { it.id == p?.unitId } ?: units.firstOrNull()) { it.name }
        val cats = b.masters.vatCategories(activeOnly = true)
        val vat = box.choice("VAT category", cats, cats.firstOrNull { it.id == p?.vatCategoryId } ?: cats.firstOrNull()) { "${it.name} (${it.kind.label})" }
        val price = box.field("Default selling price per base unit (excluding VAT)", p?.let { moneyInput(it.salePrice) } ?: "", type = NUM)
        val min = box.field("Minimum stock level for low-stock warning (optional)", p?.let { qtyInput(it.minStock) } ?: "", type = NUM)
        box.heading("Opening stock")
        box.note("Stock you already had on the books start date (${bs(b.years.booksStart())} BS). Leave blank if none. Later stock changes come only from purchases, sales and adjustments.")
        val oq = box.field("Opening quantity (base unit)", p?.let { qtyInput(it.openingQty) } ?: "", type = NUM)
        val ov = box.field("Opening total value (NPR, at cost)", p?.let { moneyInput(it.openingValue) } ?: "", type = NUM)
        val active = box.check("Active (shown when entering invoices)", p?.active ?: true)
        box.button("Save product") {
            val u = unit.selected ?: throw UserError("Choose a unit")
            val nid = b.masters.saveProduct(id, code.str, name.str, category.str, u.id, vat.selected!!.id, Money.parse(price.str, "Price"),
                Qty.parse(oq.str, u.decimals, "Opening quantity"), Money.parse(ov.str, "Opening value"), Qty.parse(min.str, 3, "Minimum stock"), active.isChecked)
            ctx.toast("Product saved")
            if (id == null) act.replace(ProductEditScreen(act, nid)) else refresh()
        }

        if (p != null) {
            box.heading("Other units")
            val base = b.masters.unit(p.unitId).name
            val conv = b.masters.productUnits(p.id)
            if (conv.isEmpty()) box.note("Only $base is used. Add a unit if you buy or sell this product in, for example, boxes or jars.")
            for (c in conv) box.kv("1 ${b.masters.unit(c.unitId).name}", "= ${qty(c.factor)} $base")
            box.button("+ Add or change a unit conversion", false) {
                val others = b.masters.units(activeOnly = true).filter { it.id != p.unitId }
                if (others.isEmpty()) throw UserError("Create another unit first (Products > menu > Units)")
                val d = ctx.vbox(16)
                val uc = d.choice("Unit", others, others.firstOrNull()) { it.name }
                val f = d.field("How many $base in 1 of that unit?", "", type = NUM)
                AlertDialog.Builder(ctx).setTitle("Unit conversion").setView(d).setPositiveButton("Save") { _, _ ->
                    safely(ctx) { b.masters.saveProductUnit(p.id, uc.selected!!.id, Qty.parse(f.str, 3, "Conversion")); refresh() }
                }.setNegativeButton("Cancel", null).show()
            }
            box.heading("History")
            for (a in b.audit.recent(20, "product", p.id)) box.note("${a.at.replace('T', ' ')}  ${a.summary}  ${a.details}")
        }
        return ctx.scroll(box)
    }
}

class UnitsScreen(act: MainActivity) : Screen(act) {
    override val title = "Units"
    override fun build(): View {
        val b = books
        val box = ctx.vbox(12)
        box.note("Decimals: 0 means only whole numbers (Pcs, Box). Use 3 for weight or volume (Kg, Ltr).")
        for (u in b.masters.units()) box.card {
            kv(u.name + if (!u.active) " (inactive)" else "", "${u.decimals} decimal(s)")
            setOnClickListener { edit(u) }
        }
        box.button("+ New unit") { edit(null) }
        return ctx.scroll(box)
    }

    private fun edit(u: UnitM?) {
        val d = ctx.vbox(16)
        val n = d.field("Unit name", u?.name ?: "")
        val dec = d.choice("Decimals allowed", listOf(0, 1, 2, 3), u?.decimals ?: 0) { if (it == 0) "0 (whole numbers only)" else "$it" }
        val a = d.check("Active", u?.active ?: true)
        AlertDialog.Builder(ctx).setTitle(if (u == null) "New unit" else "Edit unit").setView(d).setPositiveButton("Save") { _, _ ->
            safely(ctx) { books.masters.saveUnit(u?.id, n.str, dec.selected ?: 0, a.isChecked); refresh() }
        }.setNegativeButton("Cancel", null).show()
    }
}

// ======================= Customers and suppliers =======================

class PartyListScreen(act: MainActivity) : Screen(act) {
    private var filter = 0
    private var search = ""
    override val title = "Customers and suppliers"
    override fun build(): View {
        val b = books
        val page = ListPage(ctx)
        val bar = ctx.hbox()
        val e = EditText(ctx).apply { hint = "Search name, PAN, phone"; setText(search); setSingleLine(true) }
        bar.addView(e, lp(0, WRAP, 1f))
        bar.addView(Button(ctx).apply { text = "Search"; isAllCaps = false; setOnClickListener { search = e.str; refresh() } }, LinearLayout.LayoutParams(WRAP, WRAP))
        page.header.addView(bar, lp())
        val f = page.header.choice("", listOf(0, 1, 2), filter) { listOf("All", "Customers", "Suppliers")[it] }
        f.onChange { v -> if (v != null && v != filter) { filter = v; refresh() } }
        page.header.button("+ New customer or supplier") { push(PartyEditScreen(act, null)) }
        val items = b.masters.parties(customers = if (filter == 1) true else null, suppliers = if (filter == 2) true else null, search = search)
        page.show(items, "No customers or suppliers yet. They are also added automatically when you type a new name on an invoice.", { p, _ ->
            val kind = listOfNotNull(if (p.isCustomer) "Customer" else null, if (p.isSupplier) "Supplier" else null).joinToString(" & ")
            ctx.listRow(p.name, kind, listOf(p.pan.takeIf { it.isNotEmpty() }?.let { "PAN $it" }, p.phone.takeIf { it.isNotEmpty() }, p.address.takeIf { it.isNotEmpty() }).filterNotNull().joinToString("  |  "),
                C.MUTED, if (!p.active) "Inactive" else "")
        }) { push(PartyEditScreen(act, it.id)) }
        return page.root
    }
}

class PartyEditScreen(act: MainActivity, private val id: Long?) : Screen(act) {
    override val title get() = if (id == null) "New customer / supplier" else "Customer / supplier"
    override fun build(): View {
        val b = books
        val p = id?.let { b.masters.party(it) }
        val box = ctx.vbox(12)
        val name = box.field("Name", p?.name ?: "", type = CAPS)
        val cust = box.check("Customer", p?.isCustomer ?: true)
        val supp = box.check("Supplier", p?.isSupplier ?: false)
        val pan = box.field("PAN / VAT number (9 digits)", p?.pan ?: "", type = INT)
        val addr = box.field("Address", p?.address ?: "")
        val phone = box.field("Phone", p?.phone ?: "", type = PHONE)
        val email = box.field("Email", p?.email ?: "", type = EMAIL)
        val active = box.check("Active", p?.active ?: true)
        box.button("Save") {
            if (!cust.isChecked && !supp.isChecked) throw UserError("Tick Customer, Supplier or both")
            val nid = b.masters.saveParty(id, name.str, cust.isChecked, supp.isChecked, pan.str, addr.str, phone.str, email.str, active.isChecked)
            ctx.toast("Saved")
            if (id == null) act.replace(PartyEditScreen(act, nid)) else refresh()
        }
        if (p != null) {
            if (p.isCustomer) {
                box.button("Sales to this customer", false) { push(DocListScreen(act, DocListScreen.SALES, p.id)) }
                box.heading("Special prices for this customer")
                box.note("When you sell to ${p.name}, these prices are suggested instead of the product's default price. You can still change the price on each invoice.")
                for ((pid, price) in b.masters.customerPrices(p.id)) {
                    val pr = b.masters.product(pid)
                    box.card {
                        kv(pr.name, "${money(price)} / ${b.masters.unit(pr.unitId).name}")
                        note("Default price ${money(pr.salePrice)}. Tap to change or remove.")
                        setOnClickListener { priceDialog(p, pr, price) }
                    }
                }
                box.button("+ Add special price", false) {
                    val prods = b.masters.products(activeOnly = true)
                    if (prods.isEmpty()) throw UserError("Create products first")
                    ctx.pick("Choose product", prods, { "${it.name} [${it.code}]" }) { pr -> priceDialog(p, pr, b.masters.customerPrice(p.id, pr.id)) }
                }
            }
            if (p.isSupplier) {
                box.button("Purchases from this supplier", false) { push(DocListScreen(act, DocListScreen.PURCHASES, p.id)) }
                box.button("Expenses from this supplier", false) { push(DocListScreen(act, DocListScreen.EXPENSES, p.id)) }
            }
        }
        return ctx.scroll(box)
    }

    private fun priceDialog(p: Party, pr: Product, current: Long?) {
        val d = ctx.vbox(16)
        d.note("${pr.name}: default price ${money(pr.salePrice)} per ${books.masters.unit(pr.unitId).name}")
        val f = d.field("Special price for ${p.name}", current?.let { moneyInput(it, false) } ?: "", type = NUM)
        val dlg = AlertDialog.Builder(ctx).setTitle("Special price").setView(d)
            .setPositiveButton("Save") { _, _ -> safely(ctx) { books.masters.setCustomerPrice(p.id, pr.id, Money.parse(f.str, "Price")); refresh() } }
            .setNegativeButton("Cancel", null)
        if (current != null) dlg.setNeutralButton("Remove") { _, _ -> safely(ctx) { books.masters.setCustomerPrice(p.id, pr.id, null); refresh() } }
        dlg.show()
    }
}
