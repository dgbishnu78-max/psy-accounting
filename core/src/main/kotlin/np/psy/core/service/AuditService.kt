package np.psy.core.service

import np.psy.core.Books
import np.psy.core.db.list

data class AuditEntry(val id: Long, val at: String, val action: String, val entity: String, val entityId: Long?,
                      val summary: String, val details: String, val reason: String)

class AuditService(private val b: Books) {
    fun log(action: String, entity: String, entityId: Long?, summary: String, details: String = "", reason: String = "") {
        b.db.insert("INSERT INTO audit_log(at, action, entity, entity_id, summary, details, reason) VALUES (?,?,?,?,?,?,?)",
            b.nowIso(), action, entity, entityId, summary, details, reason)
    }

    fun recent(limit: Int = 500, entity: String? = null, entityId: Long? = null): List<AuditEntry> {
        val where = StringBuilder("1=1"); val args = ArrayList<Any?>()
        if (entity != null) { where.append(" AND entity = ?"); args.add(entity) }
        if (entityId != null) { where.append(" AND entity_id = ?"); args.add(entityId) }
        args.add(limit)
        return b.db.query("SELECT * FROM audit_log WHERE $where ORDER BY id DESC LIMIT ?", args.toTypedArray()) { r ->
            AuditEntry(r.long("id"), r.string("at"), r.string("action"), r.string("entity"), r.longOrNull("entity_id"),
                r.string("summary"), r.string("details"), r.string("reason"))
        }
    }
}
