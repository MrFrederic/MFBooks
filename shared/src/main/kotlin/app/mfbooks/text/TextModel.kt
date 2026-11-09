package app.mfbooks.text

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** text.json.gz. Immutable once written: every offset in a [SyncDoc] depends on it. See docs/MFBooks-ReadAlong.md §1.4. */
@Serializable
data class BookTextDoc(
    val v: Int = 1,
    val lang: String,
    val title: String? = null,
    val author: String? = null,
    val source: TextSource,
    val toc: List<TocEntry> = emptyList(),
    val blocks: List<Block>,
)

@Serializable
data class TextSource(val name: String, val format: String, val sha256: String)

@Serializable
data class TocEntry(val title: String, val block: Int)

/** [k] is one of [BlockKind]. [sen] holds sentence starts and [em] `[start, end]` pairs, both relative to [s]. */
@Serializable
data class Block(val k: String, val s: String, val sen: List<Int> = emptyList(), val em: List<List<Int>> = emptyList())

object BlockKind {
    const val H1 = "h1"; const val H2 = "h2"; const val H3 = "h3"; const val P = "p"
    const val QUOTE = "quote"; const val POEM = "poem"; const val NOTE = "note"; const val IMAGE = "image"
}

/** Start of each block in the UTF-16 concatenation of all [Block.s]; one extra entry holds the total length. */
fun BookTextDoc.blockStarts(): IntArray {
    val out = IntArray(blocks.size + 1)
    for (i in blocks.indices) out[i + 1] = out[i] + blocks[i].s.length
    return out
}

/** sync.json.gz: one exact [BookTextDoc] (by [textSha]) tied to audio time. [chars] and [ms] are delta-encoded, one entry per knot. */
@Serializable
data class SyncDoc(
    val v: Int = 1,
    val textSha: String,
    val tier: String,
    val engine: String? = null,
    val tracks: List<SyncTrack>,
    val chars: List<Int> = emptyList(),
    val ms: List<Int> = emptyList(),
    val ranges: List<SyncRange> = emptyList(),
    val user: List<UserKnot> = emptyList(),
)

object SyncTier { const val APPROXIMATE = "approximate"; const val ALIGNED = "aligned" }

object RangeKind {
    /** Word-level timing. */ const val WORD = "word"
    /** Not transcribed yet, paragraph-level estimate. */ const val APPROX = "approx"
    /** Transcribed, but this text isn't in the audio (or the reverse). */ const val NONE = "none"
}

@Serializable data class SyncTrack(val name: String, val durationMs: Long)
@Serializable data class SyncRange(val fromChar: Int, val toChar: Int, val quality: Float, val kind: String = RangeKind.WORD)
@Serializable data class UserKnot(val char: Int, val ms: Int)

/** A recognised word at its position on the book timeline. */
data class TimedWord(val w: String, val ms: Int)

/** An audio chapter, for approximate sync. */
data class ChapterRef(val title: String, val startInBookMs: Int)

/** What [SyncMap.at] found for a moment of audio. */
data class SyncPosition(val char: Int, val kind: String)

class TextParseException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { DRM, EMPTY, TOO_LARGE, UNSUPPORTED }
}

val TextJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun BookTextDoc.toGz(): ByteArray = gzip(TextJson.encodeToString(this).toByteArray())
fun SyncDoc.toGz(): ByteArray = gzip(TextJson.encodeToString(this).toByteArray())
fun textDocFromGz(bytes: ByteArray): BookTextDoc = TextJson.decodeFromString(String(gunzip(bytes)))
fun syncDocFromGz(bytes: ByteArray): SyncDoc = TextJson.decodeFromString(String(gunzip(bytes)))
