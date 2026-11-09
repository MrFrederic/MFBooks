package app.mfbooks.text

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A recognised word, [startMs] relative to the start of its chunk. */
data class SpokenWord(val word: String, val startMs: Int)

/**
 * The transcript file: one gzip member per chunk, appended and fsynced. Each member's header line names the chunk it covers, so
 * coverage needs no separate checkpoint file and survives a reinstall through the book-folder mirror. A member cut short by a
 * kill fails its CRC and is cut off on the next load.
 */
object Transcript {
    private const val MAGIC = "#mfbooks-transcript-1"
    /** How far a member's audio length may differ per track and still be the same recording; matches [SyncMap.of]. */
    const val TOLERANCE_PER_TRACK_MS = 1500

    /** Words of [fromMs, untilMs) in book time. [audioMs] is the book's length when it was transcribed. */
    class Member(val fromMs: Int, val untilMs: Int, val audioMs: Int, val engine: String, val words: List<TimedWord>)
    class Parsed(val members: List<Member>, val validBytes: Long)

    fun encode(m: Member): ByteArray = gzip(buildString {
        append(MAGIC).append(' ').append(m.fromMs).append(' ').append(m.untilMs).append(' ').append(m.audioMs).append(' ').append(m.engine).append('\n')
        for (w in m.words) append(w.ms).append(' ').append(w.w.replace('\n', ' ').replace('\r', ' ')).append('\n')
    }.toByteArray())

    /** Every intact member from the start; [Parsed.validBytes] is where the first damaged one begins. */
    fun decode(bytes: ByteArray): Parsed {
        val out = ArrayList<Member>()
        var pos = 0
        while (pos < bytes.size) {
            val (text, next) = inflateMember(bytes, pos) ?: break
            out += parse(text) ?: break
            pos = next
        }
        return Parsed(out, pos.toLong())
    }

    /** Members for this audio. Cuts a torn tail off the file; members of other audio stay in it, ignored. */
    fun load(file: File, audioMs: Int, tracks: Int): List<Member> {
        if (!file.exists()) return emptyList()
        val parsed = decode(file.readBytes())
        if (parsed.validBytes < file.length()) RandomAccessFile(file, "rw").use { it.setLength(parsed.validBytes) }
        val tolerance = TOLERANCE_PER_TRACK_MS * max(1, tracks)
        return parsed.members.filter { abs(it.audioMs - audioMs) <= tolerance }
    }

    fun append(file: File, m: Member) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(encode(m)); it.fd.sync() }
    }

    private fun parse(text: String): Member? {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.iterator()
        if (!lines.hasNext()) return null
        val head = lines.next().split(' ', limit = 5)
        if (head.size != 5 || head[0] != MAGIC) return null
        val from = head[1].toIntOrNull() ?: return null
        val until = head[2].toIntOrNull() ?: return null
        val audio = head[3].toIntOrNull() ?: return null
        val words = ArrayList<TimedWord>()
        for (line in lines) {
            val sp = line.indexOf(' ')
            if (sp <= 0) return null
            words += TimedWord(line.substring(sp + 1), line.substring(0, sp).toIntOrNull() ?: return null)
        }
        return Member(from, until, audio, head[4], words)
    }

    /** The text of the gzip member at [pos] and the offset after it, or null if it's incomplete or damaged. */
    private fun inflateMember(b: ByteArray, pos: Int): Pair<String, Int>? {
        if (b.size - pos < 18 || b[pos] != 0x1f.toByte() || b[pos + 1] != 0x8b.toByte() || b[pos + 2] != 8.toByte() || b[pos + 3] != 0.toByte()) return null
        val inf = Inflater(true)
        try {
            inf.setInput(b, pos + 10, b.size - pos - 10)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (!inf.finished()) {
                val n = try { inf.inflate(buf) } catch (e: DataFormatException) { return null }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
            }
            val end = pos + 10 + inf.bytesRead.toInt()
            if (end + 8 > b.size) return null
            val data = out.toByteArray()
            if (CRC32().apply { update(data) }.value != le32(b, end) || (data.size.toLong() and 0xFFFFFFFFL) != le32(b, end + 4)) return null
            return String(data) to end + 8
        } finally { inf.end() }
    }

    private fun le32(b: ByteArray, i: Int): Long = (0..3).fold(0L) { acc, k -> acc or ((b[i + k].toLong() and 0xFF) shl (8 * k)) }
}

/** Stretches of audio as [IntRange]s with inclusive ends, as [Aligner.align] takes them. */
object Coverage {
    fun range(fromMs: Int, untilMs: Int) = fromMs..(untilMs - 1)

    fun merge(ranges: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in ranges.filter { !it.isEmpty() }.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..max(last.last, r.last) else out += r
        }
        return out
    }

    fun totalMs(ranges: List<IntRange>): Int = ranges.sumOf { it.last - it.first + 1 }

    /** The parts of [fromMs, untilMs) outside [covered] (merged). */
    fun gaps(fromMs: Int, untilMs: Int, covered: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var at = fromMs
        for (r in covered) {
            if (r.last < at) continue
            if (r.first >= untilMs) break
            if (r.first > at) out += at..(r.first - 1)
            at = max(at, r.last + 1)
        }
        if (at < untilMs) out += at..(untilMs - 1)
        return out
    }
}

