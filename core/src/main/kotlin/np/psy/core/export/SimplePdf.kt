package np.psy.core.export

import java.io.ByteArrayOutputStream

/** Minimal PDF 1.4 writer: Helvetica fonts (Latin text only), lines, rectangles and JPEG images. */
class SimplePdf : PdfCanvas {
    private class Page(val w: Float, val h: Float) { val content = StringBuilder(); val images = ArrayList<Img>() }
    private class Img(val name: String, val data: ByteArray, val pxW: Int, val pxH: Int)
    private val pages = ArrayList<Page>()
    private var imgCount = 0
    private val cur get() = pages.last()

    override fun startPage(width: Float, height: Float) { pages.add(Page(width, height)) }

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val ch = if (c.code in 32..126) c else '?'
            if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    override fun text(x: Float, y: Float, s: String, size: Float, bold: Boolean, gray: Boolean) {
        val py = cur.h - y
        cur.content.append(if (gray) "0.45 g " else "0 g ")
        cur.content.append("BT /${if (bold) "F2" else "F1"} ${f(size)} Tf ${f(x)} ${f(py)} Td (${esc(s)}) Tj ET\n")
    }

    override fun textWidth(s: String, size: Float, bold: Boolean): Float {
        val w = if (bold) BOLD else REG
        var t = 0
        for (c in s) t += if (c.code in 32..126) w[c.code - 32] else 556
        return t * size / 1000f
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        cur.content.append("0 G ${f(width)} w ${f(x1)} ${f(cur.h - y1)} m ${f(x2)} ${f(cur.h - y2)} l S\n")
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, gray: Float) {
        cur.content.append("${f(gray)} g ${f(x)} ${f(cur.h - y - h)} ${f(w)} ${f(h)} re f 0 g\n")
    }

    override fun jpeg(x: Float, y: Float, w: Float, h: Float, data: ByteArray) {
        val (pw, ph) = jpegSize(data) ?: return
        val name = "Im${++imgCount}"
        cur.images.add(Img(name, data, pw, ph))
        cur.content.append("q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(cur.h - y - h)} cm /$name Do Q\n")
    }

    override fun finish(): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: ByteArray) { offsets.add(out.size()); out.write("${offsets.size} 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray()) }
        fun obj(body: String) = obj(body.toByteArray(Charsets.ISO_8859_1))
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        // 1 catalog, 2 pages, 3 font regular, 4 font bold, then per page: page, content, images
        val pageObjIds = ArrayList<Int>()
        var next = 5
        val plan = pages.map { p -> val pid = next++; val cid = next++; val iids = p.images.map { next++ }; pageObjIds.add(pid); Triple(pid, cid, iids) }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${pageObjIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        for ((i, p) in pages.withIndex()) {
            val (_, cid, iids) = plan[i]
            val xobj = if (p.images.isEmpty()) "" else " /XObject << ${p.images.mapIndexed { k, im -> "/${im.name} ${iids[k]} 0 R" }.joinToString(" ")} >>"
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(p.w)} ${f(p.h)}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>$xobj >> /Contents $cid 0 R >>")
            val c = p.content.toString().toByteArray(Charsets.ISO_8859_1)
            obj("<< /Length ${c.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + c + "endstream".toByteArray())
            for (im in p.images) obj("<< /Type /XObject /Subtype /Image /Width ${im.pxW} /Height ${im.pxH} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${im.data.size} >>\nstream\n"
                .toByteArray(Charsets.ISO_8859_1) + im.data + "\nendstream".toByteArray())
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format("%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    companion object {
        /** Width and height of a baseline/progressive JPEG, or null. */
        fun jpegSize(d: ByteArray): Pair<Int, Int>? {
            if (d.size < 4 || d[0] != 0xFF.toByte() || d[1] != 0xD8.toByte()) return null
            var i = 2
            while (i + 9 < d.size) {
                if (d[i] != 0xFF.toByte()) return null
                val marker = d[i + 1].toInt() and 0xFF
                val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((d[i + 5].toInt() and 0xFF) shl 8) or (d[i + 6].toInt() and 0xFF)
                    val w = ((d[i + 7].toInt() and 0xFF) shl 8) or (d[i + 8].toInt() and 0xFF)
                    return w to h
                }
                i += 2 + len
            }
            return null
        }

        private val REG = intArrayOf(278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,
            1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,
            333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584)
        private val BOLD = intArrayOf(278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,
            975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,
            333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584)
    }
}
