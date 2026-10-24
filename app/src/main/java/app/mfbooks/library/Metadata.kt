@file:OptIn(UnstableApi::class)

package app.mfbooks.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.data.Chapter
import app.mfbooks.data.RootFolder
import app.mfbooks.data.Track
import org.json.JSONObject
import java.security.MessageDigest

/** A folder containing audio, as found by the directory walk. [entries] are its files (not sub-folders). */
class Candidate(
    val root: RootFolder, val tree: Uri, val docId: String, val relPath: String, val name: String,
    val parentName: String?, val entries: List<DocEntry>,
) {
    val folderUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    val id = sha1(folderUri.toString()).take(16)
    val audio = entries.filter { it.ext in AUDIO_EXTENSIONS }.sortedWith(compareBy(NaturalOrder) { it.name })
    val images = entries.filter { it.ext in IMAGE_EXTENSIONS }
    private fun isSidecar(e: DocEntry) = e.ext in IMAGE_EXTENSIONS || e.ext == "nfo" || e.ext == "cue" ||
        e.name.lowercase() in setOf("info.txt", "openbook.json", "libby.txt")
    val sidecars = entries.filter(::isSidecar)
    fun signature(useTagTitles: Boolean) = sha1(
        "v2|$useTagTitles\n" + (audio + sidecars).sortedBy { it.name }.joinToString("\n") { "${it.name}|${it.size}|${it.mtime}" },
    )
}

fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

class MediaInfo(val tags: TagInfo, val durationMs: Long, val art: ByteArray?)

/** What we learned about one audio file; also feeds the sidecar. */
class ScannedFile(val entry: DocEntry, val tags: TagInfo, val durationMs: Long, val chapters: List<RawChapter>)

class Scanned(val book: Book, val tracks: List<Track>, val chapters: List<Chapter>, val files: List<ScannedFile>)

/** Media3 MetadataRetriever: tags, duration, artwork. Blocking; call on Dispatchers.IO. */
fun readMedia(context: Context, uri: Uri): MediaInfo? = try {
    MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { r ->
        val groups = r.retrieveTrackGroups().get()
        val durUs = r.retrieveDurationUs().get()
        val m = HashMap<String, String>()
        var art: ByteArray? = null
        fun put(k: String, v: String?) { if (!v.isNullOrBlank()) m.putIfAbsent(k, v.trim()) }
        for (g in 0 until groups.length) for (t in 0 until groups[g].length) {
            val md: Metadata = groups[g].getFormat(t).metadata ?: continue
            for (i in 0 until md.length()) when (val e = md[i]) {
                is TextInformationFrame -> id3(e.id, e.description, e.values.firstOrNull(), ::put)
                is CommentFrame -> put(if (e.description.isNullOrBlank()) "comment" else "comment2", e.text)
                is VorbisComment -> vorbis(e.key, e.value, ::put)
                is ApicFrame -> art = art ?: e.pictureData
                is PictureFrame -> art = art ?: e.pictureData
            }
        }
        val tags = TagInfo(
            title = m["title"], artist = m["artist"], albumArtist = m["albumArtist"], album = m["album"],
            composer = m["composer"], narrator = m["narrator"], year = m["year"]?.let { Regex("\\d{4}").find(it)?.value },
            genre = m["genre"], publisher = m["publisher"], series = m["series"], comment = m["comment"] ?: m["comment2"],
            description = m["description"], encoder = m["encoder"], copyright = m["copyright"],
        )
        MediaInfo(tags, if (durUs > 0) durUs / 1000 else 0, art)
    }
} catch (e: Exception) { Log.w("Metadata", "retrieve failed: $uri", e); null }

private fun id3(id: String, desc: String?, v: String?, put: (String, String?) -> Unit) {
    when (id) {
        "TIT2" -> put("title", v); "TPE1" -> put("artist", v); "TPE2" -> put("albumArtist", v)
        "TALB" -> put("album", v); "TCOM" -> put("composer", v); "TCON" -> put("genre", v)
        "TDRC", "TYER", "TDRL", "TORY" -> put("year", v); "TPUB" -> put("publisher", v)
        "TENC", "TSSE" -> put("encoder", v); "TCOP" -> put("copyright", v)
        "TXXX" -> when (desc?.lowercase()) {
            "narrator", "reader", "read by", "narrated by" -> put("narrator", v)
            "series", "series name", "mvnm" -> put("series", v)
            "publisher" -> put("publisher", v)
            "description" -> put("description", v)
        }
    }
}

private fun vorbis(key: String, v: String, put: (String, String?) -> Unit) {
    when (key.uppercase()) {
        "TITLE" -> put("title", v); "ARTIST" -> put("artist", v); "ALBUMARTIST" -> put("albumArtist", v)
        "ALBUM" -> put("album", v); "COMPOSER" -> put("composer", v); "GENRE" -> put("genre", v)
        "DATE", "YEAR" -> put("year", v); "NARRATOR", "READER" -> put("narrator", v)
        "PUBLISHER", "ORGANIZATION" -> put("publisher", v); "SERIES" -> put("series", v)
        "COMMENT" -> put("comment", v); "DESCRIPTION" -> put("description", v); "ENCODER" -> put("encoder", v); "COPYRIGHT" -> put("copyright", v)
    }
}

private fun text(ctx: Context, e: DocEntry?) = e?.let { ctx.readBytes(it.uri)?.let(::decodeText) }

