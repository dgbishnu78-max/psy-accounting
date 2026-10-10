package np.psy.accounting

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import np.psy.core.Books
import np.psy.core.SystemClock
import np.psy.core.backup.BackupManager
import np.psy.core.backup.DbFiles
import np.psy.core.backup.RestoreService
import np.psy.core.db.SqlDb
import np.psy.core.service.SafetyBackup
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors

/** Holds the open company database and backup manager for the whole app. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("${java.time.LocalDateTime.now()}\n$sw")
            } catch (ignored: Exception) {}
            prev?.uncaughtException(t, e)
        }
        BackupScheduler.schedule(this)
    }

    companion object {
        lateinit var instance: App
        private var _db: AndroidSqlDb? = null
        private var _books: Books? = null
        val io = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())

        val dbFile: File get() = instance.getDatabasePath("psy_accounts.db")
        val backupRoot: File get() = File(instance.filesDir, "backups")
        val version: String get() = try { instance.packageManager.getPackageInfo(instance.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

        @Synchronized fun db(): AndroidSqlDb { if (_db == null) _db = AndroidSqlDb.open(dbFile); return _db!! }
        @Synchronized fun books(): Books { if (_books == null) _books = Books(db(), SystemClock); return _books!! }
        val backups: BackupManager get() = BackupManager(backupRoot, SystemClock, version)

        /** Creates a verified safety backup (used before year closing/reopening). */
        fun safetyBackup(reason: String): SafetyBackup = try {
            val f = backups.create(db(), BackupManager.Kind.SAFETY, reason)
            SafetyBackup(f.name, true)
        } catch (e: Exception) { SafetyBackup("", false, e.message) }

        /** Runs the daily backup if it has not happened today (called at start-up and by the scheduled job). */
        fun dailyBackupCheck(): String? = try {
            synchronized(App) {
                val keep = books().settings.backupRetention
                backups.runDailyIfNeeded(db(), keep)?.name.also { backups.trimOthers(20) }
            }
        } catch (e: Exception) { Log.e("PSY", "daily backup failed", e); null }

        /** Restores [file]; on success the app's database is replaced and reopened. */
        @Synchronized fun restore(file: File): RestoreService.Outcome {
            val files = object : DbFiles {
                override fun open(file: File): SqlDb = AndroidSqlDb.open(file)
                override fun close(db: SqlDb) { (db as AndroidSqlDb).raw.close() }
            }
            val current = db()
            try {
                val out = RestoreService(files, dbFile, backups, SystemClock).restore(current, file, confirmed = true)
                _db = out.db as AndroidSqlDb
                _books = null
                books()
                return out
            } catch (e: Exception) {
                // Make sure we are connected to whatever database file is in place now.
                if (!current.raw.isOpen) _db = null
                _books = null
                books()
                throw e
            }
        }

        fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
            io.execute {
                val r = try { Result.success(work()) } catch (e: Throwable) { Result.failure(e) }
                main.post { done(r) }
            }
        }
    }
}

object BackupScheduler {
    private const val JOB_ID = 8301

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, BackupJobService::class.java))
            .setPeriodic(6 * 60 * 60 * 1000L)   // checked every few hours; only one backup per day is made
            .setPersisted(true)
            .build()
        js.schedule(info)
    }
}

/** Background job (best effort; Android may delay it). The app also checks at every start. */
class BackupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            App.dailyBackupCheck()
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
