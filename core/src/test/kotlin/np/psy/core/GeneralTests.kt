package np.psy.core

import np.psy.core.T.eq
import np.psy.core.T.fails
import np.psy.core.T.ok
import np.psy.core.T.test
import np.psy.core.backup.BackupFormat
import np.psy.core.db.Schema
import np.psy.core.export.Csv
import np.psy.core.export.PdfReports
import np.psy.core.export.SimplePdf
import np.psy.core.model.*
import np.psy.core.util.Money
import java.io.ByteArrayOutputStream
import java.io.File

object GeneralTests {
    fun register() {
        test("G37", "PDF and CSV exports contain correct data") {
            val fx = AccountingTests.scenario()
            fx.books.settings.set("company_pan", "601234567")
            val tb = fx.books.reports.trialBalance(fx.d("2083-06-31"))
            val csv = Csv.report(tb, fx.books.settings.companyName)
            val lines = csv.split("\r\n")
            eq("P.S.Y. Trading Private Limited", lines[0])
            eq("Code,Account,Debit,Credit", lines[3])
            val totalLine = lines.first { it.startsWith(",Total,") }
            val parts = totalLine.split(",")
            eq(parts[2], parts[3], "CSV totals equal")
            ok(!parts[2].contains(" "), "plain numbers")
            ok(Csv.escape("a,b") == "\"a,b\"" && Csv.escape("say \"hi\"") == "\"say \"\"hi\"\"\"", "escaping")
            val pdf = PdfReports(fx.books).reports(SimplePdf(), listOf(tb, fx.books.reports.balanceSheet(fx.d("2083-06-31"))))
            val text = String(pdf, Charsets.ISO_8859_1)
            ok(text.startsWith("%PDF-1.4") && text.trimEnd().endsWith("%%EOF"), "valid PDF wrapper")
            ok(text.contains("(Trial Balance)") && text.contains("(Balance Sheet)"), "titles present")
            ok(text.contains("PAN/VAT: 601234567"), "company PAN on report")
            val tot = Money.format((tb.rows.last().cells[2] as np.psy.core.report.Amt).paisa)
            ok(text.contains("($tot)"), "total $tot in PDF")
            val sale = fx.books.docs.search(listOf(VoucherType.SALE)).last()
            val inv = String(PdfReports(fx.books).document(SimplePdf(), sale.id), Charsets.ISO_8859_1)
            ok(inv.contains("(${sale.number})") || inv.contains("Invoice No.: ${sale.number}"), "invoice number")
            ok(inv.contains("(${Money.format(sale.total)})"), "grand total")
            ok(inv.contains(Money.words(sale.total).take(20)), "amount in words")
            ok(inv.contains("BS"), "BS date printed")
            eq("Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Seventy Eight Paisa Only", Money.words(12_345_678))
            eq("1,23,45,678.90", Money.format(1_234_567_890))
        }
        test("G38", "Data persists after closing and reopening the app") {
            val dir = BackupTests.tmpDir()
            val fx = BackupTests.company(dir)
            val counts = BackupTests.counts(fx.db)
            val sales = fx.bal("SALES")
            fx.db.close()
            val db2 = JdbcDb(File(dir, "psy.db"))
            val b2 = Books(db2, fx.clock)
            eq(counts, BackupTests.counts(db2))
            eq(sales, b2.reports.accountBalance(b2.masters.accountId("SALES"), null))
            eq("P.S.Y. Trading Private Limited", b2.settings.companyName)
            eq(1, b2.masters.accounts().count { it.systemKey == "INVENTORY" }, "seed not repeated")
        }
        test("G39", "No network use: the app declares no INTERNET permission and core has no network code") {
            val manifest = listOf(File("app/src/main/AndroidManifest.xml"), File("../app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
            ok(!manifest.contains("android.permission.INTERNET"), "no INTERNET permission")
            val roots = listOf(File("core/src/main/kotlin"), File("app/src/main/kotlin")).filter { it.exists() }
            ok(roots.isNotEmpty(), "sources found")
            val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
                val s = f.readText(); s.contains("java.net.") || s.contains("HttpURLConnection") || s.contains("okhttp") } }
            eq(emptyList<File>(), offenders, "files using network APIs")
        }
        test("G40", "Database upgrades preserve existing records") {
            val dir = BackupTests.tmpDir()
            val fx = BackupTests.company(dir)
            val counts = BackupTests.counts(fx.db)
            val v1Backup = ByteArrayOutputStream().also { BackupFormat.export(fx.db, it, "manual", "t", fx.clock.now()) }.toByteArray()
            // A future version 2 adds a column and a table; existing data must survive.
            val v2 = Schema.MIGRATIONS + (2 to listOf("ALTER TABLE party ADD COLUMN credit_days INTEGER NOT NULL DEFAULT 0", "CREATE TABLE note (id INTEGER PRIMARY KEY, text TEXT)"))
            Schema.migrate(fx.db, "2026-10-10T10:00:00", v2, 2)
            eq(2, fx.db.userVersion)
            eq(counts, BackupTests.counts(fx.db), "all rows kept")
            eq(0L, fx.db.query("SELECT credit_days FROM party LIMIT 1", arrayOf()) { it.long("credit_days") }.first(), "new column has default")
            // A version-1 backup restores into a version-2 database (new column gets its default)
            val fresh = JdbcDb(null)
            Schema.migrate(fresh, "2026-10-10T10:00:00", v2, 2)
            BackupFormat.loadInto(fresh, BackupFormat.read(v1Backup.inputStream()))
            eq(counts, BackupTests.counts(fresh), "v1 backup restored into v2 schema")
            eq(0L, fresh.query("SELECT COUNT(*) AS n FROM party WHERE credit_days <> 0", arrayOf()) { it.long("n") }.first())
            // Opening a database newer than the app is refused instead of damaging it
            fails<IllegalStateException> { Books(fx.db, fx.clock) }
        }
        test("G41", "Calendar corrections change display only and are validated") {
            val fx = Fx()
            val p = fx.product("X")
            val r = fx.purchase("2083-05-01", "S", fx.L(p, 1, 100), bill = "1")
            val stored = fx.books.docs.voucher(r.voucherId).date
            fails<np.psy.core.db.UserError> { fx.m.saveCalendarCorrection(2090, intArrayOf(40, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), "bad") }
            val def = np.psy.core.cal.BsCalendar.defaultMonthLengths(2095)
            val alt = def.copyOf(); alt[0] = def[0] + (if (def[0] < 32) 1 else -1); alt[1] = def[1] - (alt[0] - def[0])
            fx.m.saveCalendarCorrection(2095, alt, "Panchang 2095")
            eq(alt.toList(), (1..12).map { np.psy.core.cal.BsCalendar.daysInMonth(2095, it) })
            eq(stored, fx.books.docs.voucher(r.voucherId).date, "stored AD date unchanged")
            fx.m.saveCalendarCorrection(2095, def, "revert")
            eq(def.toList(), (1..12).map { np.psy.core.cal.BsCalendar.daysInMonth(2095, it) })
        }
    }
}
