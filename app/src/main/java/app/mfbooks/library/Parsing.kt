package app.mfbooks.library

/*
 * Contract between the pure parsers (parser agent) and the SAF scanner (scanner agent).
 * Everything here is pure Kotlin/JVM — no android.* — so it is unit-testable on the JVM.
 * The parser agent implements the TODO()s (may move bodies into other files in this package)
 * but MUST keep these names and signatures.
 */

/** Random access to a file's bytes (SAF ParcelFileDescriptor in the app, RandomAccessFile/ByteArray in tests). */
interface ByteSource {
    val size: Long
    /** Reads up to [len] bytes at [offset]; shorter only at EOF. */
    fun read(offset: Long, len: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size get() = bytes.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray =
        bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), (offset + len).toInt().coerceAtMost(bytes.size))
}

data class RawChapter(val title: String, val startMs: Long)

/** Tags as read by Media3 MetadataRetriever (+ what the parsers find). Blank/unknown = null. */
data class TagInfo(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val composer: String? = null,
    val narrator: String? = null,      // TXXX:NARRATOR etc.
    val year: String? = null,
    val genre: String? = null,
    val publisher: String? = null,
    val series: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val encoder: String? = null,
    val copyright: String? = null,     // ©2019 X;(P)2019 Publisher — publisher/year fallback
)

data class NfoData(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val expectedChapters: Int? = null,
    val expectedDurationMs: Long? = null,
    val description: String? = null,
    val raw: Map<String, String> = emptyMap(),
)

data class CueTrack(val title: String?, val performer: String?, val startMs: Long)
data class CueFile(val name: String, val tracks: List<CueTrack>)
data class CueSheet(val title: String?, val performer: String?, val files: List<CueFile>)

/** One audio file of a book as the scanner sees it. */
data class FileInput(
    val name: String,
    val durationMs: Long,
    val tags: TagInfo,
    val embeddedChapters: List<RawChapter>?,
    /** Chapters cached in .mfbooks.json for converted files (§4.5); used only when nothing else is found. */
    val cachedChapters: List<RawChapter>? = null,
)

data class MergeInput(
    val folderName: String,
    val parentFolderName: String?,
    val files: List<FileInput>,              // already natural-sorted
    val nfo: NfoData?,
    val cues: List<Pair<String, CueSheet>>,  // (cue file name, parsed sheet)
    val infoTxt: String?,
    val libbyChapters: Map<String, List<RawChapter>> = emptyMap(), // file name → chapters (openbook.json / libby.txt)
    val useTagTitles: Boolean = true,
)

data class MergedBook(
    val title: String,
    val author: String?,
    val narrator: String?,
    val year: String?,
    val publisher: String?,
    val genre: String?,
    val series: String?,
    val seriesIndex: String?,
    val description: String?,
    /** Per file (same order as input). A file with no chapters gets one chapter = the file. */
    val chapters: List<List<RawChapter>>,
    val fieldSources: Map<String, String>,
)

/** Embedded chapters for one audio file (§4.3 step 2): MP4 chpl/QT text track, ID3 CHAP, OverDrive TXXX, Vorbis CHAPTERxxx. */
fun parseChapters(src: ByteSource, fileName: String): List<RawChapter>? = runCatching {
    val head = src.read(0, 16)
    when {
        head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> readId3(src)?.let(::id3Chapters)
        isMp4(head) -> mp4Chapters(src)
        else -> readVorbisComments(src, head)?.let(::vorbisChapters)
    }?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** Extra tags MetadataRetriever misses (TXXX:NARRATOR, encoder, ©des long description...). May return null. */
fun parseExtraTags(src: ByteSource, fileName: String): TagInfo? = runCatching {
    val head = src.read(0, 16)
    when {
        head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> readId3(src)?.let(::id3Tags)
        isMp4(head) -> mp4Tags(src)
        else -> readVorbisComments(src, head)?.let(::vorbisTags)
    }
}.getOrNull()

/** [parseChapters] + [parseExtraTags] reading the MP4 `moov` only once (it is ~10 MB for a 16 h book). */
fun parseFile(src: ByteSource, fileName: String): Pair<List<RawChapter>?, TagInfo?> {
    val head = runCatching { src.read(0, 16) }.getOrNull() ?: return null to null
    if (!isMp4(head)) return parseChapters(src, fileName) to parseExtraTags(src, fileName)
    val moov = runCatching { readMoov(src) }.getOrNull() ?: return null to null
    return runCatching { mp4Chapters(src, moov) }.getOrNull()?.takeIf { it.isNotEmpty() } to runCatching { mp4Tags(moov) }.getOrNull()
}

val AUDIO_EXTENSIONS = setOf(
    "mp3", "ogg", "oga", "aac", "mp4", "m4a", "m4b", "opus", "wav", "flac", "amr", "awb", "mpga",
    "aiff", "aif", "wv", "3gp", "mka", "mkv", "webm", "weba", "m4v", "wma",
)
val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
