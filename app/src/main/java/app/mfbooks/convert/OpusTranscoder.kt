package app.mfbooks.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

/** What the source turned out to be. */
class SourceInfo(val mime: String, val durationMs: Long, val channels: Int, val sampleRate: Int)

/**
 * MP3/AAC → Ogg/Opus with platform APIs only (§4.6 steps 3–5): MediaExtractor + MediaCodec decode,
 * [Resampler] to 48 kHz, MediaCodec Opus encoder, [OggOpusWriter] (not MediaMuxer OGG: see there). Blocking; run on Dispatchers.IO.
 */
object OpusTranscoder {
    const val BITRATE = 32_000
    private const val RATE = 48_000
    private const val TIMEOUT_US = 10_000L

    /** Probe without decoding: first audio track's mime and container duration. null = no audio track. */
    fun probe(src: FileDescriptor): SourceInfo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src)
            val t = audioTrack(ex) ?: return null
            val f = ex.getTrackFormat(t)
            return SourceInfo(f.getString(MediaFormat.KEY_MIME)!!, f.longOr(MediaFormat.KEY_DURATION, 0) / 1000,
                f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2), f.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100))
        } catch (e: Exception) { return null } finally { ex.release() }
    }

    /**
     * Transcodes [src] into [dst] (an Ogg file opened "rw"). Mono stays mono; anything else becomes stereo.
     * [comments] become the file's OpusTags. [progress] gets 0..1; [cancelled] is polled and aborts with CancellationException.
     */
    fun transcode(
        src: FileDescriptor, dst: FileDescriptor, comments: List<Pair<String, String>>,
        progress: (Float) -> Unit, cancelled: () -> Boolean,
    ): SourceInfo {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null; var enc: MediaCodec? = null
        try {
            ex.setDataSource(src)
            val track = audioTrack(ex) ?: error("no audio track")
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = inFmt.longOr(MediaFormat.KEY_DURATION, 0)
            dec = MediaCodec.createDecoderByType(mime).apply { configure(inFmt, null, null, 0); start() }
            val stream = BufferedOutputStream(FileOutputStream(dst), 1 shl 16)
            val ogg = OggOpusWriter(stream)
            var head: ByteArray? = null
            var headersWritten = false
            var packets = 0L

            var srcRate = inFmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var srcCh = inFmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var floatPcm = false
            var outCh = 0
            var rs: Resampler? = null
            val pending = ArrayDeque<ShortArray>(); var pendingHead = 0 // samples of pending.first() already queued
            var pendingSamples = 0L
            var queuedFrames = 0L
            var inEos = false; var pcmEos = false; var encInEos = false; var done = false
            val info = MediaCodec.BufferInfo()

            fun startEncoder() {
                outCh = if (srcCh == 1) 1 else 2
                rs = Resampler(srcRate, RATE, outCh)
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, outCh).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            }

            fun push(a: ShortArray) { if (a.isNotEmpty()) { pending.addLast(a); pendingSamples += a.size } }

            while (!done) {
                if (cancelled()) throw CancellationException("cancelled")
                // 1. feed the decoder (only while the encoder keeps up, so memory stays bounded)
                if (!inEos && pendingSamples < RATE * 4L) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                // 2. drain the decoder into the resampler
                if (!pcmEos) {
                    val o = dec.dequeueOutputBuffer(info, if (inEos) TIMEOUT_US else 1_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        if (enc == null) { // ponytail: a mid-stream rate/channel change after the first frame isn't supported
                            srcRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate); srcCh = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcCh)
                            floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    } else if (o >= 0) {
                        if (enc == null) startEncoder()
                        if (info.size > 0) {
                            val b = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val pcm = if (floatPcm) { val fb = b.asFloatBuffer(); ShortArray(fb.remaining()) { (fb.get(it) * 32767f).toInt().coerceIn(-32768, 32767).toShort() } }
                                      else { val sb = b.asShortBuffer(); ShortArray(sb.remaining()).also { sb.get(it) } }
                            val frames = pcm.size / srcCh
                            push(rs!!.process(remix(pcm, frames, srcCh, outCh), frames))
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (enc == null) startEncoder()
                            push(rs!!.flush()); pcmEos = true
                        }
                    }
                }
                val e = enc ?: continue
                // 3. feed the encoder
                while (!encInEos) {
                    if (pending.isEmpty() && !pcmEos) break
                    val i = e.dequeueInputBuffer(if (pending.isEmpty()) TIMEOUT_US else 0)
                    if (i < 0) break
                    val pts = queuedFrames * 1_000_000L / RATE
                    if (pending.isEmpty()) { e.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); encInEos = true; break }
                    val buf = e.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                    val sb = buf.asShortBuffer()
                    var written = 0
                    while (pending.isNotEmpty() && sb.remaining() >= outCh) {
                        val a = pending.first()
                        val n = minOf(a.size - pendingHead, sb.remaining() / outCh * outCh)
                        sb.put(a, pendingHead, n); written += n; pendingHead += n
                        if (pendingHead == a.size) { pending.removeFirst(); pendingHead = 0 }
                    }
                    pendingSamples -= written
                    e.queueInputBuffer(i, 0, written * 2, pts, 0)
                    queuedFrames += written / outCh
                }
                // 4. drain the encoder into the Ogg writer
                while (true) {
                    val o = e.dequeueOutputBuffer(info, if (encInEos) TIMEOUT_US else 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        e.outputFormat.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                            ?.let(::findOpusHead)?.let { head = it }
                        continue
                    }
                    if (o < 0) break
                    if (info.size > 0) {
                        val b = e.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { b.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { if (head == null) head = findOpusHead(bytes) }
                        else {
                            if (!headersWritten) { ogg.writeHeaders(head ?: error("encoder gave no OpusHead"), comments); headersWritten = true }
                            ogg.writePacket(bytes); packets++
                        }
                    }
                    e.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; break }
                }
            }
            check(packets > 0) { "encoder produced no output" }
            ogg.finish(queuedFrames)
            stream.flush()
            progress(1f)
            return SourceInfo(mime, durUs / 1000, srcCh, srcRate)
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            ex.release()
        }
    }

    /** Duration (ms) of an encoded file as the platform extractor reports it; -1 if unreadable. */
    fun durationMs(fd: FileDescriptor): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(fd)
            audioTrack(ex)?.let { ex.getTrackFormat(it).longOr(MediaFormat.KEY_DURATION, -1000) / 1000 } ?: -1
        } catch (e: Exception) { -1 } finally { ex.release() }
    }

    /** The 19-byte OpusHead (mapping family 0), either bare or inside C2's "AOPUSHDR" config blob. */
    private fun findOpusHead(b: ByteArray): ByteArray? {
        val magic = "OpusHead".toByteArray()
        for (i in 0..b.size - 19) if ((0 until 8).all { b[i + it] == magic[it] }) {
            val family = b.getOrNull(i + 18)?.toInt() ?: 0
            val len = if (family == 0) 19 else 21 + (b[i + 9].toInt() and 0xFF)
            return b.copyOfRange(i, minOf(b.size, i + len))
        }
        return null
    }

    /** Interleaved [inCh] → [outCh] (1 or 2). Surround keeps front left/right. */
    private fun remix(pcm: ShortArray, frames: Int, inCh: Int, outCh: Int): ShortArray = when {
        inCh == outCh -> pcm
        outCh == 1 -> ShortArray(frames) { f -> ((0 until inCh).sumOf { pcm[f * inCh + it].toInt() } / inCh).toShort() }
        inCh == 1 -> ShortArray(frames * 2) { pcm[it / 2] }
        else -> ShortArray(frames * 2) { pcm[(it / 2) * inCh + it % 2] }
    }

    private fun audioTrack(ex: MediaExtractor) =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

    private fun MediaFormat.intOr(k: String, d: Int) = if (containsKey(k)) getInteger(k) else d
    private fun MediaFormat.longOr(k: String, d: Long) = if (containsKey(k)) getLong(k) else d
}
