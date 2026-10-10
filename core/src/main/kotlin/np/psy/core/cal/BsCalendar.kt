package np.psy.core.cal

import java.time.LocalDate

/**
 * Bikram Sambat (BS) calendar for BS 2000-01-01 (AD 1943-04-14) to BS 2099-12-end (AD 2043-04-13).
 *
 * BS months do not have fixed lengths; they come from the published Nepali Panchang.
 * The month-length table below was built on 2026-10-10 by comparing five independent
 * open-source Nepali calendar datasets (see docs/CALENDAR.md). Years 2000-2083 agree in
 * all sources (except one known outlier for 2062 in a single source). Years 2084 onward are
 * taken from the majority of sources and spot-checked against Hamro Patro; they are
 * projections until the official Panchang for those years is published, and can be
 * corrected in the app (Settings > Calendar corrections) without changing stored data,
 * because transactions are stored as AD dates.
 */
object BsCalendar {
    const val FIRST_YEAR = 2000
    const val LAST_YEAR = 2099
    /** AD date of 2000-01-01 BS. */
    val ANCHOR_AD: LocalDate = LocalDate.of(1943, 4, 14)

    val MONTH_NAMES = arrayOf(
        "Baisakh", "Jestha", "Asar", "Shrawan", "Bhadra", "Asoj",
        "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra"
    )

