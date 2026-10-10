package np.psy.accounting

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import np.psy.accounting.ui.*
import np.psy.core.export.Csv
import np.psy.core.export.PdfReports
import np.psy.core.report.ReportTable
import java.io.File
import java.io.FileOutputStream

/** Save to device (file picker), share, or print PDFs and CSV files. */
object Exporter {
    fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(80)

    fun reportPdf(tables: List<ReportTable>): ByteArray = PdfReports(App.books()).reports(AndroidPdf(), tables)

    fun documentPdf(voucherId: Long): ByteArray {
        val logo = App.books().settings.get("logo_jpeg_base64").takeIf { it.isNotEmpty() }?.let { try { android.util.Base64.decode(it, android.util.Base64.DEFAULT) } catch (e: Exception) { null } }
        return PdfReports(App.books()).document(AndroidPdf(), voucherId, logo)
    }

    fun offer(act: MainActivity, name: String, mime: String, bytes: ByteArray) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add("Save to phone / USB / SD card" to { saveAs(act, name, mime, bytes) })
        opts.add("Share (WhatsApp, Viber, email...)" to { share(act, name, mime, bytes) })
        if (mime == "application/pdf") opts.add("Print" to { print(act, name, bytes) })
        act.pick("$name", opts, { it.first }) { it.second() }
    }

    fun offerReport(act: MainActivity, tables: List<ReportTable>, pdf: Boolean) {
        val base = safeName(tables.first().title + "_" + java.time.LocalDate.now())
        act.working(if (pdf) "Preparing PDF..." else "Preparing CSV...", {
            if (pdf) reportPdf(tables) else Csv.bytes(Csv.reports(tables, App.books().settings.companyName))
        }) { bytes -> offer(act, "$base.${if (pdf) "pdf" else "csv"}", if (pdf) "application/pdf" else "text/csv", bytes) }
    }

    fun saveAs(act: MainActivity, name: String, mime: String, bytes: ByteArray) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name)
        act.startForResult(i) { code, data ->
            val uri = data?.data
            if (code == Activity.RESULT_OK && uri != null) {
                act.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                act.toast("Saved $name")
            }
        }
    }

    /** Copies a file to a location the user picks (e.g. Downloads, USB, SD card). */
    fun saveFileAs(act: MainActivity, file: File, mime: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, file.name)
        act.startForResult(i) { code, data ->
            val uri = data?.data
            if (code == Activity.RESULT_OK && uri != null) {
                act.contentResolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                act.toast("Exported ${file.name}")
            }
        }
    }

    fun share(act: MainActivity, name: String, mime: String, bytes: ByteArray) {
        val dir = File(act.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name); FileOutputStream(f).use { it.write(bytes) }
        val uri = Uri.parse("content://${ShareProvider.AUTHORITY}/${Uri.encode(name)}")
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Share $name"))
    }

    fun print(act: MainActivity, name: String, pdf: ByteArray) {
        val pm = act.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try { FileOutputStream(dest.fileDescriptor).use { it.write(pdf) }; cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }
}

/** Serves files from the app's share folder to the app the user shares with (read-only, temporary). */
class ShareProvider : ContentProvider() {
    companion object { const val AUTHORITY = "np.psy.accounting.share" }
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw IllegalArgumentException("bad uri")
        val dir = File(context!!.cacheDir, "share")
        val f = File(dir, name)
        if (f.canonicalFile.parentFile != dir.canonicalFile) throw SecurityException("bad path")
        return f
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')) { "pdf" -> "application/pdf"; "csv" -> "text/csv"; "zip" -> "application/zip"; else -> "application/octet-stream" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
