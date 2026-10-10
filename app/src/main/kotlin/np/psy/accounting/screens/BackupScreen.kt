package np.psy.accounting.screens

import android.app.Activity
import android.content.Intent
import android.view.View
import np.psy.accounting.App
import np.psy.accounting.Exporter
import np.psy.accounting.MainActivity
import np.psy.accounting.Screen
import np.psy.accounting.ui.*
import np.psy.core.backup.BackupFormat
import np.psy.core.backup.BackupInfo
import np.psy.core.backup.BackupManager
import java.io.File

class BackupScreen(act: MainActivity) : Screen(act) {
    override val title = "Backup and restore"

    companion object {
        fun backupNow(act: MainActivity, after: () -> Unit) {
            act.working("Making backup...", { synchronized(App) { App.backups.create(App.db(), BackupManager.Kind.MANUAL, "manual") } }) { f ->
                act.confirm("Backup made", "${f.name} was created and verified.\n\nIt is stored inside the app. To protect against losing or resetting the phone, also save a copy to another place (USB, SD card, Google Drive, email...).",
                    yes = "Save a copy elsewhere", no = "Close") { Exporter.saveFileAs(act, f, "application/zip") }
                after()
            }
        }

        fun describe(i: BackupInfo) = "Created ${i.createdAtBs} BS (${i.createdAt.replace('T', ' ')})\n" +
            "Company: ${i.company}\nApp version ${i.appVersion}, data version ${i.schemaVersion}\n" +
            "${i.vouchers} documents, ${i.journals} accounting entries, ${i.rowCounts["product"] ?: 0} products"
    }

    override fun build(): View {
        val b = books
        val box = ctx.vbox(12)
        val st = App.backups.status()
        box.card {
            text("Automatic daily backup", 15f, true)
            note("The app makes one backup per day automatically (when the phone allows background work, and also every time you open the app). It keeps the newest ${b.settings.backupRetention} daily backup(s) and never deletes the only good one.")
            kv("Last successful backup", st.lastSuccessAt?.replace('T', ' ') ?: "never")
            st.lastSuccessFile?.let { note(it) }
            if (st.lastError != null) warning("Last failure (${st.lastErrorAt?.replace('T', ' ')}): ${st.lastError}")
            button("Number of daily backups to keep: ${b.settings.backupRetention}", false) {
                ctx.prompt("Daily backups to keep", "Between 1 and 365. Older daily backups are deleted automatically.", "7", b.settings.backupRetention.toString(), INT) { v ->
                    b.settings.setBackupRetention(v.toIntOrNull() ?: 0); App.backups.applyRetention(b.settings.backupRetention); refresh()
                }
            }
        }
        box.button("Back up now") { backupNow(act) { refresh() } }
        box.button("Restore from a file (USB, SD card, Downloads...)", false) { pickFile() }
        box.warning("Backups inside the app are deleted if the app is uninstalled or the phone is reset. Save copies elsewhere regularly.")

        box.heading("Backups on this phone")
        val list = App.backups.list()
        if (list.isEmpty()) box.note("No backups yet.")
        for (e in list) box.card {
            val kind = when (e.kind) { BackupManager.Kind.DAILY -> "Daily"; BackupManager.Kind.MANUAL -> "Manual"; BackupManager.Kind.SAFETY -> "Safety (before closing/restore)" }
            text(e.file.name, 14f, true)
            note("$kind  |  ${e.file.length() / 1024} KB")
            if (e.info != null) note(describe(e.info!!)) else warning("Damaged or unreadable: ${e.error}")
            setOnClickListener {
                val opts = ArrayList<Pair<String, () -> Unit>>()
                opts.add("Save a copy elsewhere" to { Exporter.saveFileAs(act, e.file, "application/zip") })
                opts.add("Share (email, Drive...)" to { Exporter.share(act, e.file.name, "application/zip", e.file.readBytes()) })
                if (e.info != null) opts.add("Restore this backup" to { confirmRestore(e.file, e.info!!) })
                ctx.pick(e.file.name, opts, { it.first }) { it.second() }
            }
        }
        return ctx.scroll(box)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        act.startForResult(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@startForResult
            act.working("Checking backup file...", {
                val dir = File(act.filesDir, "import").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "imported_backup.zip")
                act.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                f to BackupFormat.validate(f)
            }) { (f, info) -> confirmRestore(f, info) }
        }
    }

    private fun confirmRestore(f: File, info: BackupInfo) {
        val msg = "This will REPLACE all data in the app with this backup:\n\n${describe(info)}\n\n" +
            "Before restoring, the app makes a safety backup of your current data, so you can go back if needed.\n\nThe backup is checked completely before anything is replaced. If any check fails, your current data is kept."
        ctx.confirm("Restore this backup?", msg, yes = "Restore", no = "Cancel") {
            ctx.confirm("Are you sure?", "All entries made after ${info.createdAtBs} BS will no longer be in the app (they remain in the safety backup).", yes = "Yes, restore", no = "Cancel") {
                act.working("Restoring... do not close the app", { App.restore(f) }) { out ->
                    ctx.info("Restore complete", "Data restored and verified.\n\nYour previous data was saved as ${out.safetyBackup.name}.") { act.home() }
                }
            }
        }
    }
}
