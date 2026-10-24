package app.mfbooks.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun stateToString(s: BookState) = s.name
    @TypeConverter fun stringToState(s: String) = BookState.valueOf(s)
}

/** Book plus its saved position, for lists and AA badges. */
data class BookWithProgress(
    @androidx.room.Embedded val book: Book,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "bookId") val progress: BookProgress?,
)

/** Absolute position inside the book (ms), for progress bars in lists. */
data class BookPosition(val bookId: String, val positionMs: Long)

@Dao
interface LibraryDao {
    @Query("""SELECT p.bookId AS bookId, p.positionInTrackMs + COALESCE(
              (SELECT SUM(t.durationMs) FROM Track t WHERE t.bookId = p.bookId AND t.`index` < p.trackIndex), 0) AS positionMs
              FROM BookProgress p""")
    fun positions(): Flow<List<BookPosition>>

    @Query("SELECT * FROM RootFolder ORDER BY name") fun roots(): Flow<List<RootFolder>>
    @Query("SELECT * FROM RootFolder") suspend fun rootsNow(): List<RootFolder>
    @Upsert suspend fun upsertRoot(r: RootFolder)
    @Delete suspend fun deleteRoot(r: RootFolder)

    @Transaction @Query("SELECT * FROM Book WHERE hidden = 0 ORDER BY title COLLATE NOCASE")
    fun books(): Flow<List<BookWithProgress>>
    @Transaction @Query("SELECT * FROM Book ORDER BY title COLLATE NOCASE")
    fun allBooksIncludingHidden(): Flow<List<BookWithProgress>>
    @Query("SELECT * FROM Book WHERE hidden = 0 ORDER BY title COLLATE NOCASE") suspend fun booksNow(): List<Book>
    @Query("SELECT * FROM Book WHERE rootUri = :root") suspend fun booksInRoot(root: String): List<Book>
    @Query("SELECT * FROM Book WHERE id = :id") suspend fun book(id: String): Book?
    @Query("SELECT * FROM Book WHERE id = :id") fun bookFlow(id: String): Flow<Book?>
    @Transaction @Query("SELECT * FROM Book WHERE id = :id") fun bookWithProgressFlow(id: String): Flow<BookWithProgress?>
    @Query("SELECT * FROM Book WHERE hidden = 0 AND state = :state ORDER BY lastPlayedAt DESC, title COLLATE NOCASE")
    suspend fun booksByState(state: BookState): List<Book>
    @Query("SELECT * FROM Book WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayed(): Book?
    @Query("SELECT * FROM Book WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 1")
    fun lastPlayedFlow(): Flow<Book?>
    @Query("""SELECT * FROM Book WHERE hidden = 0 AND (title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%'
              OR narrator LIKE '%' || :q || '%') ORDER BY lastPlayedAt DESC, title COLLATE NOCASE""")
    suspend fun search(q: String): List<Book>
    @Upsert suspend fun upsertBook(b: Book)
    @Query("DELETE FROM Book WHERE id = :id") suspend fun deleteBook(id: String)
    @Query("UPDATE Book SET state = :state WHERE id = :id") suspend fun setState(id: String, state: BookState)
    @Query("UPDATE Book SET lastPlayedAt = :at WHERE id = :id") suspend fun setLastPlayed(id: String, at: Long)
    @Query("UPDATE Book SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: String, hidden: Boolean)

    @Query("SELECT * FROM Track WHERE bookId = :bookId ORDER BY `index`") suspend fun tracks(bookId: String): List<Track>
    @Query("SELECT * FROM Track WHERE bookId = :bookId ORDER BY `index`") fun tracksFlow(bookId: String): Flow<List<Track>>
    @Query("SELECT * FROM Chapter WHERE bookId = :bookId ORDER BY `index`") suspend fun chapters(bookId: String): List<Chapter>
    @Query("SELECT * FROM Chapter WHERE bookId = :bookId ORDER BY `index`") fun chaptersFlow(bookId: String): Flow<List<Chapter>>

    @Query("DELETE FROM Track WHERE bookId = :bookId") suspend fun deleteTracks(bookId: String)
    @Query("DELETE FROM Chapter WHERE bookId = :bookId") suspend fun deleteChapters(bookId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(t: List<Track>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(c: List<Chapter>)

    /** Replace a book with its freshly scanned tracks and chapters in one transaction. */
    @Transaction
    suspend fun replaceBook(b: Book, tracks: List<Track>, chapters: List<Chapter>) {
        upsertBook(b); deleteTracks(b.id); deleteChapters(b.id); insertTracks(tracks); insertChapters(chapters)
    }

    /** ponytail: stats are denormalised, so a vanished book is just deleted. */
    @Transaction
    suspend fun removeBook(id: String) {
        deleteTracks(id); deleteChapters(id); deleteProgress(id); deleteBook(id)
    }

    @Query("SELECT * FROM BookProgress WHERE bookId = :bookId") suspend fun progress(bookId: String): BookProgress?
    @Upsert suspend fun upsertProgress(p: BookProgress)
    @Query("DELETE FROM BookProgress WHERE bookId = :bookId") suspend fun deleteProgress(bookId: String)

    @Query("SELECT * FROM BookSettings WHERE bookId = :bookId") suspend fun settings(bookId: String): BookSettings?
    @Query("SELECT * FROM BookSettings WHERE bookId = :bookId") fun settingsFlow(bookId: String): Flow<BookSettings?>
    @Upsert suspend fun upsertSettings(s: BookSettings)

    /** SABP import: keep the earliest known "added" date. */
    @Query("UPDATE Book SET addedAt = :at WHERE id = :id AND addedAt > :at") suspend fun setAddedAtIfEarlier(id: String, at: Long)
}

@Dao
interface UserDataDao {
    @Query("SELECT * FROM Bookmark WHERE bookId = :bookId ORDER BY trackIndex, positionMs") fun bookmarks(bookId: String): Flow<List<Bookmark>>
    @Query("SELECT * FROM Bookmark WHERE bookId = :bookId ORDER BY trackIndex, positionMs") suspend fun bookmarksNow(bookId: String): List<Bookmark>
    @Insert suspend fun insertBookmark(b: Bookmark): Long
    @Delete suspend fun deleteBookmark(b: Bookmark)

    @Query("SELECT * FROM HistoryEvent WHERE bookId = :bookId ORDER BY at DESC LIMIT 200") fun history(bookId: String): Flow<List<HistoryEvent>>
    @Insert suspend fun insertHistory(e: HistoryEvent)

    @Insert suspend fun insertSession(s: ListeningSession): Long
    @Query("UPDATE ListeningSession SET listenedMs = :ms WHERE id = :id") suspend fun updateSession(id: Long, ms: Long)
    @Query("SELECT * FROM ListeningSession WHERE startedAt >= :from ORDER BY startedAt") fun sessionsSince(from: Long): Flow<List<ListeningSession>>
    /** SABP statistics import dedupe (path + 1st of month). */
    @Query("SELECT * FROM ListeningSession WHERE bookPath = :path AND startedAt = :at LIMIT 1") suspend fun sessionAt(path: String, at: Long): ListeningSession?
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM PendingChange ORDER BY id") suspend fun pending(): List<PendingChange>
    @Query("SELECT COALESCE(MAX(id), 0) FROM PendingChange") suspend fun maxPending(): Long
    @Query("DELETE FROM PendingChange WHERE id <= :id") suspend fun deletePendingUpTo(id: Long)
    @Query("DELETE FROM PendingChange WHERE id > :id") suspend fun deletePendingAbove(id: Long)
    @Query("SELECT DISTINCT tbl || ':' || `key` FROM PendingChange WHERE id > :id") suspend fun pendingKeysAbove(id: Long): List<String>
    @Insert suspend fun addPending(p: PendingChange)
    @Query("DELETE FROM PendingChange") suspend fun clearPending()

    @Query("SELECT seq FROM SyncSeen WHERE `key` = :key") suspend fun seen(key: String): Long?
    @Upsert suspend fun setSeen(s: SyncSeen)
    @Query("DELETE FROM SyncSeen") suspend fun clearSeen()

    @Query("SELECT * FROM Book WHERE serverId = :sid LIMIT 1") suspend fun bookByServerId(sid: String): Book?
    @Query("SELECT * FROM Book WHERE serverId IS NULL") suspend fun booksWithoutServerId(): List<Book>
    @Query("SELECT * FROM Book WHERE serverId IS NOT NULL") suspend fun syncedBooks(): List<Book>
    @Query("UPDATE Book SET serverId = :sid WHERE id = :id") suspend fun setServerId(id: String, sid: String?)
    @Query("UPDATE Book SET state = :state, lastPlayedAt = :lastPlayedAt, hidden = :hidden, queuePosition = :queuePosition WHERE id = :id")
    suspend fun applyBookState(id: String, state: BookState, lastPlayedAt: Long?, hidden: Boolean, queuePosition: Int?)

    @Query("SELECT * FROM Bookmark WHERE uuid = :uuid") suspend fun bookmark(uuid: String): Bookmark?
    @Upsert suspend fun upsertBookmark(b: Bookmark)
    @Query("DELETE FROM Bookmark WHERE uuid = :uuid") suspend fun deleteBookmark(uuid: String)
    @Query("SELECT * FROM HistoryEvent WHERE uuid = :uuid") suspend fun history(uuid: String): HistoryEvent?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertHistory(e: HistoryEvent)
    @Query("SELECT * FROM ListeningSession WHERE uuid = :uuid") suspend fun session(uuid: String): ListeningSession?
    @Upsert suspend fun upsertSession(s: ListeningSession)

    /** Books with at least one streamed track (offloaded or cloud-only). */
    @Query("SELECT DISTINCT bookId FROM Track WHERE uri LIKE 'http%'") fun remoteBookIds(): Flow<List<String>>
    @Query("UPDATE Track SET uri = :uri, fileName = :fileName, sizeBytes = :size WHERE bookId = :bookId AND `index` = :index")
    suspend fun setTrack(bookId: String, index: Int, uri: String, fileName: String, size: Long)
    @Query("UPDATE Track SET sha256 = :sha WHERE bookId = :bookId AND `index` = :index") suspend fun setSha(bookId: String, index: Int, sha: String)

    @Query("UPDATE Book SET id = :new, rootUri = :root, folderUri = :folder, relativePath = :rel, scanSignature = '' WHERE id = :old")
    suspend fun moveBookRow(old: String, new: String, root: String, folder: String, rel: String)
    @Query("UPDATE Track SET bookId = :new WHERE bookId = :old") suspend fun moveTracks(old: String, new: String)
    @Query("UPDATE Chapter SET bookId = :new WHERE bookId = :old") suspend fun moveChapters(old: String, new: String)
    @Query("UPDATE BookProgress SET bookId = :new WHERE bookId = :old") suspend fun moveProgress(old: String, new: String)
    @Query("UPDATE BookSettings SET bookId = :new WHERE bookId = :old") suspend fun moveSettings(old: String, new: String)
    @Query("UPDATE Bookmark SET bookId = :new WHERE bookId = :old") suspend fun moveBookmarks(old: String, new: String)
    @Query("UPDATE HistoryEvent SET bookId = :new WHERE bookId = :old") suspend fun moveHistory(old: String, new: String)
    @Query("UPDATE ListeningSession SET bookId = :new WHERE bookId = :old") suspend fun moveSessions(old: String, new: String)

    /** A downloaded cloud-only book becomes a folder book: give it the id the scanner computes for that folder. */
    @Transaction
    suspend fun rekey(old: String, new: String, root: String, folder: String, rel: String) {
        moveBookRow(old, new, root, folder, rel); moveTracks(old, new); moveChapters(old, new); moveProgress(old, new)
        moveSettings(old, new); moveBookmarks(old, new); moveHistory(old, new); moveSessions(old, new)
    }
}

private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
private const val ON = "EXISTS (SELECT 1 FROM SyncSeen WHERE `key` = 'enabled')"
private fun trigger(name: String, event: String, kind: String, key: String, extra: String = "") =
    "CREATE TRIGGER IF NOT EXISTS sync_$name AFTER $event WHEN $ON $extra BEGIN INSERT INTO PendingChange(tbl, `key`, at) VALUES ('$kind', $key, $NOW); END"

/** Every write the server must hear about queues a [PendingChange], but only while a server is connected. */
val SYNC_TRIGGERS = listOf(
    trigger("progress_i", "INSERT ON BookProgress", "progress", "NEW.bookId"),
    trigger("progress_u", "UPDATE ON BookProgress", "progress", "NEW.bookId"),
    trigger("settings_i", "INSERT ON BookSettings", "book_settings", "NEW.bookId"),
    trigger("settings_u", "UPDATE ON BookSettings", "book_settings", "NEW.bookId"),
    trigger("book_i", "INSERT ON Book", "book", "NEW.id"),
    trigger("book_u", "UPDATE OF state, lastPlayedAt, hidden, queuePosition ON Book", "book", "NEW.id",
        "AND (OLD.state IS NOT NEW.state OR OLD.lastPlayedAt IS NOT NEW.lastPlayedAt OR OLD.hidden IS NOT NEW.hidden OR OLD.queuePosition IS NOT NEW.queuePosition)"),
    trigger("bookmark_i", "INSERT ON Bookmark", "bookmark", "NEW.uuid"),
    trigger("bookmark_d", "DELETE ON Bookmark", "bookmark", "OLD.uuid"),
    trigger("history_i", "INSERT ON HistoryEvent", "history", "NEW.uuid"),
    trigger("session_i", "INSERT ON ListeningSession", "session", "NEW.uuid"),
    trigger("session_u", "UPDATE ON ListeningSession", "session", "NEW.uuid"),
)

/** v2: MFBooks Server sync (server ids, uuids, the change queue). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "ALTER TABLE Book ADD COLUMN serverId TEXT",
            "ALTER TABLE Track ADD COLUMN sha256 TEXT",
            "ALTER TABLE BookSettings ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE Bookmark ADD COLUMN uuid TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE Bookmark ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "UPDATE Bookmark SET uuid = lower(hex(randomblob(16))), updatedAt = createdAt",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_Bookmark_uuid ON Bookmark(uuid)",
            "ALTER TABLE HistoryEvent ADD COLUMN uuid TEXT NOT NULL DEFAULT ''",
            "UPDATE HistoryEvent SET uuid = lower(hex(randomblob(16)))",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_HistoryEvent_uuid ON HistoryEvent(uuid)",
            "ALTER TABLE ListeningSession ADD COLUMN uuid TEXT NOT NULL DEFAULT ''",
            "UPDATE ListeningSession SET uuid = lower(hex(randomblob(16)))",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_ListeningSession_uuid ON ListeningSession(uuid)",
            "CREATE TABLE IF NOT EXISTS PendingChange (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tbl TEXT NOT NULL, `key` TEXT NOT NULL, at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS SyncSeen (`key` TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(`key`))",
        ).forEach(db::execSQL)
    }
}

/** Triggers aren't part of Room's schema: (re)create them on every open, which also covers fresh installs. */
val SYNC_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) = SYNC_TRIGGERS.forEach(db::execSQL)
}

@Database(
    entities = [RootFolder::class, Book::class, Track::class, Chapter::class, BookProgress::class,
        BookSettings::class, Bookmark::class, HistoryEvent::class, ListeningSession::class, PendingChange::class, SyncSeen::class],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun userData(): UserDataDao
    abstract fun sync(): SyncDao
}