data class Chunk(val fromMs: Int, val untilMs: Int) { val durationMs get() = untilMs - fromMs }

object ChunkPlan {
    const val MIN_PIECE_MS = 1000

    /**
     * What's left to transcribe, on a fixed grid so a moved listener doesn't shift the chunks: the cell holding [startMs], forward
     * to the end, then wrapping to the start. Covered parts are removed; slivers under [MIN_PIECE_MS] aren't worth a session.
     */
    fun plan(audioMs: Int, startMs: Int, chunkMs: Int, covered: List<IntRange>): List<Chunk> {
        if (audioMs <= 0) return emptyList()
        val merged = Coverage.merge(covered)
        val cells = (audioMs + chunkMs - 1) / chunkMs
        val first = startMs.coerceIn(0, audioMs - 1) / chunkMs
        return (0 until cells).map { (first + it) % cells }.flatMap { c ->
            Coverage.gaps(c * chunkMs, min((c + 1) * chunkMs, audioMs), merged).map { Chunk(it.first, it.last + 1) }
        }.filter { it.durationMs >= MIN_PIECE_MS }
    }
}

/** A part of one file: [trackIndex] is the position in the list given to [AudioSpans.of]. */
data class TrackSpan(val trackIndex: Int, val fromInTrackMs: Long, val untilInTrackMs: Long)

object AudioSpans {
    /** Maps book time [fromMs, untilMs) onto files. Book time is the sum of the previous tracks' durations plus the offset. */
    fun of(durations: List<Long>, fromMs: Long, untilMs: Long): List<TrackSpan> {
        val out = ArrayList<TrackSpan>()
        var start = 0L
        durations.forEachIndexed { i, d ->
            val a = max(fromMs, start); val b = min(untilMs, start + d)
            if (a < b) out += TrackSpan(i, a - start, b - start)
            start += d
        }
        return out
    }
}

fun normalizeWord(w: String): String =
    Normalizer.normalize(w, Normalizer.Form.NFKC).lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

fun bookWords(doc: BookTextDoc): Set<String> =
    doc.blocks.flatMapTo(HashSet()) { b -> b.s.split(Regex("\\s+")).map(::normalizeWord).filter { it.isNotEmpty() } }

object ProbeRules {
    const val MIN_WORDS = 20
    const val MIN_TEXT_MATCH = 0.25
    /** The recogniser must beat this many times realtime, or a 16-hour book takes too long. */
    const val MIN_SPEED = 2.0

    fun accepts(words: List<SpokenWord>, book: Set<String>, audioMs: Long, wallMs: Long): Boolean {
        if (words.size < MIN_WORDS) return false
        if (words.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return false
        val normal = words.map { normalizeWord(it.word) }.filter { it.isNotEmpty() }
        if (normal.isEmpty() || normal.count { it in book }.toDouble() / normal.size < MIN_TEXT_MATCH) return false
        return wallMs <= 0 || audioMs.toDouble() / wallMs > MIN_SPEED
    }
}

object Outcome {
    /** ponytail: thresholds from the plan, not tuned on real books yet. */
    const val READY_COVERAGE = 0.5f
    const val EARLY_MIN_RATIO = 0.5f
    const val EARLY_AFTER_MS = 3_600_000

    /** Of the text the transcript has reached, the share that matched. Book-wide coverage can't say this after one hour. */
    fun matchedRatio(ranges: List<SyncRange>): Float {
        val word = ranges.filter { it.kind == RangeKind.WORD }.sumOf { (it.toChar - it.fromChar).toLong() }
        val none = ranges.filter { it.kind == RangeKind.NONE }.sumOf { (it.toChar - it.fromChar).toLong() }
        return if (word + none == 0L) 0f else word.toFloat() / (word + none)
    }

    /** True once, at the alignment that crosses the first hour of transcribed audio. */
    fun stopEarly(coveredBeforeMs: Int, coveredNowMs: Int, ratio: Float) =
        coveredBeforeMs < EARLY_AFTER_MS && coveredNowMs >= EARLY_AFTER_MS && ratio < EARLY_MIN_RATIO
}

/** Most common language among confident samples ("ru-RU" → "ru"); null when none or tied. */
fun majorityLanguage(samples: List<String?>): String? {
    val counts = samples.filterNotNull().map { it.substringBefore('-').lowercase() }.filter { it == "ru" || it == "en" }.groupingBy { it }.eachCount()
    val best = counts.maxByOrNull { it.value } ?: return null
    return best.key.takeIf { counts.count { c -> c.value == best.value } == 1 }
}

/** Vosk's result JSON → words; `start` is in seconds. A result without timings gives nothing. */
fun parseVoskResult(json: String): List<SpokenWord> {
    val a = JSONObject(json).optJSONArray("result") ?: return emptyList()
    return (0 until a.length()).map { i -> a.getJSONObject(i).let { SpokenWord(it.getString("word"), (it.getDouble("start") * 1000).roundToInt()) } }
}
