package app.mfbooks.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class BookState { NEW, STARTED, FINISHED }

@Entity
data class RootFolder(@PrimaryKey val uri: String, val name: String, val addedAt: Long)

@Entity(indices = [Index("rootUri"), Index("state")])
data class Book(
    @PrimaryKey val id: String,        // stable hash of folder document URI
    val rootUri: String,
    val folderUri: String,             // SAF document URI of the book folder
    val relativePath: String,          // "Author/Title"
    val title: String,
    val author: String?,
    val narrator: String?,
    val year: String?,
    val genre: String?,
    val publisher: String?,
    val series: String?,
    val seriesIndex: String?,
    val coverUri: String?,             // file:// of cached cover in filesDir/covers, or null
    val seedColor: Int?,               // dominant cover colour (ARGB)
    val durationMs: Long,
    val fileCount: Int,
    val state: BookState,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val queuePosition: Int?,
    val hidden: Boolean = false,
    val description: String?,
    val fieldSources: String?,         // JSON {"title":"nfo","chapters":"embedded-timing+cue-titles",...}
    val scanSignature: String,
    val serverId: String? = null,      // the book's UUID on MFBooks Server; cloud-only books use it as [id] too
)

@Entity(primaryKeys = ["bookId", "index"])
data class Track(
    val bookId: String, val index: Int, val uri: String, val fileName: String,
    val title: String?, val durationMs: Long, val sizeBytes: Long, val mtime: Long,
    val sha256: String? = null,
) {
    /** Streamed from the server (offloaded or cloud-only book). */
    val isRemote get() = uri.startsWith("http")
}

/** == reference "GlobalChapter". Books without real chapters get one chapter per track. */
@Entity(primaryKeys = ["bookId", "index"])
data class Chapter(
    val bookId: String, val index: Int, val title: String,
    val trackIndex: Int, val startInTrackMs: Long, val durationMs: Long,
    val startInBookMs: Long,
)

@Entity
data class BookProgress(
    @PrimaryKey val bookId: String,
    val trackIndex: Int, val positionInTrackMs: Long, val updatedAt: Long,
)

/** Per-book overrides; null = use default from prefs. */
@Entity
data class BookSettings(
    @PrimaryKey val bookId: String,
    val speed: Float? = null,
    val savedToggleSpeed: Float? = null,
    val pitch: Float? = null,
    val boostMb: Int? = null,
    val eqBandsDb: String? = null,     // "0,0,0,0,0" for 60/230/910/4k/14k Hz
    val balance: Float? = null,
    val mono: Boolean? = null,
    val skipSilence: Boolean? = null,
    val skipStartMs: Long? = null,
    val skipEndMs: Long? = null,
    val repeatMode: String? = null,
    val updatedAt: Long = 0,
)

@Entity(indices = [Index("bookId"), Index("uuid", unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: String,
    val trackIndex: Int, val positionMs: Long, val title: String, val note: String?, val createdAt: Long,
    val uuid: String = UUID.randomUUID().toString(), val updatedAt: Long = createdAt,
)

@Entity(indices = [Index("bookId"), Index("uuid", unique = true)])
data class HistoryEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: String,
    val action: String, // PREV,NEXT,START,PAUSE,BACK,FWD,SEEK,SLEEP
    val trackIndex: Int, val positionMs: Long, val at: Long,
    val uuid: String = UUID.randomUUID().toString(),
)

/** Denormalised so stats survive book deletion. */
@Entity(indices = [Index("uuid", unique = true)])
data class ListeningSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String, val bookTitle: String, val bookPath: String,
    val startedAt: Long, val listenedMs: Long,
    val uuid: String = UUID.randomUUID().toString(),
)

/**
 * The sync queue: SQL triggers (see [SYNC_TRIGGERS]) add a row for every write the server should hear about, so no
 * call site has to remember. [tbl] is the sync kind, [key] the local book id or the row's uuid.
 */
@Entity
data class PendingChange(@PrimaryKey(autoGenerate = true) val id: Long = 0, val tbl: String, val key: String, val at: Long)

/** Last server seq seen per synced record ("progress:<serverId>"), sent back as `base` for conflict detection. */
@Entity
data class SyncSeen(@PrimaryKey val key: String, val seq: Long)
