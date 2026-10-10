package np.psy.core.service

import np.psy.core.Books
import np.psy.core.db.*
import np.psy.core.model.FyStatus
import np.psy.core.model.StockMove
import np.psy.core.model.VatClass
import np.psy.core.model.VoucherType
import np.psy.core.util.Money
import java.time.LocalDate

/**
 * Chronological moving weighted-average costing.
 *
 * Ordering: by date; within a date: opening stock, then purchases and stock-in adjustments,
 * then sales, purchase returns and stock-out adjustments, then sales returns; then by entry
 * sequence. This order is fixed so results are always reproducible.
 *
 * - Purchases add quantity and value (taxable amount after discount; recoverable VAT excluded,
 *   ineligible VAT included).
 * - Sales and other issues remove stock at the current average cost (value x qty / balance qty).
 * - Sales returns come back at the cost at which the original invoice line left stock.
 * - Purchase returns leave at the current average cost; any difference from the supplier credit
 *   goes to "Inventory Adjustments" in cost of goods sold.
 * - Negative stock (only after explicit confirmation): the oversold units are costed provisionally
 *   at the last known average cost. When later stock-in covers the shortage, those units are
 *   re-costed at the actual cost of the covering stock and the original sale's cost of goods sold
 *   is corrected (first-oversold, first-covered). Units still uncovered stay provisional and are
 *   flagged in reports. A year cannot be closed while any product has negative stock at year-end.
 *
 * Every change to a product's movements re-runs the product's full history, so edits and
 * cancellations of past entries always flow through to later costs.
 */
class CostingService(private val b: Books) {
    private val db get() = b.db

    private class Ev(
        val lineId: Long?, val voucherId: Long?, val type: String, val date: String, val phase: Int, val seq: Long, val lineNo: Int,
        val qty: Long, val inflow: Boolean, val fixedValue: Long?, val origLineId: Long?, val origBaseQty: Long,
    )

    private class Over(val lineId: Long, var qtyRem: Long, var provRem: Long)

    data class Result(val lineCosts: Map<Long, Long>, val provisional: Set<Long>, val moves: List<StockMove>)

    /** Recalculates the given products and updates line costs, stock movements and affected journal entries. */
    fun recompute(productIds: Collection<Long>): Set<Long> = db.transaction {
        val changedVouchers = HashSet<Long>()
        for (pid in productIds.toSet()) changedVouchers.addAll(recomputeProduct(pid))
        val closed = b.years.all().filter { it.status != FyStatus.OPEN }
        for (vid in changedVouchers) {
            val d = LocalDate.parse(db.string("SELECT date FROM voucher WHERE id = ?", vid)!!)
            if (closed.any { it.contains(d) }) throw IllegalStateException("Internal check failed: recalculation would change a closed year. No changes were saved.")
            b.journals.regenerateForVoucher(vid)
        }
        changedVouchers
    }

    fun recomputeAll() = db.transaction {
        recompute(db.list("SELECT id FROM product") { it.long("id") })
    }

    private fun phase(type: String, inflow: Boolean): Int = when (type) {
        "PURCHASE" -> 1
        "STOCK_ADJUSTMENT" -> if (inflow) 1 else 2
        "SALE", "PURCHASE_RETURN" -> 2
        "SALES_RETURN" -> 3
        else -> 2
    }

    private fun loadEvents(pid: Long): List<Ev> {
        val evs = db.list("""
            SELECT l.id, l.voucher_id, l.line_no, l.base_qty, l.taxable, l.vat, l.original_line_id, v.type, v.date, v.seq, v.vat_class,
                   ol.base_qty AS orig_qty
            FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id
            LEFT JOIN voucher_line ol ON ol.id = l.original_line_id
            WHERE l.product_id = ? AND v.status = 'POSTED' AND v.type IN ('SALE','PURCHASE','SALES_RETURN','PURCHASE_RETURN','STOCK_ADJUSTMENT')
        """.trimIndent(), pid) { r ->
            val type = r.string("type")
            val bq = r.long("base_qty")
            val inflow = when (type) { "PURCHASE", "SALES_RETURN" -> true; "STOCK_ADJUSTMENT" -> bq > 0; else -> false }
            val fixed: Long? = when (type) {
                "PURCHASE" -> r.long("taxable") + if (VatClass.valueOf(r.string("vat_class")) == VatClass.INELIGIBLE) r.long("vat") else 0
                "STOCK_ADJUSTMENT" -> if (inflow && r.long("taxable") > 0) r.long("taxable") else null
                else -> null
            }
            Ev(r.long("id"), r.long("voucher_id"), type, r.string("date"), phase(type, inflow), r.long("seq"), r.int("line_no"),
                Math.abs(bq), inflow, fixed, r.longOrNull("original_line_id"), r.longOrNull("orig_qty") ?: 0)
        }
        return evs.sortedWith(compareBy<Ev>({ it.date }, { it.phase }, { it.seq }, { it.lineNo }))
    }

