@file:OptIn(UnstableApi::class)

package app.mfbooks.text

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import app.mfbooks.convert.Resampler
import app.mfbooks.data.Track
import app.mfbooks.server.Server
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder

private const val RATE = 16_000
private const val SAMPLES_PER_MS = RATE / 1000
private const val PREROLL_US = 500_000L
private const val TIMEOUT_US = 10_000L

/**
 * Book time → 16 kHz mono PCM, across files. Keeps the decoder open between adjacent reads so a job walks a file once.
 * A file that ends before its stored duration is padded with silence, so the timeline stays the one [AudioSpans] defines.
 * Blocking; call off the main thread. [cancelled] is polled and aborts with CancellationException.
 */
class PcmReader(private val context: Context, private val tracks: List<Track>, private val cancelled: () -> Boolean = { false }) : Closeable {
    private val durations = tracks.map { it.durationMs }
    private var decoder: TrackDecoder? = null
    private var decoderTrack = -1
    private var decoderSample = -1L

    fun read(fromMs: Long, untilMs: Long): ShortArray {
        val spans = AudioSpans.of(durations, fromMs, untilMs)
        val out = ShortArray(spans.sumOf { ((it.untilInTrackMs - it.fromInTrackMs) * SAMPLES_PER_MS).toInt() })
        var at = 0
        for (s in spans) {
            val n = ((s.untilInTrackMs - s.fromInTrackMs) * SAMPLES_PER_MS).toInt()
            val first = s.fromInTrackMs * SAMPLES_PER_MS
            if (decoderTrack != s.trackIndex || decoderSample != first) {
                decoder?.close()
                decoder = null
                decoder = TrackDecoder(context, tracks[s.trackIndex], s.fromInTrackMs, cancelled)
                decoderTrack = s.trackIndex
            }
            val d = decoder!!
            var got = 0
            while (got < n) { val r = d.read(out, at + got, n - got); if (r <= 0) break; got += r }
            decoderSample = first + got
            if (got < n) { d.close(); decoder = null; decoderTrack = -1 } // ended early: the rest of the span stays silent
            at += n
        }
        return out
    }

    override fun close() { decoder?.close(); decoder = null }
}

/** One file from [startMs]: the extract → decode → downmix → [Resampler] steps of OpusTranscoder, without the encoder. */
private class TrackDecoder(context: Context, track: Track, startMs: Long, private val cancelled: () -> Boolean) : Closeable {
    private val ex = MediaExtractorCompat(context) // the player's parser: the platform one finds no audio in some long m4b files
    private var fd: ParcelFileDescriptor? = null
    private var dec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var srcRate = 44100; private var srcCh = 2; private var floatPcm = false
    private var rs: Resampler? = null
    private var dropUs = 0L
    private var dropFrames = -1L
    private var inEos = false; private var done = false
    private var pcm = ShortArray(0)
    private var pending = ShortArray(0); private var pendingAt = 0

    init {
        try {
            if (track.isRemote) ex.setDataSource(track.uri, Server.authHeader()?.let { mapOf("Authorization" to it) } ?: emptyMap())
            else {
                fd = context.contentResolver.openFileDescriptor(Uri.parse(track.uri), "r") ?: throw IOException("can't open ${track.fileName}")
                ex.setDataSource(fd!!.fileDescriptor)
            }
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("no audio track in ${track.fileName}")
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            val first = ex.sampleTime.takeIf { it != -1L } ?: 0
            val target = first + startMs * 1000
            if (startMs > 0) {
                val seekUs = (target - PREROLL_US).coerceAtLeast(first)
                // ponytail: MP3 seeks are estimates, so local MP3 walks the frames like OpusTranscoder. A remote MP3 keeps the
                // estimate, because walking would download everything before the seek point.
                if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG && !track.isRemote) {
                    while (ex.sampleTime != -1L && ex.sampleTime < seekUs) { check(); if (!ex.advance()) break }
                } else ex.seekTo(seekUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            }
            val anchor = ex.sampleTime.takeIf { it != -1L } ?: target
            dropUs = (target - anchor).coerceAtLeast(0)
            dec = MediaCodec.createDecoderByType(mime).apply { configure(f, null, null, 0); start() }
        } catch (e: Throwable) { close(); throw e }
    }

    private fun check() { if (cancelled()) throw CancellationException("cancelled") }

    /** Up to [len] samples into [dst]; 0 at the end of the file. */
    fun read(dst: ShortArray, off: Int, len: Int): Int {
        while (pendingAt >= pending.size) {
            if (done) return 0
            pump()
        }
        val n = minOf(len, pending.size - pendingAt)
        System.arraycopy(pending, pendingAt, dst, off, n)
        pendingAt += n
        return n
    }

    private fun pump() {
        check()
        val d = dec!!
        while (!inEos) {
            val i = d.dequeueInputBuffer(0)
            if (i < 0) break
            val n = ex.readSampleData(d.getInputBuffer(i)!!, 0)
            if (n < 0) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
            else { d.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
        }
        val o = d.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (rs == null) { // ponytail: as in OpusTranscoder, a format change after the first frame isn't supported
                val f = d.outputFormat
                srcRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate); srcCh = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcCh)
                floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
            }
            return
        }
        if (o < 0) return
        if (info.size > 0) {
            val b = d.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
            b.position(info.offset); b.limit(info.offset + info.size)
            val samples = if (floatPcm) info.size / 4 else info.size / 2
            if (pcm.size < samples) pcm = ShortArray(samples)
            if (floatPcm) { val fb = b.asFloatBuffer(); for (j in 0 until samples) pcm[j] = (fb.get(j) * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            else b.asShortBuffer().get(pcm, 0, samples)
            d.releaseOutputBuffer(o, false)
            resample(samples / srcCh)
        } else d.releaseOutputBuffer(o, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            rs?.flush()?.let(::enqueue)
            done = true
        }
    }

    /** Downmix, drop what lies before the seek target, resample. ponytail: placed by counting from the seek, good to a few ms. */
    private fun resample(frames: Int) {
        if (dropFrames < 0) dropFrames = dropUs * srcRate / 1_000_000
        val skip = minOf(dropFrames, frames.toLong()).toInt()
        dropFrames -= skip
        if (skip == frames) return
        val mono = ShortArray(frames - skip) { f ->
            var acc = 0
            for (c in 0 until srcCh) acc += pcm[(f + skip) * srcCh + c]
            (acc / srcCh).toShort()
        }
        val r = rs ?: Resampler(srcRate, RATE, 1).also { rs = it }
        enqueue(r.process(mono, mono.size))
    }

    private fun enqueue(out: ShortArray) {
        pending = if (pendingAt >= pending.size) out else pending.copyOfRange(pendingAt, pending.size) + out
        pendingAt = 0
    }

    override fun close() {
        dec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        dec = null
        runCatching { ex.release() }
        runCatching { fd?.close() }
        fd = null
    }

    private fun MediaFormat.intOr(k: String, d: Int) = if (containsKey(k)) getInteger(k) else d
}
