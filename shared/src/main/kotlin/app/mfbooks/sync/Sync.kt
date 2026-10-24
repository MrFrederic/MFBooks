package app.mfbooks.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/** Wire format of `/api/v1/sync` (see server/API.md). `data` stays a JSON object so either side can push partial records. */
@Serializable
data class Change(
    val kind: String,
    val id: String,
    val bookId: String? = null,
    val updatedAt: Long,
    val base: Long = 0,
    val deleted: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
    val seq: Long = 0,
    val deviceId: String? = null,
    val conflict: Conflict? = null,
)

@Serializable
data class Conflict(val deviceId: String, val deviceName: String, val updatedAt: Long, val data: JsonObject)

@Serializable
data class SyncRequest(val since: Long, val now: Long, val changes: List<Change> = emptyList())

@Serializable
data class SyncResponse(val seq: Long, val now: Long, val changes: List<Change>)

object Kind {
    const val BOOK = "book"
    const val PROGRESS = "progress"
    const val SETTINGS = "book_settings"
    const val BOOKMARK = "bookmark"
    const val HISTORY = "history"
    const val SESSION = "session"
    const val APP = "app_settings"
    val ALL = setOf(BOOK, PROGRESS, SETTINGS, BOOKMARK, HISTORY, SESSION, APP)
}

// Payloads, field for field with the app's Room entities (minus device-only fields).

@Serializable
data class TrackData(val index: Int, val fileName: String, val title: String? = null, val durationMs: Long, val sizeBytes: Long, val sha256: String? = null)

@Serializable
data class ChapterData(val index: Int, val title: String, val trackIndex: Int, val startInTrackMs: Long, val durationMs: Long, val startInBookMs: Long)

@Serializable
data class BookData(
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val publisher: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val description: String? = null,
    val durationMs: Long = 0,
    val seedColor: Int? = null,
    val state: String = "NEW",
    val addedAt: Long = 0,
    val lastPlayedAt: Long? = null,
    val queuePosition: Int? = null,
    val hidden: Boolean = false,
    val tracks: List<TrackData> = emptyList(),
    val chapters: List<ChapterData> = emptyList(),
)

@Serializable
data class ProgressData(val trackIndex: Int, val positionInTrackMs: Long)

@Serializable
data class SettingsData(
    val speed: Float? = null, val savedToggleSpeed: Float? = null, val pitch: Float? = null, val boostMb: Int? = null,
    val eqBandsDb: String? = null, val balance: Float? = null, val mono: Boolean? = null, val skipSilence: Boolean? = null,
    val skipStartMs: Long? = null, val skipEndMs: Long? = null, val repeatMode: String? = null,
)

@Serializable
data class BookmarkData(val trackIndex: Int, val positionMs: Long, val title: String, val note: String? = null, val createdAt: Long)

@Serializable
data class HistoryData(val action: String, val trackIndex: Int, val positionMs: Long, val at: Long)

@Serializable
data class SessionData(val bookTitle: String, val bookPath: String = "", val startedAt: Long, val listenedMs: Long)

val SyncJson = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }

inline fun <reified T> JsonObject.decode(): T = SyncJson.decodeFromJsonElement(this)
inline fun <reified T> T.toData(): JsonObject = SyncJson.encodeToJsonElement(this).jsonObject

/** Merge rules, the same on the server and in the app. */
object Merge {
    /**
     * Does [incoming] replace [existing]? Last writer wins; history events never change once written. Sessions merge as a
     * union by id too, but a session grows while it lasts, so its own device's later writes win.
     */
    fun wins(incoming: Change, existing: Change?): Boolean = when {
        existing == null -> true
        incoming.kind == Kind.HISTORY -> false
        else -> incoming.updatedAt > existing.updatedAt
    }

    /** Shallow merge, so a client can push only the fields it changed. */
    fun apply(existing: JsonObject?, incoming: JsonObject) = if (existing == null) incoming else JsonObject(existing + incoming)

    /** Two devices moved the same book offline: [incoming] was based on an older version from someone else, and is far away. */
    fun isConflict(incoming: Change, existing: Change?): Boolean {
        if (incoming.kind != Kind.PROGRESS || existing == null || existing.deviceId == incoming.deviceId || incoming.base >= existing.seq) return false
        return abs(position(incoming.data) - position(existing.data)) > 60_000 || track(incoming.data) != track(existing.data)
    }

    private fun track(d: JsonObject) = (d["trackIndex"] as? JsonPrimitive)?.longOrNull
    private fun position(d: JsonObject) = (d["positionInTrackMs"] as? JsonPrimitive)?.longOrNull ?: 0
}

/** Matches local books to server records on a new phone, before the server ids are known. */
fun sameBook(a: BookData, b: BookData) =
    a.title.equals(b.title, true) && a.author.orEmpty().equals(b.author.orEmpty(), true) &&
        a.tracks.size == b.tracks.size && abs(a.durationMs - b.durationMs) <= 2_000
