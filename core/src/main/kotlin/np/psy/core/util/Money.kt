package np.psy.core.util

import np.psy.core.db.UserError
import java.math.BigDecimal
import java.math.RoundingMode

/** Money is stored as Long paisa (1 NPR = 100 paisa). Quantities as Long thousandths. */
object Money {
    /** 123456789 -> "12,34,567.89" (Nepali/Indian digit grouping). */
    fun format(paisa: Long, grouping: Boolean = true): String {
        val neg = paisa < 0
        val abs = Math.abs(paisa)
        val rupees = abs / 100
        val p = abs % 100
        val r = if (grouping) group(rupees.toString()) else rupees.toString()
        return (if (neg) "-" else "") + r + "." + (if (p < 10) "0$p" else "$p")
    }

    fun formatNpr(paisa: Long) = "NPR " + format(paisa)

    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.substring(digits.length - 3)
        var rest = digits.substring(0, digits.length - 3)
        val parts = ArrayList<String>()
        while (rest.length > 2) { parts.add(0, rest.substring(rest.length - 2)); rest = rest.substring(0, rest.length - 2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Parses user text such as "1,250.50" into paisa. Empty -> 0. */
    fun parse(text: String, field: String = "Amount"): Long {
        val t = text.trim().replace(",", "").replace("NPR", "", ignoreCase = true).trim()
        if (t.isEmpty()) return 0
        val bd = try { BigDecimal(t) } catch (e: NumberFormatException) { throw UserError("$field: '$text' is not a valid amount") }
        if (bd.scale() > 2 && bd.setScale(2, RoundingMode.DOWN).compareTo(bd) != 0) throw UserError("$field: use at most 2 decimal places")
        if (bd.abs() > BigDecimal("100000000000")) throw UserError("$field is too large")
        return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Rounds a/b half-up (away from zero for .5) using integer math. */
    fun divRound(a: Long, b: Long): Long {
        require(b != 0L)
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** a*b/c rounded half-up without overflow. */
    fun mulDiv(a: Long, b: Long, c: Long): Long {
        require(c != 0L)
        return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b)).divide(BigDecimal.valueOf(c), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** VAT for a taxable amount at a rate in basis points (1300 = 13%). */
    fun vat(taxable: Long, rateBp: Int): Long = mulDiv(taxable, rateBp.toLong(), 10000)

    /** Percentage with 2 decimals, e.g. margin. Returns null if denominator is zero. */
    fun percent(num: Long, den: Long): String = if (den == 0L) "-" else
        BigDecimal.valueOf(num).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(den), 2, RoundingMode.HALF_UP).toPlainString() + "%"

    fun words(paisa: Long): String {
        val neg = paisa < 0
        val abs = Math.abs(paisa)
        val r = abs / 100; val p = abs % 100
        var s = "Rupees " + (if (r == 0L) "Zero" else numberWords(r))
        if (p > 0) s += " and " + numberWords(p) + " Paisa"
        return (if (neg) "Minus " else "") + s + " Only"
    }

    private val ONES = arrayOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
    private val TENS = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    private fun below100(n: Int): String = if (n < 20) ONES[n] else (TENS[n / 10] + if (n % 10 > 0) " " + ONES[n % 10] else "")
    private fun below1000(n: Int): String {
        val h = n / 100; val rest = n % 100
        val parts = ArrayList<String>()
        if (h > 0) parts.add(ONES[h] + " Hundred")
        if (rest > 0) parts.add(below100(rest))
        return parts.joinToString(" ")
    }

    /** Indian/Nepali system: crore, lakh, thousand. */
    fun numberWords(n: Long): String {
        if (n == 0L) return "Zero"
        var x = n
        val parts = ArrayList<String>()
        val crore = x / 10000000; x %= 10000000
        val lakh = x / 100000; x %= 100000
        val thousand = x / 1000; x %= 1000
        if (crore > 0) parts.add(numberWords(crore) + " Crore")
        if (lakh > 0) parts.add(below100(lakh.toInt()) + " Lakh")
        if (thousand > 0) parts.add(below100(thousand.toInt()) + " Thousand")
        if (x > 0) parts.add(below1000(x.toInt()))
        return parts.joinToString(" ")
    }
}

object Qty {
    const val ONE = 1000L

    fun parse(text: String, decimals: Int, field: String = "Quantity"): Long {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return 0
        val bd = try { BigDecimal(t) } catch (e: NumberFormatException) { throw UserError("$field: '$text' is not a valid number") }
        if (bd.stripTrailingZeros().scale() > decimals)
            throw UserError(if (decimals == 0) "$field must be a whole number for this unit" else "$field: use at most $decimals decimal places")
        if (bd.abs() > BigDecimal("1000000000")) throw UserError("$field is too large")
        return bd.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun format(milli: Long, decimals: Int = 3): String {
        val bd = BigDecimal.valueOf(milli).movePointLeft(3)
        val s = bd.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (decimals == 0 && milli % 1000 == 0L) (milli / 1000).toString() else s
    }

    /** amount = qty(milli) * rate(paisa per unit) / 1000 */
    fun amount(qtyMilli: Long, ratePaisa: Long): Long = Money.mulDiv(qtyMilli, ratePaisa, 1000)
}
