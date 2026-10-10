package np.psy.core.backup

import np.psy.core.Books
import np.psy.core.Clock
import np.psy.core.cal.BsDate
import np.psy.core.db.*
import np.psy.core.util.Json
import java.io.*
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupError(message: String) : Exception(message)

data class BackupInfo(
    val file: File?, val kind: String, val createdAt: String, val createdAtBs: String, val schemaVersion: Int, val appVersion: String,
    val company: String, val rowCounts: Map<String, Long>,
) {
    val vouchers get() = rowCounts["voucher"] ?: 0L
    val journals get() = rowCounts["journal"] ?: 0L
}

/**
 * Backup file format (versioned): a ZIP containing manifest.json and one JSON file per table.
 * The manifest stores each table's row count and SHA-256, so incomplete or corrupt files are
 * detected. Data is stored by column name, so backups can be restored into newer app versions
 * (columns added later receive their default values).
 */
object BackupFormat {
    const val FORMAT = "PSY-ACCOUNTS-BACKUP"
    const val FORMAT_VERSION = 1L

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun export(db: SqlDb, out: OutputStream, kind: String, appVersion: String, now: LocalDateTime) {
        val zip = ZipOutputStream(BufferedOutputStream(out))
        val tables = LinkedHashMap<String, Any?>()
        db.transaction {
            for (t in Schema.TABLES) {
                var cols: List<String>? = null
                val rows = db.list("SELECT * FROM $t ORDER BY rowid") { r ->
                    if (cols == null) cols = r.columns()
                    r.columns().map { c -> r.value(c) }
                }
                if (cols == null) cols = db.list("PRAGMA table_info($t)") { it.string("name") }
                val bytes = Json.write(mapOf("table" to t, "columns" to cols, "rows" to rows)).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("data/$t.json")); zip.write(bytes); zip.closeEntry()
                tables[t] = mapOf("rows" to rows.size.toLong(), "sha256" to sha256(bytes))
            }
            val company = db.string("SELECT value FROM settings WHERE key = 'company_name'") ?: ""
            val manifest = mapOf(
                "format" to FORMAT, "formatVersion" to FORMAT_VERSION, "schemaVersion" to db.userVersion.toLong(), "appVersion" to appVersion,
                "kind" to kind, "createdAt" to now.withNano(0).toString(), "createdAtBs" to BsDate.fromAd(now.toLocalDate()).toString(),
                "company" to company, "tables" to tables)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.write(manifest).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        zip.finish(); zip.flush()
    }

    class Content(val info: BackupInfo, val tables: Map<String, Pair<List<String>, List<List<Any?>>>>)

