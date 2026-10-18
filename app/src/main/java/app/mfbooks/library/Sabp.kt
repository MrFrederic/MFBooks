package app.mfbooks.library

import android.content.Context
import app.mfbooks.data.Bookmark
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookSettings
import app.mfbooks.data.BookState
import app.mfbooks.data.LibraryDao
import app.mfbooks.data.UserDataDao
import app.mfbooks.data.ListeningSession
import java.util.Calendar

/*
 * Smart AudioBook Player compatibility (import only; SABP's files are never written or deleted):
 *  - <book>/position.sabp.dat     Java-serialized BookDataBackup → progress, state, dates, speed/skip-silence
 *  - <book>/bookmarks.sabp.xml    → Bookmark rows
 *  - <root>/!Smart AudioBook Player Statistics/statistics.xml → ListeningSession rows (one per book and month)
 *  - <root>/!Smart AudioBook Player Statistics/<book folder>.jpg → fallback cover (see LibraryScanner)
 */

const val SABP_STATS_DIR = "!Smart AudioBook Player Statistics"
const val SABP_POSITION = "position.sabp.dat"
const val SABP_BOOKMARKS = "bookmarks.sabp.xml"

// ---- Minimal java.io serialization stream reader (pure Kotlin; enough for SABP's object graph) ----

class JavaObject(val className: String, val fields: Map<String, Any?>) {
    operator fun get(k: String) = fields[k]
}
class JavaEnum(val className: String, val name: String)
private class ClassDesc(val name: String, val flags: Int, val fields: List<Pair<Char, String>>, val superDesc: ClassDesc?)

/** Parses the first object of a Java serialization stream into nested [JavaObject]/[JavaEnum]/String/boxed primitives/arrays. */
fun readJavaSerialized(bytes: ByteArray): Any? = JavaStream(bytes).run {
    if (u16() != 0xACED || u16() != 5) error("not a Java serialization stream")
    content()
}

private class JavaStream(private val b: ByteArray) {
    private var p = 0
    private val handles = ArrayList<Any?>()

    fun u8() = b[p++].toInt() and 0xFF
    fun u16() = (u8() shl 8) or u8()
    fun i32() = (u16() shl 16) or u16()
    fun i64() = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)
    fun utf(len: Int) = String(b, p, len, Charsets.UTF_8).also { p += len } // ponytail: modified UTF-8 ≈ UTF-8 for real names
    private fun newHandle(o: Any?): Int { handles += o; return handles.size - 1 }

    fun content(): Any? = when (val tc = u8()) {
        0x70 -> null                                            // TC_NULL
        0x71 -> handles[i32() - 0x7E0000]                       // TC_REFERENCE
        0x74 -> utf(u16()).also { newHandle(it) }               // TC_STRING
        0x7C -> utf(i64().toInt()).also { newHandle(it) }       // TC_LONGSTRING
        0x72, 0x7D -> classDesc(tc)                              // TC_CLASSDESC / TC_PROXYCLASSDESC
        0x76 -> { val d = descOrNull(); d.also { newHandle(it) } } // TC_CLASS
        0x73 -> obj()
        0x7E -> {                                               // TC_ENUM
            val d = descOrNull()
            val h = newHandle(null)
            JavaEnum(d?.name.orEmpty(), content() as String).also { handles[h] = it }
        }
        0x75 -> array()
        0x77 -> { p += u8(); content() }                        // TC_BLOCKDATA: skip opaque data
        0x7A -> { p += i32(); content() }
        else -> error("unsupported tag 0x%02x at %d".format(tc, p - 1))
    }

    private fun descOrNull(): ClassDesc? = when (val v = content()) { null -> null; is ClassDesc -> v; else -> error("class desc expected") }

    private fun classDesc(tc: Int): ClassDesc {
        if (tc == 0x7D) error("proxy classes unsupported")
        val name = utf(u16())
        p += 8 // serialVersionUID
        val h = newHandle(null)
        val flags = u8()
        val fields = List(u16()) {
            val type = u8().toChar()
            val fname = utf(u16())
            if (type == 'L' || type == '[') content() // field class name (string or reference)
            type to fname
        }
        skipAnnotations()
        return ClassDesc(name, flags, fields, descOrNull()).also { handles[h] = it }
    }

    private fun skipAnnotations() { while (b[p].toInt() != 0x78) content(); p++ } // until TC_ENDBLOCKDATA

    private fun obj(): JavaObject {
        val d = descOrNull() ?: error("object without class")
        val h = newHandle(null)
        val chain = generateSequence(d) { it.superDesc }.toList().reversed()
        val values = LinkedHashMap<String, Any?>()
        val o = JavaObject(d.name, values)
        handles[h] = o
        for (c in chain) {
            if (c.flags and 0x04 != 0) { if (c.flags and 0x08 != 0) skipAnnotations(); continue } // externalizable
            for ((type, name) in c.fields) values[name] = value(type)
            if (c.flags and 0x01 != 0) {                                // writeObject data: keep the first block (java.util.Date)
                if (b[p].toInt() == 0x77 && c.name == "java.util.Date") { p++; val n = u8(); val s = p; values["time"] = i64(); p = s + n }
                skipAnnotations()
            }
        }
        return o
    }

    private fun value(type: Char): Any? = when (type) {
        'B' -> b[p++]; 'C' -> u16().toChar(); 'D' -> Double.fromBits(i64()); 'F' -> Float.fromBits(i32())
        'I' -> i32(); 'J' -> i64(); 'S' -> u16().toShort(); 'Z' -> u8() != 0
        else -> content()
    }

    private fun array(): Any? {
        val d = descOrNull()
        val h = newHandle(null)
        val n = i32()
        val comp = d?.name?.getOrNull(1) ?: 'L'
        return List(n) { value(comp) }.also { handles[h] = it }
    }
}