/** Blocking: reads tags, chapters and sidecars, merges, resolves the cover. [sc] = existing .mfbooks.json (null = none/ignored). */
fun analyze(ctx: Context, c: Candidate, old: Book?, sc: JSONObject?, useCache: Boolean, useTagTitles: Boolean, fallbackCover: DocEntry? = null): Scanned {
    var art: ByteArray? = null
    val files = c.audio.mapIndexed { i, e ->
        val cached = if (useCache) Sidecar.cachedFile(sc, e) else null
        var tags: TagInfo; var dur: Long
        if (cached != null) {
            tags = Sidecar.tags(cached); dur = cached.optLong("durationMs")
            // ponytail: cover art isn't cached, so the first file always goes through Media3 once more
            if (i == 0) art = readMedia(ctx, e.uri)?.art
        } else {
            val mi = readMedia(ctx, e.uri)
            tags = mi?.tags ?: TagInfo(); dur = mi?.durationMs ?: 0
            if (i == 0) art = mi?.art
        }
        var embedded: List<RawChapter>? = null
        try {
            SafSource(ctx, e.uri).use { src ->
                val (ch, extra) = parseFile(src, e.name)
                embedded = ch
                if (cached == null) tags = tags.fill(extra)
            }
        } catch (ex: Exception) { Log.w("Metadata", "parse failed: ${e.name}", ex) }
        val prev = Sidecar.fileEntry(sc, e.name)
        val kept = prev?.takeIf { it.has("converted") && kotlin.math.abs(it.optLong("durationMs") - dur) < 1000 }?.let(Sidecar::chapters)
        ScannedFile(e, tags, dur, emptyList()) to FileInput(e.name, dur, tags, embedded?.takeIf { it.isNotEmpty() }, kept)
    }

    val nfoEntries = c.entries.filter { it.ext == "nfo" }
    val nfoEntry = nfoEntries.firstOrNull { it.name.substringBeforeLast('.').equals(c.name, true) } ?: nfoEntries.firstOrNull()
    val nfo = text(ctx, nfoEntry)?.let { runCatching { parseNfo(it) }.getOrNull() }
    val cues = c.entries.filter { it.ext == "cue" }.mapNotNull { e -> text(ctx, e)?.let { t -> runCatching { e.name to parseCue(t) }.getOrNull() } }
    val infoTxt = text(ctx, c.entries.firstOrNull { it.name.equals("info.txt", true) })
    val libby = text(ctx, c.entries.firstOrNull { it.name.equals("openbook.json", true) })
        ?.let { runCatching { parseOpenbookJson(it) }.getOrNull() }.orEmpty()

    val m = mergeBook(MergeInput(c.name, c.parentName, files.map { it.second }, nfo, cues, infoTxt, libby, useTagTitles))

    // User edits in .mfbooks.json win (§4.5).
    val bj = sc?.optJSONObject("book")
    val edited = (bj?.optJSONArray("userEdited") ?: sc?.optJSONArray("userEdited"))?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty()
    fun f(key: String, merged: String?) = if (key in edited && bj?.has(key) == true) bj.optString(key).ifBlank { null } else merged
    val sources = m.fieldSources.toMutableMap().also { s -> edited.forEach { s[it] = "user" } }

    val tracks = ArrayList<Track>(); val chapters = ArrayList<Chapter>(); val scanned = ArrayList<ScannedFile>()
    var offset = 0L
    files.forEachIndexed { i, (sf, _) ->
        val e = sf.entry
        val raw = m.chapters.getOrNull(i).orEmpty().ifEmpty { listOf(RawChapter(sf.tags.title ?: e.name.substringBeforeLast('.'), 0)) }
        tracks += Track(c.id, i, e.uri.toString(), e.name, sf.tags.title, sf.durationMs, e.size, e.mtime)
        raw.forEachIndexed { k, ch ->
            val end = raw.getOrNull(k + 1)?.startMs ?: sf.durationMs
            chapters += Chapter(c.id, chapters.size, ch.title, i, ch.startMs, (end - ch.startMs).coerceAtLeast(0), offset + ch.startMs)
        }
        scanned += ScannedFile(e, sf.tags, sf.durationMs, raw)
        offset += sf.durationMs
    }

    val ranked = rankCoverCandidates(c.images.map { it.name }, c.audio.map { it.name.substringBeforeLast('.') }, c.name)
        .mapNotNull { n -> c.images.firstOrNull { it.name == n } }
    val (cover, seed) = Covers.resolve(ctx, c.id, ranked, art, fallbackCover)

    val book = Book(
        id = c.id, rootUri = c.root.uri, folderUri = c.folderUri.toString(), relativePath = c.relPath,
        title = f("title", m.title) ?: c.name, author = f("author", m.author), narrator = f("narrator", m.narrator),
        year = f("year", m.year), genre = f("genre", m.genre), publisher = f("publisher", m.publisher),
        series = f("series", m.series), seriesIndex = f("seriesIndex", m.seriesIndex),
        coverUri = cover, seedColor = seed, durationMs = offset, fileCount = tracks.size,
        state = old?.state ?: BookState.NEW, addedAt = old?.addedAt ?: System.currentTimeMillis(),
        lastPlayedAt = old?.lastPlayedAt, queuePosition = old?.queuePosition, hidden = old?.hidden ?: false,
        description = f("description", m.description), fieldSources = JSONObject(sources as Map<*, *>).toString(),
        scanSignature = c.signature(useTagTitles),
        serverId = old?.serverId ?: sc?.optString("serverId")?.ifBlank { null },
    )
    return Scanned(book, tracks, chapters, scanned)
}