    /** Pure costing calculation (no database writes). */
    fun calculate(pid: Long): Result {
        val p = b.masters.product(pid)
        val events = loadEvents(pid)
        val start = b.years.booksStart().toString()
        var origCosts: Map<Long, Long> = emptyMap()
        var result: Result? = null
        for (iteration in 0 until 6) {
            val r = pass(p.openingQty, p.openingValue, start, events, origCosts)
            // Sales returns depend on the final cost of the original sale line; repeat until stable.
            val newOrig = events.filter { it.origLineId != null }.associate { it.origLineId!! to (r.lineCosts[it.origLineId] ?: 0L) }
            result = r
            if (newOrig == origCosts) break
            origCosts = newOrig
        }
        return result!!
    }

    private fun pass(openQty: Long, openValue: Long, start: String, events: List<Ev>, origCosts: Map<Long, Long>): Result {
        var q = openQty
        var v = if (openQty > 0) openValue else 0L
        var lastVal = if (openQty > 0) openValue else 0L
        var lastQty = if (openQty > 0) openQty else 0L
        val queue = ArrayDeque<Over>()
        val cost = HashMap<Long, Long>()
        val resolved = HashMap<Long, Long>()
        val eventValue = HashMap<Ev, Long>()
        val lineCostSoFar = HashMap<Long, Long>()

        fun avgValue(n: Long): Long = if (lastQty > 0) Money.mulDiv(lastVal, n, lastQty) else 0L

        for (e in events) {
            val n = e.qty
            if (n == 0L) { eventValue[e] = 0; e.lineId?.let { cost[it] = 0 }; continue }
            if (e.inflow) {
                val w: Long = e.fixedValue ?: if (e.type == "SALES_RETURN" && e.origLineId != null && e.origBaseQty > 0) {
                    val oc = origCosts[e.origLineId] ?: lineCostSoFar[e.origLineId]
                    if (oc != null) Money.mulDiv(oc, n, e.origBaseQty) else avgValue(n)
                } else avgValue(n)
                eventValue[e] = w
                e.lineId?.let { cost[it] = w }
                val cover = if (q < 0) minOf(n, -q) else 0L
                var coveredValue = 0L
                if (cover > 0) {
                    coveredValue = if (cover == n) w else Money.mulDiv(w, cover, n)
                    var remaining = cover; var remVal = coveredValue
                    while (remaining > 0 && queue.isNotEmpty()) {
                        val h = queue.first()
                        val t = minOf(h.qtyRem, remaining)
                        val part = if (t == remaining) remVal else Money.mulDiv(remVal, t, remaining)
                        val prov = if (t == h.qtyRem) h.provRem else Money.mulDiv(h.provRem, t, h.qtyRem)
                        resolved[h.lineId] = (resolved[h.lineId] ?: 0L) + part
                        h.qtyRem -= t; h.provRem -= prov
                        remaining -= t; remVal -= part
                        if (h.qtyRem == 0L) queue.removeFirst()
                    }
                }
                val before = q
                q += n
                if (q > 0) {
                    v = (if (before > 0) v else 0L) + (w - coveredValue)
                    lastVal = v; lastQty = q
                } else {
                    v = 0; lastVal = w; lastQty = n
                }
            } else {
                val avail = maxOf(q, 0L)
                val fromStock = minOf(n, avail)
                val c1 = when { fromStock == 0L -> 0L; fromStock == avail -> v; else -> Money.mulDiv(v, fromStock, avail) }
                v -= c1
                val over = n - fromStock
                if (over > 0 && e.lineId != null) {
                    queue.addLast(Over(e.lineId, over, avgValue(over)))
                }
                q -= n
                if (q <= 0) v = 0
                e.lineId?.let { cost[it] = c1; lineCostSoFar[it] = c1 + if (over > 0) avgValue(over) else 0L }
            }
        }
        val provisional = HashSet<Long>()
        val unresolved = HashMap<Long, Long>()
        for (o in queue) { unresolved[o.lineId] = (unresolved[o.lineId] ?: 0L) + o.provRem; provisional.add(o.lineId) }
        val finalCost = HashMap<Long, Long>()
        for (e in events) {
            val id = e.lineId ?: continue
            finalCost[id] = if (e.inflow) cost[id] ?: 0L else (cost[id] ?: 0L) + (resolved[id] ?: 0L) + (unresolved[id] ?: 0L)
        }
        // Pass 2: running balances with final costs.
        val moves = ArrayList<StockMove>()
        var q2 = openQty; var v2 = openValue
        var ord = 0L
        if (openQty != 0L || openValue != 0L)
            moves.add(StockMove(0, 0, null, null, LocalDate.parse(start), ord++, "OPENING", openQty, openValue, q2, v2, false))
        for (e in events) {
            val id = e.lineId ?: continue
            val c = finalCost[id] ?: 0L
            val dq = if (e.inflow) e.qty else -e.qty
            val dv = if (e.inflow) c else -c
            q2 += dq; v2 += dv
            moves.add(StockMove(0, 0, e.voucherId, id, LocalDate.parse(e.date), ord++, e.type, dq, dv, q2, v2, id in provisional))
        }
        if (q2 > 0 && v2 != v) throw IllegalStateException("Costing self-check failed (value $v2 vs $v)")
        if (q2 == 0L && v2 != 0L) throw IllegalStateException("Costing self-check failed: value left with zero quantity")
        return Result(finalCost, provisional, moves)
    }

