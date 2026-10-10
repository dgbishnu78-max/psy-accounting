package np.psy.core.report

import np.psy.core.util.Money
import np.psy.core.util.Qty

enum class Align { LEFT, RIGHT, CENTER }
enum class RowStyle { NORMAL, SECTION, SUBTOTAL, TOTAL, WARNING, MUTED }

data class Col(val title: String, val align: Align = Align.LEFT, val weight: Float = 1f)

/** Money cell (paisa). Rendered with grouping on screen/PDF and as a plain number in CSV. */
data class Amt(val paisa: Long, val blankIfZero: Boolean = false) {
    fun display() = if (blankIfZero && paisa == 0L) "" else Money.format(paisa)
    fun plain() = if (blankIfZero && paisa == 0L) "" else Money.format(paisa, grouping = false)
}

/** Quantity cell (thousandths). */
data class QtyV(val milli: Long, val decimals: Int = 3, val blankIfZero: Boolean = false) {
    fun display() = if (blankIfZero && milli == 0L) "" else Qty.format(milli, decimals)
}

/**
 * A report row. [link] lets the screen drill down: "account:<id>", "voucher:<id>", "journal:<id>", "product:<id>".
 */
data class ReportRow(val cells: List<Any?>, val style: RowStyle = RowStyle.NORMAL, val link: String? = null)

data class ReportTable(
    val title: String,
    val period: String,
    val columns: List<Col>,
    val rows: List<ReportRow>,
    val notes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val landscape: Boolean = false,
) {
    fun displayCell(v: Any?): String = when (v) {
        null -> ""
        is Amt -> v.display()
        is QtyV -> v.display()
        else -> v.toString()
    }

    fun csvCell(v: Any?): String = when (v) {
        null -> ""
        is Amt -> v.plain()
        is QtyV -> if (v.blankIfZero && v.milli == 0L) "" else Qty.format(v.milli, 3)
        else -> v.toString()
    }
}
