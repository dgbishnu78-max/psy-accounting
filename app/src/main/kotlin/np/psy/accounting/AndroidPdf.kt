package np.psy.accounting

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import np.psy.core.export.PdfCanvas
import java.io.ByteArrayOutputStream

/** PdfCanvas on Android's PdfDocument: uses system fonts, so Nepali (Devanagari) names print correctly. */
class AndroidPdf : PdfCanvas {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var n = 0
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); color = Color.BLACK }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val fill = Paint().apply { style = Paint.Style.FILL }

    override fun startPage(width: Float, height: Float) {
        page?.let { doc.finishPage(it) }
        n++
        page = doc.startPage(PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), n).create())
    }

    private fun paint(size: Float, b: Boolean, gray: Boolean): Paint = (if (b) bold else regular).apply { textSize = size; color = if (gray) Color.GRAY else Color.BLACK }

    override fun text(x: Float, y: Float, s: String, size: Float, bold: Boolean, gray: Boolean) {
        page!!.canvas.drawText(s, x, y, paint(size, bold, gray))
    }

    override fun textWidth(s: String, size: Float, bold: Boolean): Float = paint(size, bold, false).measureText(s)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        stroke.strokeWidth = width
        page!!.canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, gray: Float) {
        val g = (gray * 255).toInt().coerceIn(0, 255)
        fill.color = Color.rgb(g, g, g)
        page!!.canvas.drawRect(x, y, x + w, y + h, fill)
    }

    override fun jpeg(x: Float, y: Float, w: Float, h: Float, data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        page!!.canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), null)
    }

    override fun finish(): ByteArray {
        page?.let { doc.finishPage(it) }; page = null
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