// ---- SABP data ----

data class SabpPosition(
    val fileName: String?,
    /** Raw mFilePosition: seconds in the SABP versions seen so far (ms in others); see [positionMs]. */
    val filePosition: Long,
    val speed: Float?,
    val state: BookState?,
    val skipSilence: Boolean,
    val boost: Int,
    val lastPlaybackTime: Long?,
    val creationTime: Long?,
)

/** SABP stores whole seconds; fall back to ms when the number can't be seconds inside the track. */
fun sabpPositionMs(raw: Long, trackDurationMs: Long): Long =
    if (trackDurationMs <= 0 || raw * 1000 <= trackDurationMs + 1000) raw * 1000 else raw.coerceAtMost(trackDurationMs)

fun parseSabpPosition(bytes: ByteArray): SabpPosition? {
    val o = runCatching { readJavaSerialized(bytes) }.getOrNull() as? JavaObject ?: return null
    if (!o.className.endsWith("BookDataBackup")) return null
    fun num(k: String) = (o[k] as? Number)?.toLong()
    return SabpPosition(
        fileName = o["mFileName"] as? String,
        filePosition = num("mFilePosition") ?: 0,
        speed = (o["mPlaybackSpeed"] as? Float)?.takeIf { it in 0.25f..5f },
        state = when ((o["mBookState"] as? JavaEnum)?.name) { "Started" -> BookState.STARTED; "Finished" -> BookState.FINISHED; "New" -> BookState.NEW; else -> null },
        skipSilence = (num("mSkipSilence") ?: 0) != 0L,
        boost = num("mBoostVolume")?.toInt() ?: 0,
        lastPlaybackTime = num("mLastPlaybackTime")?.takeIf { it > 0 },
        creationTime = ((o["mBookCreationTime"] as? JavaObject)?.get("time") as? Long)?.takeIf { it > 0 },
    )
}

data class SabpBookmark(val title: String, val description: String?, val fileName: String, val filePosition: Long)

private fun tagValue(xml: String, tag: String) =
    Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescapeXml)?.trim()

fun parseSabpBookmarks(xml: String): List<SabpBookmark> =
    Regex("<bookmark>(.*?)</bookmark>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
        val x = m.groupValues[1]
        val file = tagValue(x, "fileName") ?: return@mapNotNull null
        SabpBookmark(tagValue(x, "title").orEmpty(), tagValue(x, "description")?.ifBlank { null }, file, tagValue(x, "filePosition")?.toLongOrNull() ?: 0)
    }.toList()

/** One statistics entry: book folder path (relative to the root), month "YYYY-MM", seconds listened. */
data class SabpStat(val path: String, val year: Int, val month: Int, val seconds: Long)

fun parseSabpStatistics(xml: String): List<SabpStat> =
    Regex("<book>(.*?)</book>", RegexOption.DOT_MATCHES_ALL).findAll(xml).flatMap { m ->
        val path = tagValue(m.groupValues[1], "path") ?: return@flatMap emptySequence()
        Regex("<time>\\s*(\\d{4})-(\\d{1,2})\\s+(\\d+)\\s*</time>").findAll(m.groupValues[1]).map {
            SabpStat(path, it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toLong())
        }
    }.toList()