    /** Reads and fully validates a backup. Throws [BackupError] with a clear message if it is unusable. */
    fun read(input: InputStream, file: File? = null): Content {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(BufferedInputStream(input)).use { z ->
                var total = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bytes = z.readBytes()
                    total += bytes.size
                    if (total > 2_000_000_000L) throw BackupError("Backup file is too large")
                    entries[e.name] = bytes
                }
            }
        } catch (e: BackupError) { throw e } catch (e: Exception) { throw BackupError("This file is not a readable backup (damaged or not a ZIP file).") }
        val mBytes = entries["manifest.json"] ?: throw BackupError("This file is not a P.S.Y. Accounts backup (manifest missing).")
        @Suppress("UNCHECKED_CAST")
        val m = try { Json.parse(String(mBytes, Charsets.UTF_8)) as Map<String, Any?> } catch (e: Exception) { throw BackupError("Backup manifest is damaged.") }
        if (m["format"] != FORMAT) throw BackupError("This file is not a P.S.Y. Accounts backup.")
        val fv = (m["formatVersion"] as? Long) ?: throw BackupError("Backup manifest is damaged.")
        if (fv > FORMAT_VERSION) throw BackupError("This backup was made by a newer version of the app. Please update the app before restoring it.")
        val sv = ((m["schemaVersion"] as? Long) ?: 0L).toInt()
        if (sv > Schema.CURRENT_VERSION) throw BackupError("This backup uses database version $sv, which is newer than this app supports (${Schema.CURRENT_VERSION}). Update the app first.")
        if (sv < 1) throw BackupError("Backup manifest has an invalid database version.")
        @Suppress("UNCHECKED_CAST")
        val tmeta = m["tables"] as? Map<String, Map<String, Any?>> ?: throw BackupError("Backup manifest is damaged.")
        val tables = LinkedHashMap<String, Pair<List<String>, List<List<Any?>>>>()
        val counts = LinkedHashMap<String, Long>()
        for ((t, meta) in tmeta) {
            val bytes = entries["data/$t.json"] ?: throw BackupError("Backup is incomplete: table $t is missing.")
            if (sha256(bytes) != meta["sha256"]) throw BackupError("Backup is corrupt: table $t failed its integrity check.")
            @Suppress("UNCHECKED_CAST")
            val obj = Json.parse(String(bytes, Charsets.UTF_8)) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val cols = obj["columns"] as List<String>
            @Suppress("UNCHECKED_CAST") val rows = obj["rows"] as List<List<Any?>>
            if (rows.size.toLong() != meta["rows"]) throw BackupError("Backup is corrupt: table $t row count mismatch.")
            tables[t] = cols to rows; counts[t] = rows.size.toLong()
        }
        for (required in listOf("settings", "account", "voucher", "journal", "journal_line"))
            if (required !in tables) throw BackupError("Backup is incomplete: $required is missing.")
        val info = BackupInfo(file, m["kind"]?.toString() ?: "", m["createdAt"]?.toString() ?: "", m["createdAtBs"]?.toString() ?: "", sv,
            m["appVersion"]?.toString() ?: "", m["company"]?.toString() ?: "", counts)
        return Content(info, tables)
    }

    fun validate(file: File): BackupInfo = FileInputStream(file).use { read(it, file).info }

    /**
     * Loads backup content into [db], which must be a freshly created (migrated, seeded) database.
     * Runs in one transaction; foreign keys are checked at commit.
     */
    fun loadInto(db: SqlDb, content: Content) {
        db.transaction {
            db.execute("PRAGMA defer_foreign_keys = ON")
            for (t in Schema.TABLES.reversed()) db.update("DELETE FROM $t")
            db.update("DELETE FROM sqlite_sequence")
            for (t in Schema.TABLES) {
                val (cols, rows) = content.tables[t] ?: continue
                val existing = db.list("PRAGMA table_info($t)") { it.string("name") }.toSet()
                val use = cols.withIndex().filter { it.value in existing }
                if (use.isEmpty()) continue
                val sql = "INSERT INTO $t (${use.joinToString(",") { it.value }}) VALUES (${use.joinToString(",") { "?" }})"
                for (r in rows) db.insert(sql, *use.map { r[it.index] }.toTypedArray())
            }
        }
        verifyDatabase(db, content.info.rowCounts)
    }

    /** Integrity checks used after restore and before reporting success. */
    fun verifyDatabase(db: SqlDb, expectedCounts: Map<String, Long>? = null) {
        val ic = db.string("PRAGMA integrity_check")
        if (ic != "ok") throw BackupError("Database integrity check failed: $ic")
        val fk = db.list("PRAGMA foreign_key_check") { it.columns().joinToString { c -> "${it.value(c)}" } }
        if (fk.isNotEmpty()) throw BackupError("Database has broken links between records (${fk.size}).")
        if (expectedCounts != null) for ((t, n) in expectedCounts) {
            if (t !in Schema.TABLES) continue
            val got = db.long("SELECT COUNT(*) FROM $t")
            if (got != n) throw BackupError("Restored table $t has $got rows, expected $n.")
        }
        val unbalanced = db.long("SELECT COUNT(*) FROM (SELECT journal_id FROM journal_line GROUP BY journal_id HAVING SUM(debit) <> SUM(credit))")
        if (unbalanced > 0) throw BackupError("Restored data has $unbalanced unbalanced journal entries.")
    }
}

/** Last-backup status shown on the dashboard. Stored next to the backups, outside the database. */
data class BackupStatus(val lastSuccessAt: String?, val lastSuccessFile: String?, val lastErrorAt: String?, val lastError: String?, val lastAttemptAt: String?)

/**
 * Creates, verifies, lists and cleans up backup files.
 * - Daily automatic backups: daily/PSY_Backup_YYYY-MM-DD.zip (AD date), one per calendar day.
 * - Manual backups: manual/PSY_Backup_YYYY-MM-DD_HHMMSS_manual.zip
 * - Safety backups (before restore / year closing / reopening): safety/PSY_Safety_..._<reason>.zip
 * A backup is first written to a temporary file, then re-read and fully verified, and only then
 * renamed to its final name. Old daily backups are deleted only after a newer one has been verified.
 */
class BackupManager(val root: File, private val clock: Clock, private val appVersion: String) {
    val dailyDir = File(root, "daily"); val manualDir = File(root, "manual"); val safetyDir = File(root, "safety")
    private val statusFile = File(root, "backup_status.json")

    enum class Kind(val dir: String) { DAILY("daily"), MANUAL("manual"), SAFETY("safety") }

    init { listOf(root, dailyDir, manualDir, safetyDir).forEach { it.mkdirs() } }

    fun dirFor(k: Kind) = when (k) { Kind.DAILY -> dailyDir; Kind.MANUAL -> manualDir; Kind.SAFETY -> safetyDir }

