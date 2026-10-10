package np.psy.core.service

import np.psy.core.Books
import np.psy.core.db.UserError
import np.psy.core.db.list
import np.psy.core.db.string

class SettingsService(private val b: Books) {
    fun get(key: String, default: String = ""): String = b.db.string("SELECT value FROM settings WHERE key = ?", key) ?: default
    fun int(key: String, default: Int): Int = get(key).toIntOrNull() ?: default
    fun bool(key: String, default: Boolean = false): Boolean = get(key).let { if (it.isEmpty()) default else it == "1" }

    fun set(key: String, value: String, audit: Boolean = true) {
        b.db.transaction {
            val old = get(key)
            if (old == value) return@transaction
            b.db.execute("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", key, value)
            if (audit && !key.startsWith("counter.")) {
                val shown = if (key == "logo_jpeg_base64") "(logo image changed)" else "'$old' -> '$value'"
                b.audit.log("SETTING", "settings", null, "Changed $key", shown)
            }
        }
    }

    fun all(): Map<String, String> = b.db.list("SELECT key, value FROM settings ORDER BY key") { it.string("key") to (it.stringOrNull("value") ?: "") }.toMap()

    val companyName get() = get("company_name")
    val companyAddress get() = get("company_address")
    val companyPan get() = get("company_pan")
    val printAdDates get() = bool("print_ad_dates", true)
    val backupRetention get() = int("backup_retention_days", 7).coerceAtLeast(1)

    fun setBackupRetention(n: Int) {
        if (n < 1 || n > 365) throw UserError("Keep between 1 and 365 backups")
        set("backup_retention_days", n.toString())
    }

    /** Validates and saves a document number format such as "SI-{FY}-{N4}". */
    fun setNumberFormat(type: String, format: String) {
        if (!format.contains("{N")) throw UserError("The number format must contain {N} (or {N4}) for the running number")
        if (format.length > 40) throw UserError("The number format is too long")
        set("number_format.$type", format)
    }

    companion object {
        val VAT_STATUS = listOf("NOT_SET" to "Not set", "VAT_REGISTERED" to "VAT registered", "PAN_ONLY" to "PAN only (not VAT registered)")
    }
}
