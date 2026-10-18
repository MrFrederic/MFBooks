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

@Database(
    entities = [RootFolder::class, Book::class, Track::class, Chapter::class, BookProgress::class,
        BookSettings::class, Bookmark::class, HistoryEvent::class, ListeningSession::class],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun userData(): UserDataDao
}