    private fun stamp(t: LocalDateTime) = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))

    fun fileNameFor(kind: Kind, now: LocalDateTime, reason: String = ""): String = when (kind) {
        Kind.DAILY -> "PSY_Backup_${now.toLocalDate()}.zip"
        Kind.MANUAL -> "PSY_Backup_${stamp(now)}_manual.zip"
        Kind.SAFETY -> "PSY_Safety_${stamp(now)}_${reason.replace(Regex("[^A-Za-z0-9-]"), "-").take(30)}.zip"
    }

    /** Writes and verifies a backup. Never leaves a partial file under a final name. */
    fun create(db: SqlDb, kind: Kind, reason: String = ""): File {
        val now = clock.now()
        val dir = dirFor(kind)
        dir.mkdirs()
        val target = File(dir, fileNameFor(kind, now, reason))
        val tmp = File(dir, target.name + ".tmp")
        markAttempt(now)
        try {
            FileOutputStream(tmp).use { fos ->
                BackupFormat.export(db, fos, kind.name.lowercase(), appVersion, now)
                fos.flush(); fos.fd.sync()
            }
            val info = BackupFormat.validate(tmp)
            val expected = Schema.TABLES.associateWith { db.long("SELECT COUNT(*) FROM $it") }
            for ((t, n) in expected) if ((info.rowCounts[t] ?: -1L) != n) throw BackupError("Verification failed for table $t")
            if (target.exists()) {
                // Same-day daily backup being refreshed: keep the old one until the new one is in place.
                val old = File(dir, target.name + ".old")
                old.delete()
                if (!target.renameTo(old)) throw BackupError("Could not replace existing backup ${target.name}")
                if (!tmp.renameTo(target)) { old.renameTo(target); throw BackupError("Could not save backup file") }
                old.delete()
            } else if (!tmp.renameTo(target)) throw BackupError("Could not save backup file")
            markSuccess(now, target)
            return target
        } catch (e: Exception) {
            tmp.delete()
            markError(now, e.message ?: e.toString())
            throw if (e is BackupError) e else BackupError("Backup failed: ${e.message ?: e}")
        }
    }

    /** Daily backup if none exists for today, then retention cleanup. Returns the new file or null if not needed. */
    fun runDailyIfNeeded(db: SqlDb, retention: Int): File? {
        val today = clock.today()
        val existing = File(dailyDir, fileNameFor(Kind.DAILY, clock.now()))
        val valid = existing.exists() && try { BackupFormat.validate(existing); true } catch (e: Exception) { false }
        if (valid) { cleanupTemp(); return null }
        val f = create(db, Kind.DAILY)
        applyRetention(retention)
        return f
    }

    private fun cleanupTemp() {
        for (d in listOf(dailyDir, manualDir, safetyDir)) d.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /**
     * Keeps the newest [keep] VALID daily backups and deletes older ones. Does nothing unless the
     * newest valid daily backup is from today (i.e. a new backup has just succeeded). Invalid files
     * never count towards the number kept.
     */
    fun applyRetention(keep: Int): List<File> {
        require(keep >= 1)
        cleanupTemp()
        val today = clock.today()
        val files = dailyDir.listFiles()?.filter { it.name.matches(Regex("PSY_Backup_\\d{4}-\\d{2}-\\d{2}\\.zip")) } ?: return emptyList()
        val dated = files.mapNotNull { f -> try { LocalDate.parse(f.name.substring(11, 21)) to f } catch (e: Exception) { null } }.sortedByDescending { it.first }
        val valid = dated.filter { (_, f) -> try { BackupFormat.validate(f); true } catch (e: Exception) { false } }
        if (valid.isEmpty() || valid.first().first != today) return emptyList()
        val keepSet = valid.take(keep).map { it.second }.toSet()
        val oldestKept = valid.take(keep).last().first
        val deleted = ArrayList<File>()
        for ((d, f) in dated) {
            if (f in keepSet) continue
            if (d.isBefore(oldestKept) || f !in valid.map { it.second }) { if (f.delete()) deleted.add(f) }
        }
        return deleted
    }

    /** Keeps the newest [keep] manual and safety backups each. */
    fun trimOthers(keep: Int = 20) {
        for (d in listOf(manualDir, safetyDir)) {
            val fs = d.listFiles()?.filter { it.name.endsWith(".zip") }?.sortedByDescending { it.name } ?: continue
            fs.drop(keep).forEach { it.delete() }
        }
    }

    data class Entry(val file: File, val kind: Kind, val info: BackupInfo?, val error: String?)

    fun list(): List<Entry> {
        val out = ArrayList<Entry>()
        for (k in Kind.values()) {
            val fs = dirFor(k).listFiles()?.filter { it.name.endsWith(".zip") } ?: continue
            for (f in fs) out.add(try { Entry(f, k, BackupFormat.validate(f), null) } catch (e: Exception) { Entry(f, k, null, e.message) })
        }
        return out.sortedByDescending { it.file.lastModified() }
    }

    fun status(): BackupStatus {
        if (!statusFile.exists()) return BackupStatus(null, null, null, null, null)
        return try {
            @Suppress("UNCHECKED_CAST") val m = Json.parse(statusFile.readText()) as Map<String, Any?>
            BackupStatus(m["lastSuccessAt"] as String?, m["lastSuccessFile"] as String?, m["lastErrorAt"] as String?, m["lastError"] as String?, m["lastAttemptAt"] as String?)
        } catch (e: Exception) { BackupStatus(null, null, null, "Status file unreadable", null) }
    }

    private fun writeStatus(s: BackupStatus) {
        val tmp = File(root, "backup_status.json.tmp")
        tmp.writeText(Json.write(mapOf("lastSuccessAt" to s.lastSuccessAt, "lastSuccessFile" to s.lastSuccessFile, "lastErrorAt" to s.lastErrorAt,
            "lastError" to s.lastError, "lastAttemptAt" to s.lastAttemptAt)))
        if (!tmp.renameTo(statusFile)) { statusFile.delete(); tmp.renameTo(statusFile) }
    }

    private fun markAttempt(t: LocalDateTime) { val s = status(); writeStatus(s.copy(lastAttemptAt = t.withNano(0).toString())) }
    private fun markSuccess(t: LocalDateTime, f: File) { val s = status(); writeStatus(s.copy(lastSuccessAt = t.withNano(0).toString(), lastSuccessFile = f.name, lastError = null, lastErrorAt = null)) }
    private fun markError(t: LocalDateTime, msg: String) { val s = status(); writeStatus(s.copy(lastErrorAt = t.withNano(0).toString(), lastError = msg)) }
}

