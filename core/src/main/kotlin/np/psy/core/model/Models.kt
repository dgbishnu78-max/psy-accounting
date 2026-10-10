package np.psy.core.model

import java.time.LocalDate

enum class AccountType(val label: String, val debitNormal: Boolean) {
    ASSET("Asset", true), LIABILITY("Liability", false), EQUITY("Equity", false),
    REVENUE("Revenue", false), COGS("Cost of Goods Sold", true), EXPENSE("Expense", true);
    val isPnl get() = this == REVENUE || this == COGS || this == EXPENSE
}

enum class VatKind(val label: String) { TAXABLE("Taxable"), ZERO_RATED("Zero-rated"), EXEMPT("Exempt") }

enum class VatClass(val label: String) {
    NONE("No VAT"), ELIGIBLE("Eligible input VAT"), INELIGIBLE("Ineligible input VAT"), PENDING("Pending verification")
}

enum class VoucherType(val label: String, val stockSign: Int) {
    SALE("Sales Invoice", -1), PURCHASE("Purchase Invoice", +1), SALES_RETURN("Sales Return (Credit Note)", +1),
    PURCHASE_RETURN("Purchase Return (Debit Note)", -1), EXPENSE("Expense", 0), STOCK_ADJUSTMENT("Stock Adjustment", 0);
}

enum class VoucherStatus { POSTED, CANCELLED, SUPERSEDED }
enum class FyStatus(val label: String) { OPEN("Open"), CLOSING("Closing in Progress"), CLOSED("Closed") }

data class UnitM(val id: Long, val name: String, val decimals: Int, val active: Boolean)
data class VatCategory(val id: Long, val name: String, val rateBp: Int, val kind: VatKind, val active: Boolean) {
    val rateLabel get() = np.psy.core.util.Money.format(rateBp.toLong()).removeSuffix(".00") + "%"
}
data class Account(val id: Long, val code: String, val name: String, val type: AccountType, val subtype: String, val systemKey: String?, val active: Boolean) {
    val label get() = "$code $name"
}
data class ExpenseCategory(val id: Long, val name: String, val accountId: Long, val active: Boolean)
data class Party(val id: Long, val name: String, val isCustomer: Boolean, val isSupplier: Boolean, val pan: String,
                 val address: String, val phone: String, val email: String, val active: Boolean)
data class Product(val id: Long, val code: String, val name: String, val category: String, val unitId: Long, val vatCategoryId: Long,
                   val salePrice: Long, val openingQty: Long, val openingValue: Long, val minStock: Long, val active: Boolean)
data class ProductUnit(val id: Long, val productId: Long, val unitId: Long, val factor: Long)
data class FiscalYear(val id: Long, val name: String, val start: LocalDate, val end: LocalDate, val status: FyStatus) {
    fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}

data class Voucher(
    val id: Long, val type: VoucherType, val number: String, val date: LocalDate, val seq: Long, val status: VoucherStatus,
    val partyId: Long?, val partyName: String, val partyPan: String, val partyAddress: String, val refNo: String,
    val originalVoucherId: Long?, val settleAccountId: Long?, val expenseCategoryId: Long?, val vatClass: VatClass,
    val description: String, val gross: Long, val discount: Long, val taxable: Long, val vat: Long, val total: Long,
    val notes: String, val docRef: String, val replacesId: Long?, val replacedById: Long?, val cancelReason: String,
    val cancelledAt: String?, val createdAt: String,
)

data class VoucherLine(
    val id: Long, val voucherId: Long, val lineNo: Int, val productId: Long?, val description: String, val unitId: Long?,
    val qty: Long, val factor: Long, val baseQty: Long, val rate: Long, val gross: Long, val discount: Long, val taxable: Long,
    val vatCategoryId: Long?, val vatKind: VatKind, val vatRateBp: Int, val vat: Long, val total: Long, val cost: Long,
    val originalLineId: Long?,
)

data class Journal(val id: Long, val voucherId: Long?, val type: String, val number: String, val date: LocalDate, val seq: Long,
                   val narration: String, val status: String, val reversalOfId: Long?, val createdAt: String)
data class JournalLine(val id: Long, val journalId: Long, val accountId: Long, val debit: Long, val credit: Long, val memo: String)

/** Input for one item line of a sales/purchase/return/adjustment document. */
data class LineInput(
    val productId: Long,
    /** Quantity in the entered unit, thousandths. */
    val qty: Long,
    /** Price per entered unit, paisa. For stock adjustment IN: unit cost (0 = current average). */
    val rate: Long,
    val discount: Long = 0,
    /** Entered unit; null = product base unit. */
    val unitId: Long? = null,
    /** null = product's VAT category. */
    val vatCategoryId: Long? = null,
    val originalLineId: Long? = null,
    val description: String = "",
)

data class DocInput(
    val type: VoucherType,
    val date: LocalDate,
    val partyName: String = "",
    val partyPan: String = "",
    val partyAddress: String = "",
    /** Supplier bill number (purchase), bill/reference number (expense), customer reference (sale). */
    val refNo: String = "",
    /** Invoice number for sales; blank = next automatic number. */
    val number: String = "",
    val originalVoucherId: Long? = null,
    /** Account debited (sales) or credited (purchases/expenses). null = Receivables/Payables. */
    val settleAccountId: Long? = null,
    val vatClass: VatClass = VatClass.NONE,
    val expenseCategoryId: Long? = null,
    val description: String = "",
    val notes: String = "",
    val docRef: String = "",
    /** Unique token generated when the entry form opens, to stop double posting. */
    val postToken: String? = null,
    val lines: List<LineInput> = emptyList(),
    /** Expense only. */
    val expenseAmount: Long = 0,
    val expenseVat: Long = 0,
    /** Stock adjustment only: true = stock in, false = stock out. */
    val adjustmentIn: Boolean = true,
    /** User explicitly confirmed posting despite insufficient stock. */
    val allowNegativeStock: Boolean = false,
)

data class StockShortage(val productId: Long, val productName: String, val available: Long, val required: Long, val unit: String)

class NeedsStockConfirmation(val shortages: List<StockShortage>) :
    RuntimeException("Insufficient stock: " + shortages.joinToString { it.productName })

data class PostResult(val voucherId: Long, val number: String, val warnings: List<String>)

data class JournalLineInput(val accountId: Long, val debit: Long, val credit: Long, val memo: String = "")

data class StockMove(val id: Long, val productId: Long, val voucherId: Long?, val lineId: Long?, val date: LocalDate, val ord: Long,
                     val kind: String, val qty: Long, val value: Long, val balanceQty: Long, val balanceValue: Long, val provisional: Boolean)
