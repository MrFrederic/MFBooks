package app.mfbooks.server

import app.mfbooks.convert.Eligibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.abs

class Probe(val durationMs: Long, val codec: String?, val channels: Int, val tags: Map<String, String>, val chapters: List<Pair<String, Long>>, val hasPicture: Boolean)

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

fun ffprobe(f: File): Probe? {
    val p = ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", "-show_chapters", f.path)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) return null
    val o = Json.parseToJsonElement(out).jsonObject
    val format = o["format"]?.jsonObject ?: return null
    val streams = o["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    val audio = streams.firstOrNull { it.s("codec_type") == "audio" } ?: return null
    // Vorbis comments live on the stream, MP4/ID3 tags on the format.
    val tags = (listOfNotNull(format["tags"], audio["tags"])).flatMap { it.jsonObject.entries }
        .associate { (k, v) -> k.lowercase() to (v as JsonPrimitive).content }
    val chapters = o["chapters"]?.jsonArray?.map { it.jsonObject }.orEmpty().map { c ->
        (c["tags"]?.jsonObject?.s("title") ?: "") to ((c.s("start_time")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }
    return Probe(((format.s("duration") ?: audio.s("duration"))?.toDoubleOrNull()?.times(1000))?.toLong() ?: 0,
        audio.s("codec_name"), audio.s("channels")?.toIntOrNull() ?: 2, tags, chapters,
        streams.any { it.s("codec_type") == "video" })
}

/** Runs ffmpeg; returns the last `out_time_us` (= decoded length). Throws with ffmpeg's own message on failure. */
fun ffmpeg(args: List<String>, onTimeUs: (Long) -> Unit = {}): Long {
    val err = File.createTempFile("ffmpeg", ".log")
    try {
        val p = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-y", "-progress", "pipe:1") + args)
            .redirectError(err).start()
        var last = 0L
        p.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let { last = it; onTimeUs(it) }
        }
        if (p.waitFor() != 0) error("ffmpeg: " + err.readText().trim().lines().takeLast(3).joinToString(" ").ifEmpty { "failed" })
        return last
    } finally { err.delete() }
}

/**
 * One book at a time, track by track, to Ogg Opus at the app's settings. A converted track replaces the original only if
 * it decodes fully and its length is within 50 ms of the original's decoded length.
 */
class Converter(private val app: App) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    fun start(scope: CoroutineScope) {
        resume()
        scope.launch(Dispatchers.IO) {
            for (id in queue) try { convert(id) } catch (e: Exception) {
                log.warn("Conversion of $id failed: ${e.message}")
                setState(id, "failed", 0.0, e.message)
            }
        }
    }

    fun resume() = app.db.query("SELECT book_id FROM cloud WHERE conversion IN ('queued', 'converting')") { it.getString(1) }.forEach { queue.trySend(it) }

    fun enqueue(bookId: String) {
        if (!app.convertOn) return setState(bookId, "none", 0.0, null)
        setState(bookId, "queued", 0.0, null)
        queue.trySend(bookId)
    }

    private fun setState(id: String, state: String, progress: Double, error: String?) {
        app.db.update("INSERT OR REPLACE INTO cloud(book_id, conversion, progress, error) VALUES (?, ?, ?, ?)", id, state, progress, error)
        app.live.book(id, state, progress)
    }

    private fun convert(id: String) {
        if (!app.convertOn) return setState(id, "none", 0.0, null)
        val files = app.library.files(id).filter { it.state == "ready" && it.original }
        val probes = files.associateWith { ffprobe(File(app.books, it.path)) }
        val todo = files.filter { f ->
            val p = probes[f] ?: return@filter false
            p.codec != "opus" && Eligibility.isEligible(f.path, f.received, p.durationMs)
        }
        // Files at 50 kb/s or less, or already Opus, are kept as they are.
        (files - todo.toSet()).forEach { app.db.update("UPDATE files SET original = 0 WHERE book_id = ? AND idx = ?", id, it.idx) }
        val total = todo.sumOf { probes[it]!!.durationMs }.coerceAtLeast(1)
        var done = 0L
        var lastReport = 0L
        setState(id, "converting", 0.0, null)
        for (f in todo) {
            val src = File(app.books, f.path)
            val probe = probes[f]!!
            val tmp = File(src.parentFile, "${f.idx}.opus.tmp")
            val channels = if (probe.channels > 2) listOf("-ac", "2") else emptyList()
            val srcUs = ffmpeg(listOf("-i", src.path, "-map", "0:a:0", "-c:a", "libopus", "-b:a", "${Eligibility.BITRATE / 1000}k", "-vbr", "on") + channels + listOf("-f", "opus", tmp.path)) { us ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { lastReport = now; setState(id, "converting", (done + us / 1000).toDouble() / total, null) }
            }
            val outUs = try { ffmpeg(listOf("-xerror", "-i", tmp.path, "-f", "null", "-")) } catch (e: Exception) { tmp.delete(); throw e }
            if (abs(outUs - srcUs) > 50_000) {
                tmp.delete()
                error("Track ${f.idx + 1}: the Opus file is ${abs(outUs - srcUs) / 1000} ms off; kept the original")
            }
            val dst = File(src.parentFile, "${f.idx}.opus")
            if (!tmp.renameTo(dst)) error("Could not move ${tmp.name}")
            if (src != dst) src.delete()
            app.db.update("UPDATE files SET path = ?, format = 'opus', original = 0, size = ?, received = ? WHERE book_id = ? AND idx = ?",
                "$id/${dst.name}", dst.length(), dst.length(), id, f.idx)
            done += probe.durationMs
        }
        setState(id, "done", 1.0, null)
        log.info("Converted $id: ${todo.size} of ${files.size} tracks")
    }
}