// ---- Import into Room ----

object Sabp {
    /** Progress/state/settings/bookmarks from SABP files in a book folder. Room wins unless SABP played the book more recently. */
    suspend fun importBook(context: Context, dao: LibraryDao, user: UserDataDao, bookId: String, entries: List<DocEntry>) {
        if (entries.none { it.name == SABP_POSITION || it.name == SABP_BOOKMARKS }) return
        val tracks = dao.tracks(bookId)
        entries.firstOrNull { it.name == SABP_POSITION }?.let { e ->
            val p = dao.progress(bookId)
            // ponytail: the file's mtime is ≥ SABP's lastPlaybackTime; skip parsing when Room is clearly newer
            if (p != null && e.mtime in 1..p.updatedAt) return@let
            val s = context.readBytes(e.uri)?.let(::parseSabpPosition) ?: return@let
            val at = s.lastPlaybackTime ?: s.creationTime ?: e.mtime
            s.creationTime?.let { dao.setAddedAtIfEarlier(bookId, it) }
            if (p != null && at <= p.updatedAt) return@let
            val idx = tracks.indexOfFirst { it.fileName == s.fileName }.takeIf { it >= 0 } ?: return@let
            dao.upsertProgress(BookProgress(bookId, idx, sabpPositionMs(s.filePosition, tracks[idx].durationMs), at))
            s.state?.takeIf { it != BookState.NEW }?.let { dao.setState(bookId, it) }
            if (s.lastPlaybackTime != null && (dao.book(bookId)?.lastPlayedAt ?: 0) < s.lastPlaybackTime) dao.setLastPlayed(bookId, s.lastPlaybackTime)
            // ponytail: SABP's boost level has no known dB scale and its EQ bands differ from ours, so only speed + skip silence
            val speed = s.speed?.takeIf { it != 1f }
            if (dao.settings(bookId) == null && (speed != null || s.skipSilence)) {
                dao.upsertSettings(BookSettings(bookId, speed = speed, skipSilence = s.skipSilence.takeIf { it }))
            }
        }
        entries.firstOrNull { it.name == SABP_BOOKMARKS }?.let { e ->
            val list = context.readBytes(e.uri)?.let(::decodeText)?.let(::parseSabpBookmarks).orEmpty()
            if (list.isEmpty()) return@let
            val have = user.bookmarksNow(bookId)
            for (bm in list) {
                val idx = tracks.indexOfFirst { it.fileName == bm.fileName }.takeIf { it >= 0 } ?: continue
                val pos = sabpPositionMs(bm.filePosition, tracks[idx].durationMs)
                if (have.any { it.trackIndex == idx && kotlin.math.abs(it.positionMs - pos) < 1000 }) continue
                user.insertBookmark(Bookmark(bookId = bookId, trackIndex = idx, positionMs = pos, title = bm.title.ifBlank { "Bookmark" }, note = bm.description, createdAt = e.mtime))
            }
        }
    }

    /**
     * statistics.xml → one ListeningSession per (path, month), startedAt = 1st of the month (local time).
     * Re-imports only raise listenedMs of the same row, so SABP's later listening in that month is picked up without duplicates.
     * [books] maps relative path → (bookId, title) for books still in the library.
     */
    suspend fun importStatistics(context: Context, dao: UserDataDao, statsDir: List<DocEntry>, books: Map<String, Pair<String, String>>) {
        val e = statsDir.firstOrNull { it.name.equals("statistics.xml", true) } ?: return
        val stats = context.readBytes(e.uri)?.let(::decodeText)?.let(::parseSabpStatistics).orEmpty()
        for (s in stats) {
            val start = Calendar.getInstance().apply { clear(); set(s.year, s.month - 1, 1) }.timeInMillis
            val ms = s.seconds * 1000
            val old = dao.sessionAt(s.path, start)
            if (old != null) { if (old.listenedMs < ms) dao.updateSession(old.id, ms); continue }
            val (id, title) = books[s.path] ?: ("sabp:${s.path}" to s.path.split('/').let { folderSeries(it.last(), it.getOrNull(it.size - 2)).title })
            dao.insertSession(ListeningSession(bookId = id, bookTitle = title, bookPath = s.path, startedAt = start, listenedMs = ms))
        }
    }
}
