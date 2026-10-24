package app.mfbooks.server

import app.mfbooks.sync.Change
import app.mfbooks.sync.Conflict
import app.mfbooks.sync.Kind
import app.mfbooks.sync.Merge
import app.mfbooks.sync.SyncJson
import app.mfbooks.sync.SyncRequest
import app.mfbooks.sync.SyncResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet

/** The synced records: one row per (kind, id); `seq` is the change log. */
class Records(private val db: Db) {
    private fun row(rs: ResultSet) = Change(
        kind = rs.getString("kind"), id = rs.getString("id"), bookId = rs.getString("book_id"),
        updatedAt = rs.getLong("updated_at"), deleted = rs.getInt("deleted") != 0,
        data = SyncJson.parseToJsonElement(rs.getString("data")).jsonObject, seq = rs.getLong("seq"), deviceId = rs.getString("device_id"),
        conflict = rs.getString("conflict")?.let { SyncJson.decodeFromString<Conflict>(it) },
    )

    fun get(kind: String, id: String) = db.query("SELECT * FROM records WHERE kind = ? AND id = ?", kind, id, row = ::row).firstOrNull()
    fun all(kind: String) = db.query("SELECT * FROM records WHERE kind = ? AND deleted = 0", kind, row = ::row)
    fun ofBook(kind: String, bookId: String) = db.query("SELECT * FROM records WHERE kind = ? AND book_id = ? AND deleted = 0", kind, bookId, row = ::row)
    fun since(seq: Long) = db.query("SELECT * FROM records WHERE seq > ? ORDER BY seq", seq, row = ::row)
    fun seq() = db.long("SELECT COALESCE(MAX(seq), 0) FROM records")

    /** Applies the merge rules; true if anything was stored. [c].deviceId is the writer. */
    fun put(c: Change): Boolean = db.tx {
        val old = get(c.kind, c.id)
        val conflict = Merge.isConflict(c, old)
        val wins = Merge.wins(c, old)
        if (!wins && !conflict) return@tx false
        fun nameOf(id: String?) = db.query("SELECT name FROM devices WHERE id = ?", id) { it.getString(1) }.firstOrNull() ?: "Another device"
        val stored = when {
            !wins -> old!!.copy(conflict = Conflict(c.deviceId.orEmpty(), nameOf(c.deviceId), c.updatedAt, c.data))
            else -> c.copy(
                bookId = c.bookId ?: old?.bookId ?: c.id.takeIf { c.kind == Kind.PROGRESS || c.kind == Kind.SETTINGS || c.kind == Kind.BOOK },
                data = if (old == null || old.deleted) c.data else Merge.apply(old.data, c.data),
                conflict = if (conflict) Conflict(old!!.deviceId.orEmpty(), nameOf(old.deviceId), old.updatedAt, old.data) else null,
            )
        }
        db.update(
            "INSERT OR REPLACE INTO records(kind, id, book_id, data, updated_at, device_id, seq, deleted, conflict) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            stored.kind, stored.id, stored.bookId, stored.data.toString(), stored.updatedAt, stored.deviceId, seq() + 1,
            if (stored.deleted) 1 else 0, stored.conflict?.let { SyncJson.encodeToString(Conflict.serializer(), it) },
        )
        true
    }

    /** A record written by the server itself (imports, deletions): always wins. */
    fun putServer(kind: String, id: String, data: JsonObject, bookId: String? = null, deleted: Boolean = false) =
        put(Change(kind, id, bookId, updatedAt = maxOf(System.currentTimeMillis(), (get(kind, id)?.updatedAt ?: 0) + 1), deleted = deleted, data = data, deviceId = "server"))
}

fun Route.syncRoutes(app: App) {
    get("/sync") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        call.respond(SyncResponse(app.records.seq(), System.currentTimeMillis(), app.records.since(since)))
    }
    post("/sync") {
        val req = call.receive<SyncRequest>()
        val now = System.currentTimeMillis()
        // A phone with a wrong clock must not win last-writer-wins: shift its times onto the server clock.
        val offset = now - req.now
        app.db.update("UPDATE devices SET clock_offset = ? WHERE id = ?", offset, call.device.id)
        var changed = false
        for (c in req.changes) {
            if (c.kind !in Kind.ALL) fail(HttpStatusCode.BadRequest, "Unknown kind ${c.kind}")
            changed = app.records.put(c.copy(updatedAt = c.updatedAt + offset, deviceId = call.device.id, seq = 0, conflict = null)) || changed
        }
        val seq = app.records.seq()
        if (changed) app.live.changed(seq)
        call.respond(SyncResponse(seq, now, app.records.since(req.since)))
    }
}