/** Opens and closes database files; implemented by the app (Android) and the tests (JDBC). */
interface DbFiles {
    fun open(file: File): SqlDb
    fun close(db: SqlDb)
}

/**
 * Safe restore:
 * 1. validate the backup file (format, version, checksums);
 * 2. create a verified safety backup of the current data;
 * 3. build a NEW database file from the backup and verify it (integrity, links, row counts, balanced journals);
 * 4. swap it in place of the current database file (old file kept until the new one opens and verifies);
 * 5. verify again after reopening.
 * If any step fails, the current data is left untouched.
 */
class RestoreService(private val files: DbFiles, private val dbFile: File, private val backups: BackupManager, private val clock: Clock) {
    data class Outcome(val db: SqlDb, val safetyBackup: File, val info: BackupInfo)

    fun restore(currentDb: SqlDb, backupFile: File, confirmed: Boolean): Outcome {
        if (!confirmed) throw BackupError("Restore was not confirmed")
        val content = FileInputStream(backupFile).use { BackupFormat.read(it, backupFile) }
        val safety = backups.create(currentDb, BackupManager.Kind.SAFETY, "before-restore")
        val tmpFile = File(dbFile.parentFile, dbFile.name + ".restore")
        listOf(tmpFile, File(tmpFile.path + "-journal")).forEach { it.delete() }
        var tmpDb: SqlDb? = null
        try {
            tmpDb = files.open(tmpFile)
            Books(tmpDb, clock) // creates the current schema
            BackupFormat.loadInto(tmpDb, content)
            Books(tmpDb, clock) // runs any newer migrations, calendar corrections, recovery
            BackupFormat.verifyDatabase(tmpDb, content.info.rowCounts)
        } catch (e: Exception) {
            tmpDb?.let { files.close(it) }
            tmpFile.delete()
            throw if (e is BackupError) e else BackupError("Restore failed and your current data was not changed: ${e.message}")
        }
        files.close(tmpDb!!)
        files.close(currentDb)
        val old = File(dbFile.parentFile, dbFile.name + ".pre-restore")
        old.delete()
        if (!dbFile.renameTo(old)) { throw BackupError("Could not replace the database file; current data was not changed") }
        if (!tmpFile.renameTo(dbFile)) { old.renameTo(dbFile); throw BackupError("Could not move the restored database into place; current data was kept") }
        return try {
            val db = files.open(dbFile)
            Books(db, clock)
            BackupFormat.verifyDatabase(db, content.info.rowCounts)
            old.delete()
            Outcome(db, safety, content.info)
        } catch (e: Exception) {
            dbFile.delete(); old.renameTo(dbFile)
            throw BackupError("The restored database could not be verified, so your previous data was put back. ${e.message}")
        }
    }
}
