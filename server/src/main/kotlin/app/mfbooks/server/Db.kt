package app.mfbooks.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

private val SCHEMA = """
CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, clock_offset INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS records(kind TEXT NOT NULL, id TEXT NOT NULL, book_id TEXT, data TEXT NOT NULL, updated_at INTEGER NOT NULL,
    device_id TEXT, seq INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, conflict TEXT, PRIMARY KEY(kind, id));
CREATE INDEX IF NOT EXISTS records_seq ON records(seq);
CREATE INDEX IF NOT EXISTS records_book ON records(book_id);
CREATE TABLE IF NOT EXISTS files(book_id TEXT NOT NULL, idx INTEGER NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT,
    received INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, format TEXT NOT NULL, original INTEGER NOT NULL DEFAULT 1,
    verified INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(book_id, idx));
CREATE TABLE IF NOT EXISTS cloud(book_id TEXT PRIMARY KEY, conversion TEXT NOT NULL, progress REAL NOT NULL DEFAULT 0, error TEXT);
CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL, offset INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL, book_id TEXT, error TEXT, created_at INTEGER NOT NULL);
"""

/** ponytail: one SQLite connection behind a lock; plenty for one owner. A pool if it ever has to serve many. */
class Db(path: String) {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA busy_timeout=5000")
            SCHEMA.split(';').filter { it.isNotBlank() }.forEach(s::execute)
        }
    }

    private fun prep(sql: String, args: Array<out Any?>) = c.prepareStatement(sql).also { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) } }

    @Synchronized fun update(sql: String, vararg args: Any?): Int = prep(sql, args).use { it.executeUpdate() }

    @Synchronized fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> =
        prep(sql, args).use { s -> s.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } } }

    fun long(sql: String, vararg args: Any?): Long = query(sql, *args) { it.getLong(1) }.firstOrNull() ?: 0

    @Synchronized fun <T> tx(block: () -> T): T {
        c.autoCommit = false
        try { return block().also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    fun get(k: String): String? = query("SELECT v FROM kv WHERE k = ?", k) { it.getString(1) }.firstOrNull()
    fun set(k: String, v: String?) { if (v == null) update("DELETE FROM kv WHERE k = ?", k) else update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", k, v) }

    /** A consistent copy for export, taken while the server runs. */
    @Synchronized fun snapshot(to: String) { c.createStatement().use { it.execute("VACUUM INTO '${to.replace("'", "''")}'") } }
}