    /** Writes costs and movements for one product; returns ids of vouchers whose costs changed. */
    private fun recomputeProduct(pid: Long): Set<Long> {
        val r = calculate(pid)
        val changed = HashSet<Long>()
        val current = db.list("SELECT l.id, l.voucher_id, l.cost FROM voucher_line l JOIN voucher v ON v.id = l.voucher_id WHERE l.product_id = ? AND v.status = 'POSTED'", pid) {
            Triple(it.long("id"), it.long("voucher_id"), it.long("cost"))
        }
        for ((lid, vid, c) in current) {
            val nc = r.lineCosts[lid] ?: 0L
            if (nc != c) { db.update("UPDATE voucher_line SET cost = ? WHERE id = ?", nc, lid); changed.add(vid) }
        }
        db.update("DELETE FROM stock_move WHERE product_id = ?", pid)
        for (m in r.moves) db.insert("""INSERT INTO stock_move(product_id, voucher_id, line_id, date, ord, kind, qty, value, balance_qty, balance_value, provisional)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""", pid, m.voucherId, m.lineId, m.date.toString(), m.ord, m.kind, m.qty, m.value, m.balanceQty, m.balanceValue, if (m.provisional) 1 else 0)
        return changed
    }

    // ---------------- Queries ----------------
    private fun move(r: Row) = StockMove(r.long("id"), r.long("product_id"), r.longOrNull("voucher_id"), r.longOrNull("line_id"),
        LocalDate.parse(r.string("date")), r.long("ord"), r.string("kind"), r.long("qty"), r.long("value"), r.long("balance_qty"),
        r.long("balance_value"), r.bool("provisional"))

    fun moves(pid: Long): List<StockMove> = db.list("SELECT * FROM stock_move WHERE product_id = ? ORDER BY ord", pid, mapper = ::move)

    /** Quantity and value on hand at the end of [date] (null = latest). */
    fun balanceAt(pid: Long, date: LocalDate?): Pair<Long, Long> {
        val m = if (date == null) db.first("SELECT * FROM stock_move WHERE product_id = ? ORDER BY ord DESC LIMIT 1", pid, mapper = ::move)
                else db.first("SELECT * FROM stock_move WHERE product_id = ? AND date <= ? ORDER BY ord DESC LIMIT 1", pid, date.toString(), mapper = ::move)
        return if (m == null) {
            val p = b.masters.product(pid)
            if (date == null || !date.isBefore(b.years.booksStart())) p.openingQty to p.openingValue else 0L to 0L
        } else m.balanceQty to m.balanceValue
    }

    /** Lowest balance reached on or after [date] (used for shortage warnings). */
    fun minBalanceFrom(pid: Long, date: LocalDate): Long? =
        db.longOrNull("SELECT MIN(balance_qty) FROM stock_move WHERE product_id = ? AND date >= ?", pid, date.toString())

    fun averageCost(pid: Long): Long {
        val (q, v) = balanceAt(pid, null)
        return if (q > 0) Money.mulDiv(v, 1000, q) else 0L
    }
}
