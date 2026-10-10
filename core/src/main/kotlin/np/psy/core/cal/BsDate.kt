package np.psy.core.cal

import java.time.LocalDate

/** A Bikram Sambat date. Month 1 = Baisakh ... 12 = Chaitra. */
data class BsDate(val year: Int, val month: Int, val day: Int) : Comparable<BsDate> {
    override fun compareTo(other: BsDate): Int =
        compareValuesBy(this, other, BsDate::year, BsDate::month, BsDate::day)

    fun toAd(): LocalDate = BsCalendar.toAd(this)

    /** e.g. 2083-06-24 */
    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)

    /** e.g. 24 Asoj 2083 */
    fun toLongString(): String = "$day ${BsCalendar.monthName(month)} $year"

    fun plusDays(days: Long): BsDate = BsCalendar.toBs(toAd().plusDays(days))

    fun firstOfMonth() = BsDate(year, month, 1)
    fun lastOfMonth() = BsDate(year, month, BsCalendar.daysInMonth(year, month))

    companion object {
        private val PATTERN = Regex("""^\s*(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})\s*(?:BS|B\.S\.?)?\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Parses a BS date typed by the user, e.g. "2083-06-24", "2083/6/24", "2083-06-24 BS".
         * Throws IllegalArgumentException with a friendly message if invalid.
         */
        fun parse(text: String): BsDate {
            val m = PATTERN.matchEntire(text) ?: throw IllegalArgumentException("Enter the BS date as YYYY-MM-DD, for example 2083-06-24")
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            if (!BsCalendar.isSupportedYear(y)) throw IllegalArgumentException("BS year must be between ${BsCalendar.FIRST_YEAR} and ${BsCalendar.LAST_YEAR}")
            if (mo !in 1..12) throw IllegalArgumentException("BS month must be between 1 and 12")
            val dim = BsCalendar.daysInMonth(y, mo)
            if (d !in 1..dim) throw IllegalArgumentException("${BsCalendar.monthName(mo)} $y has $dim days")
            return BsDate(y, mo, d)
        }

        fun fromAd(ad: LocalDate): BsDate = BsCalendar.toBs(ad)
    }
}

/** Formatting helpers used everywhere dates are shown. Storage is always ISO AD (yyyy-MM-dd). */
object Dates {
    fun bs(isoAd: String): String = BsDate.fromAd(LocalDate.parse(isoAd)).toString()
    fun bsLabel(isoAd: String): String = bs(isoAd) + " BS"
    fun adLabel(isoAd: String): String = "$isoAd AD"
    fun both(isoAd: String): String = "${bs(isoAd)} BS (${isoAd} AD)"
    fun isoFromBs(text: String): String = BsDate.parse(text).toAd().toString()
}
