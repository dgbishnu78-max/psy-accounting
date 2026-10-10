package np.psy.core.export

/**
 * Drawing surface for PDF output. Coordinates are points from the TOP-LEFT of the page.
 * The Android app implements this with android.graphics.pdf.PdfDocument (full Unicode fonts);
 * [SimplePdf] is a dependency-free implementation used in tests and as a fallback.
 */
interface PdfCanvas {
    fun startPage(width: Float, height: Float)
    fun text(x: Float, y: Float, s: String, size: Float, bold: Boolean = false, gray: Boolean = false)
    fun textWidth(s: String, size: Float, bold: Boolean = false): Float
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 0.5f)
    fun fillRect(x: Float, y: Float, w: Float, h: Float, gray: Float)
    /** JPEG image (logo). */
    fun jpeg(x: Float, y: Float, w: Float, h: Float, data: ByteArray)
    fun finish(): ByteArray
}
