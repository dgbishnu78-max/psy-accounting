package np.psy.core.export

import np.psy.core.report.ReportTable

/** RFC 4180 CSV. Amounts are plain numbers (no thousands separators) so spreadsheets read them. */
object Csv {
    fun escape(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun line(cells: List<String>) = cells.joinToString(",") { escape(it) }

    fun report(t: ReportTable, company: String): String {
        val sb = StringBuilder()
        sb.append(line(listOf(company))).append("\r\n")
        sb.append(line(listOf(t.title))).append("\r\n")
        sb.append(line(listOf(t.period))).append("\r\n")
        sb.append(line(t.columns.map { it.title })).append("\r\n")
        for (r in t.rows) {
            val cells = t.columns.indices.map { i -> t.csvCell(r.cells.getOrNull(i)) }
            sb.append(line(cells)).append("\r\n")
        }
        for (w in t.warnings) sb.append(line(listOf("WARNING: $w"))).append("\r\n")
        for (n in t.notes) sb.append(line(listOf("Note: $n"))).append("\r\n")
        return sb.toString()
    }

    fun reports(ts: List<ReportTable>, company: String): String = ts.joinToString("\r\n") { report(it, company) }

    /** UTF-8 with BOM so Excel shows Nepali text correctly. */
    fun bytes(text: String): ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
}
