package np.psy.core

import np.psy.core.T.eq
import np.psy.core.T.fails
import np.psy.core.T.ok
import np.psy.core.T.test
import np.psy.core.backup.*
import np.psy.core.db.SqlDb
import np.psy.core.db.Row
import np.psy.core.model.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupTests {
    fun tmpDir(): File = Files.createTempDirectory("psytest").toFile()

    class Files_ : DbFiles {
        override fun open(file: File): SqlDb = JdbcDb(file)
        override fun close(db: SqlDb) { (db as JdbcDb).close() }
    }

    /** File-backed company with the standard scenario. */
    fun company(dir: File): Fx {
        val fx = Fx(File(dir, "psy.db"))
        val a = fx.product("Rice", openingQty = 10_000, openingValue = 500_000)
        fx.purchase("2083-05-01", "Supplier", fx.L(a, 20, 52000), bill = "B-1")
        fx.sale("2083-05-03", "Customer", fx.L(a, 5, 80000))
        fx.expense("2083-05-04", "Rent", 1_000_000, 0, VatClass.NONE)
        return fx
    }

    fun counts(db: SqlDb) = np.psy.core.db.Schema.TABLES.associateWith { t -> db.query("SELECT COUNT(*) AS n FROM $t", arrayOf()) { it.long("n") }.first() }

    fun register() {
        test("B21", "Manual backup and restore work correctly") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            val f = mgr.create(fx.db, BackupManager.Kind.MANUAL)
            ok(f.name.startsWith("PSY_Backup_2026-10-10_") && f.name.endsWith("_manual.zip"), f.name)
            val before = counts(fx.db); val salesBefore = fx.bal("SALES")
            // change data after the backup
            fx.sale("2083-05-05", "Customer", fx.L(fx.m.products().first().id, 1, 80000))
            ok(counts(fx.db)["voucher"] != before["voucher"], "data changed")
            val out = RestoreService(Files_(), File(dir, "psy.db"), mgr, fx.clock).restore(fx.db, f, confirmed = true)
            val books = Books(out.db, fx.clock)
            eq(before, counts(out.db), "row counts restored")
            eq(salesBefore, books.reports.accountBalance(books.masters.accountId("SALES"), null))
            val tb = books.reports.trialBalanceLines(null); eq(tb.sumOf { it.debit }, tb.sumOf { it.credit })
            fails<BackupError>("restore needs confirmation") { RestoreService(Files_(), File(dir, "psy.db"), mgr, fx.clock).restore(out.db, f, confirmed = false) }
        }
        test("B22", "Automatic daily backup: once per calendar day with date-based name") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            val f1 = mgr.runDailyIfNeeded(fx.db, 7)
            eq("PSY_Backup_2026-10-10.zip", f1!!.name)
            eq(null, mgr.runDailyIfNeeded(fx.db, 7), "second run the same day does nothing")
            fx.clock.t = fx.clock.t.plusDays(1)
            eq("PSY_Backup_2026-10-11.zip", mgr.runDailyIfNeeded(fx.db, 7)!!.name)
            eq(2, mgr.dailyDir.listFiles()!!.count { it.name.endsWith(".zip") })
            ok(mgr.status().lastSuccessFile == "PSY_Backup_2026-10-11.zip", "status shows last success")
            // A damaged file for today is not treated as a valid backup: it gets replaced
            File(mgr.dailyDir, "PSY_Backup_2026-10-11.zip").writeText("garbage")
            ok(mgr.runDailyIfNeeded(fx.db, 7) != null, "corrupt daily backup replaced")
            BackupFormat.validate(File(mgr.dailyDir, "PSY_Backup_2026-10-11.zip"))
        }
        test("B23", "A failed backup never deletes existing valid backups") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            repeat(3) { mgr.runDailyIfNeeded(fx.db, 1); fx.clock.t = fx.clock.t.plusDays(1) }
            eq(1, mgr.dailyDir.listFiles()!!.count { it.name.endsWith(".zip") }, "keep only latest")
            val survivor = mgr.dailyDir.listFiles()!!.first { it.name.endsWith(".zip") }
            val broken = object : SqlDb by fx.db {
                override fun <T> query(sql: String, args: Array<out Any?>, mapper: (Row) -> T): List<T> =
                    if (sql.contains("FROM voucher_line")) throw RuntimeException("disk error") else fx.db.query(sql, args, mapper)
            }
            fails<BackupError> { mgr.runDailyIfNeeded(broken, 1) }
            ok(survivor.exists(), "previous valid backup kept")
            BackupFormat.validate(survivor)
            ok(mgr.dailyDir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no partial file left")
            ok(mgr.status().lastError != null, "error recorded for display")
            eq(emptyList<File>(), mgr.applyRetention(1), "retention does nothing when today's backup failed")
            ok(survivor.exists(), "still kept")
        }
        test("B24", "Retention keeps the configured number of successful backups") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            repeat(10) { mgr.runDailyIfNeeded(fx.db, 7); fx.clock.t = fx.clock.t.plusDays(1) }
            val names = mgr.dailyDir.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name }.sorted()
            eq(7, names.size); eq("PSY_Backup_2026-10-13.zip", names.first()); eq("PSY_Backup_2026-10-19.zip", names.last())
            // An invalid file does not count towards the number kept
            File(mgr.dailyDir, "PSY_Backup_2026-10-18.zip").writeText("bad")
            mgr.runDailyIfNeeded(fx.db, 3)
            val left = mgr.dailyDir.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name }.sorted()
            eq(listOf("PSY_Backup_2026-10-17.zip", "PSY_Backup_2026-10-19.zip", "PSY_Backup_2026-10-20.zip"), left)
        }
        test("B25", "Corrupt and incompatible backup files are rejected safely") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            val good = mgr.create(fx.db, BackupManager.Kind.MANUAL)
            val notZip = File(dir, "x.zip").also { it.writeText("hello") }
            fails<BackupError> { BackupFormat.validate(notZip) }
            val truncated = File(dir, "t.zip").also { it.writeBytes(good.readBytes().copyOf(good.length().toInt() / 2)) }
            fails<BackupError> { BackupFormat.validate(truncated) }
            fun rewrite(name: String, edit: (String, ByteArray) -> ByteArray?): File {
                val out = File(dir, name)
                ZipFile(good).use { z -> ZipOutputStream(out.outputStream()).use { zo ->
                    for (e in z.entries()) { val data = edit(e.name, z.getInputStream(e).readBytes()) ?: continue; zo.putNextEntry(ZipEntry(e.name)); zo.write(data); zo.closeEntry() } } }
                return out
            }
            val tampered = rewrite("tampered.zip") { n, d -> if (n == "data/voucher.json") String(d).replace("Customer", "Hacker").toByteArray() else d }
            ok(fails<BackupError> { BackupFormat.validate(tampered) }.message!!.contains("corrupt"), "checksum")
            val missing = rewrite("missing.zip") { n, d -> if (n == "data/journal.json") null else d }
            fails<BackupError> { BackupFormat.validate(missing) }
            val newer = rewrite("newer.zip") { n, d -> if (n == "manifest.json") String(d).replace("\"schemaVersion\":1", "\"schemaVersion\":99").toByteArray() else d }
            ok(fails<BackupError> { BackupFormat.validate(newer) }.message!!.contains("newer"), "version")
            val before = counts(fx.db)
            fails<BackupError> { RestoreService(Files_(), File(dir, "psy.db"), mgr, fx.clock).restore(fx.db, tampered, true) }
            eq(before, counts(fx.db), "current data untouched after rejected restore")
        }
        test("B26", "Restore makes a safety backup of current data first") {
            val dir = tmpDir(); val fx = company(dir)
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            val f = mgr.create(fx.db, BackupManager.Kind.MANUAL)
            fx.sale("2083-05-06", "Late Customer", fx.L(fx.m.products().first().id, 1, 80000))
            val vouchersNow = counts(fx.db).getValue("voucher")
            val out = RestoreService(Files_(), File(dir, "psy.db"), mgr, fx.clock).restore(fx.db, f, true)
            ok(out.safetyBackup.exists() && out.safetyBackup.parentFile.name == "safety", "safety backup in safety folder")
            val info = BackupFormat.validate(out.safetyBackup)
            eq(vouchersNow, info.vouchers, "safety backup holds the pre-restore data")
            ok(info.vouchers > counts(out.db).getValue("voucher"), "restored data is the older backup")
        }
        test("B27", "Backups include accounting and financial-year history") {
            val fx = YearEndTests.closedYear()
            val dir = tmpDir()
            val mgr = BackupManager(File(dir, "backups"), fx.clock, "test")
            val f = mgr.create(fx.db, BackupManager.Kind.MANUAL)
            val content = BackupFormat.read(f.inputStream())
            for (t in listOf("settings", "product", "party", "voucher", "voucher_line", "stock_move", "journal", "journal_line", "fiscal_year", "fy_closing", "audit_log", "account", "unit", "vat_category"))
                ok((content.info.rowCounts[t] ?: 0L) > 0, "table $t has rows in backup")
            val fresh = JdbcDb(null); Books(fresh, fx.clock)
            BackupFormat.loadInto(fresh, content)
            val b2 = Books(fresh, fx.clock)
            eq(FyStatus.CLOSED, b2.years.all().first { it.name == "2082/83" }.status, "closed status restored")
            eq(fx.books.reports.accountBalance(fx.m.accountId("RETAINED_EARNINGS"), null), b2.reports.accountBalance(b2.masters.accountId("RETAINED_EARNINGS"), null))
        }
    }
}