    private val DEFAULT_TABLE: Array<IntArray> = arrayOf(
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2000 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2001 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2002 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2003 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2004 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2005 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2006 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2007 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31), // 2008 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2009 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2010 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2011 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30), // 2012 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2013 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2014 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2015 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30), // 2016 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2017 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2018 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2019 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2020 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2021 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30), // 2022 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2023 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2024 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2025 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2026 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2027 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2028 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 32, 30, 30, 29, 30, 29, 30, 30), // 2029 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2030 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2031 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2032 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2033 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2034 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31), // 2035 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2036 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2037 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2038 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30), // 2039 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2040 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2041 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2042 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30), // 2043 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2044 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2045 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2046 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2047 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2048 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30), // 2049 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2050 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2051 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2052 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30), // 2053 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2054 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2055 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 32, 30, 30, 29, 30, 29, 30, 30), // 2056 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2057 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2058 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2059 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2060 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2061 (5/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 31, 29, 30, 29, 30, 29, 31), // 2062 (4/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2063 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2064 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2065 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 29, 31), // 2066 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2067 (5/5 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2068 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2069 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 29, 30, 30, 29, 30, 30), // 2070 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2071 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2072 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 31), // 2073 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2074 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2075 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30), // 2076 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2077 (5/5 sources agree)
        intArrayOf(31, 31, 31, 32, 31, 31, 30, 29, 30, 29, 30, 30), // 2078 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2079 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30), // 2080 (5/5 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31), // 2081 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2082 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30), // 2083 (5/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 30, 30, 30, 29, 30, 30, 30), // 2084 (3/5 sources agree)
        intArrayOf(31, 32, 31, 32, 30, 31, 30, 30, 29, 30, 30, 30), // 2085 (3/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2086 (3/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 30, 29, 30, 30, 30), // 2087 (3/5 sources agree)
        intArrayOf(30, 31, 32, 32, 30, 31, 30, 30, 29, 30, 30, 30), // 2088 (4/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2089 (4/5 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2090 (4/5 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 30, 29, 30, 30, 30), // 2091 (2/3 sources agree)
        intArrayOf(30, 31, 32, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2092 (2/3 sources agree)
        intArrayOf(30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2093 (2/3 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 30, 30, 30, 29, 30, 30, 30), // 2094 (2/3 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 30, 29, 30, 30, 30, 30), // 2095 (2/3 sources agree)
        intArrayOf(30, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2096 (2/3 sources agree)
        intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30), // 2097 (2/3 sources agree)
        intArrayOf(31, 31, 32, 31, 31, 31, 29, 30, 29, 30, 29, 31), // 2098 (2/3 sources agree)
        intArrayOf(31, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30), // 2099 (2/3 sources agree)
    )

    @Volatile private var table: Array<IntArray> = DEFAULT_TABLE.map { it.copyOf() }.toTypedArray()
    @Volatile private var yearStartEpoch: LongArray = computeStarts(table)

    private fun computeStarts(t: Array<IntArray>): LongArray {
        val starts = LongArray(t.size + 1)
        var e = ANCHOR_AD.toEpochDay()
        for (i in t.indices) {
            starts[i] = e
            e += t[i].sum()
        }
        starts[t.size] = e
        return starts
    }

    /** Default (built-in) month lengths for a year. */
    fun defaultMonthLengths(year: Int): IntArray {
        checkYear(year)
        return DEFAULT_TABLE[year - FIRST_YEAR].copyOf()
    }

    /**
     * Applies user corrections (year -> 12 month lengths). Only the given years are replaced.
     * Each month must be 29..32 days. Returns the list of years changed.
     */
    @Synchronized
    fun applyCorrections(corrections: Map<Int, IntArray>) {
        val t = DEFAULT_TABLE.map { it.copyOf() }.toTypedArray()
        for ((y, lens) in corrections) {
            validateMonthLengths(y, lens)
            t[y - FIRST_YEAR] = lens.copyOf()
        }
        table = t
        yearStartEpoch = computeStarts(t)
    }

    fun validateMonthLengths(year: Int, lens: IntArray) {
        checkYear(year)
        require(lens.size == 12) { "A BS year must have 12 months" }
        for (i in 0 until 12) require(lens[i] in 29..32) { "${MONTH_NAMES[i]} $year must have 29 to 32 days" }
        val total = lens.sum()
        require(total in 364..367) { "BS year $year would have $total days, which is not plausible" }
    }

    private fun checkYear(year: Int) {
        if (year < FIRST_YEAR || year > LAST_YEAR)
            throw IllegalArgumentException("BS year $year is outside the supported range $FIRST_YEAR-$LAST_YEAR")
    }

    fun isSupportedYear(year: Int) = year in FIRST_YEAR..LAST_YEAR

    fun daysInMonth(year: Int, month: Int): Int {
        checkYear(year)
        require(month in 1..12) { "BS month must be 1-12" }
        return table[year - FIRST_YEAR][month - 1]
    }

    fun daysInYear(year: Int): Int { checkYear(year); return table[year - FIRST_YEAR].sum() }

    val minAd: LocalDate get() = ANCHOR_AD
    val maxAd: LocalDate get() = LocalDate.ofEpochDay(yearStartEpoch[table.size] - 1)

    fun toAd(bs: BsDate): LocalDate {
        checkYear(bs.year)
        require(bs.month in 1..12) { "BS month must be 1-12" }
        val dim = daysInMonth(bs.year, bs.month)
        require(bs.day in 1..dim) { "${MONTH_NAMES[bs.month - 1]} ${bs.year} has only $dim days" }
        var e = yearStartEpoch[bs.year - FIRST_YEAR]
        val lens = table[bs.year - FIRST_YEAR]
        for (m in 0 until bs.month - 1) e += lens[m]
        return LocalDate.ofEpochDay(e + bs.day - 1)
    }

    fun toBs(ad: LocalDate): BsDate {
        val e = ad.toEpochDay()
        if (e < yearStartEpoch[0] || e >= yearStartEpoch[table.size])
            throw IllegalArgumentException("AD date $ad is outside the supported BS range")
        // binary search year
        var lo = 0; var hi = table.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (yearStartEpoch[mid] <= e) lo = mid else hi = mid - 1
        }
        var rem = (e - yearStartEpoch[lo]).toInt()
        val lens = table[lo]
        var m = 0
        while (rem >= lens[m]) { rem -= lens[m]; m++ }
        return BsDate(FIRST_YEAR + lo, m + 1, rem + 1)
    }

    fun monthName(month: Int) = MONTH_NAMES[month - 1]
}
