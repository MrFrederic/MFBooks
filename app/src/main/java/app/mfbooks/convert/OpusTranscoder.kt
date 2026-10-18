package app.mfbooks.convert

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

/** What the source turned out to be. */
class SourceInfo(val mime: String, val durationMs: Long, val channels: Int, val sampleRate: Int)

/**
 * MP3/AAC → Ogg/Opus with platform APIs only (§4.6 steps 3–5): MediaExtractor + MediaCodec decode,
 * [Resampler] to 48 kHz, MediaCodec Opus encoder, [OggOpusWriter] (not MediaMuxer OGG: see there). Blocking.
 *
 * Speed: the platform Opus encoder is single-threaded and is the bottleneck, so a file is cut into time chunks
 * (≤ 10 min) and up to [maxParallel] run at once, each with its own extractor → decoder → resampler → encoder
 * (two threads: feed, drain). A chunk after the first starts [WARMUP] early and drops those packets, so the
 * spliced stream has the exact single-pass timeline (same 20 ms packet grid, same length); only the encoder's
 * adaptive state differs for a few frames after a splice (measured: no SNR loss vs. one pass).
 * Chunks spool their packets into the work dir; a finished chunk survives a stop, so a resumed run (pause, job
 * stopped by the system, FGS timeout) only redoes the unfinished ones. The Ogg file is written at the end.
 */
object OpusTranscoder {
    const val BITRATE = 32_000
    private const val RATE = 48_000
    private const val FRAME = 960                      // the encoder's default 20 ms packet
    private const val WARMUP = 50L * FRAME            // 1 s of encoder history before a splice, discarded
    private const val PREROLL_US = 500_000L            // decoder + resampler warm-up before that
    private const val TIMEOUT_US = 10_000L
    private const val MIN_CHUNK_US = 2 * 60_000_000L
    private const val MAX_CHUNK_US = 10 * 60_000_000L
    private const val TAG = "ConvertPerf"

    /** Opus encoders in parallel: each chunk keeps ~2 cores busy (encoder + decoder/resampler). */
    val maxParallel get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

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
     * [comments] become the file's OpusTags. [progress] gets 0..1 (on the calling thread, ~4×/s);
     * [cancelled] is polled and aborts with CancellationException. [workDir] holds the chunks: pass the same
     * directory again to resume, delete it after success (null = a temporary one, deleted here).
     */
    fun transcode(
        src: FileDescriptor, dst: FileDescriptor, comments: List<Pair<String, String>>,
        progress: (Float) -> Unit, cancelled: () -> Boolean, workDir: File? = null, parallel: Int = maxParallel,
    ): SourceInfo {
        val t0 = SystemClock.elapsedRealtime()
        // Positional reads (pread) through our own read-ahead: thread-safe for several extractors on one fd, and
        // no tiny per-sample reads through FUSE. Not closed here: the caller owns the descriptor.
        val channel = FileInputStream(src).channel
        val probe = MediaExtractor()
        val track: Int; val inFmt: MediaFormat; val firstUs: Long
        try {
            probe.setDataSource(PreadSource(channel))
            track = audioTrack(probe) ?: error("no audio track")
            inFmt = probe.getTrackFormat(track)
            probe.selectTrack(track)
            firstUs = probe.sampleTime.takeIf { it != -1L } ?: 0 // may be negative: an edit list trimming encoder priming
        } finally { probe.release() }
        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = inFmt.longOr(MediaFormat.KEY_DURATION, 0)
        val par = parallel.coerceAtLeast(1)
        // MP3 chunks start by walking frames from the file start (~25 µs each), so MP3 gets one chunk per worker.
        val maxChunkUs = if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG) Long.MAX_VALUE else MAX_CHUNK_US
        val chunkUs = (durUs / par).coerceIn(MIN_CHUNK_US, maxChunkUs)
        val n = if (durUs <= 0) 1 else ((durUs + chunkUs - 1) / chunkUs).toInt().coerceAtLeast(1)
        val total48 = durUs * RATE / 1_000_000
        val bounds = LongArray(n + 1) { k -> if (k == n) Long.MAX_VALUE else total48 * k / n / FRAME * FRAME }

        val dir = workDir ?: File.createTempFile("opus-", ".d").also { it.delete() }
        dir.mkdirs()
        val plan = File(dir, "plan")
        val planText = "v1 $firstUs ${bounds.joinToString(",")}"
        if (!plan.exists() || plan.readText() != planText) { dir.listFiles()?.forEach { it.delete() }; plan.writeText(planText) }
        fun done(k: Int) = File(dir, "$k.done")
        val todo = (0 until n).filter { !done(it).exists() }
        val resumed = n - todo.size

        val error = AtomicReference<Throwable?>()
        val stop = { error.get() != null }
        val next = AtomicInteger()
        val running = ConcurrentHashMap<Int, Segment>()
        val doneSamples = AtomicLong((0 until n).filter { done(it).exists() }.sumOf { minOf(bounds[it + 1], total48) - bounds[it] })
        var encCap = 0
        /** A codec can fail transiently (e.g. memory pressure in the codec process): redo the chunk, twice at most. */
        fun runChunk(k: Int): Segment {
            var attempt = 0
            while (true) {
                var seg: Segment? = null
                try {
                    seg = Segment(k, bounds[k], bounds[k + 1], channel, track, firstUs, SpoolSink(File(dir, "$k.pk")), stop)
                    running[k] = seg
                    seg.run()
                    return seg
                } catch (e: Exception) {
                    if (stop() || ++attempt >= 3) throw e
                    Log.w(TAG, "chunk $k failed (attempt $attempt), retrying: ${describe(e)}", e)
                } finally { seg?.close(); running -= k }
            }
        }
        try {
            val pool = List(minOf(par, todo.size)) { w ->
                thread(name = "opus-$w") {
                    try {
                        while (!stop()) {
                            val k = todo.getOrNull(next.getAndIncrement()) ?: break
                            val seg = runChunk(k)
                            if (stop()) break
                            if (k == 0) File(dir, "head").writeBytes(seg.head ?: error("encoder gave no OpusHead"))
                            encCap = seg.encCap
                            File(dir, "$k.tmp").apply { writeText("${seg.encStart + seg.queued}"); renameTo(done(k)) }
                            doneSamples.addAndGet(minOf(bounds[k + 1], total48) - bounds[k])
                        }
                    } catch (e: Throwable) { if (e !is CancellationException) Log.w(TAG, "chunk failed: ${describe(e)}", e); error.compareAndSet(null, e) }
                }
            }
            var last = 0L
            while (true) {
                (pool.firstOrNull { it.isAlive } ?: break).join(250)
                if (cancelled()) error.compareAndSet(null, CancellationException("cancelled"))
                val now = SystemClock.elapsedRealtime()
                if (total48 > 0 && now - last >= 250) {
                    last = now
                    progress(((doneSamples.get() + running.values.sumOf { it.fed.get() }).toFloat() / total48).coerceIn(0f, 0.99f))
                }
            }
            error.get()?.let { throw it }

            // All chunks done: one Ogg stream in timeline order.
            val stream = BufferedOutputStream(FileOutputStream(dst), 1 shl 16)
            val ogg = OggOpusWriter(stream)
            ogg.writeHeaders(File(dir, "head").readBytes(), comments)
            var packets = 0L
            for (k in 0 until n) DataInputStream(BufferedInputStream(File(dir, "$k.pk").inputStream(), 1 shl 16)).use { inp ->
                while (true) {
                    val len = try { inp.readInt() } catch (e: EOFException) { break }
                    ogg.writePacket(ByteArray(len).also { inp.readFully(it) }); packets++
                }
            }
            check(packets > 0) { "encoder produced no output" }
            val frames = done(n - 1).readText().trim().toLong()
            ogg.finish(frames)
            stream.flush()
            progress(1f)
            val wall = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "${frames / RATE}s audio in ${wall}ms = ${"%.1f".format(frames.toDouble() / RATE / (wall / 1000.0))}x realtime, " +
                "$n chunk(s) ($resumed resumed), $par parallel, $mime, enc buffer $encCap samples")
            if (workDir == null) dir.deleteRecursively()
            return SourceInfo(mime, durUs / 1000, inFmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2), inFmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100))
        } catch (e: Throwable) {
            error.compareAndSet(null, e) // stops the pool
            if (workDir == null) dir.deleteRecursively() // a caller-owned dir keeps its finished chunks for a retry/resume
            throw e
        }
    }

    private class SpoolSink(f: File) {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(f), 1 shl 16))
        fun packet(p: ByteArray) { out.writeInt(p.size); out.write(p) }
        fun close() = out.close()
    }

    /**
     * Output samples [from, until) of the 48 kHz timeline. The encoder starts at [encStart] (= from − WARMUP) and
     * a packet is kept when its position encStart + (samples before it) falls in [from, until).
     */
    private class Segment(
        val k: Int, val from: Long, val until: Long, val channel: FileChannel, val track: Int, val firstUs: Long,
        val sink: SpoolSink, val globalStop: () -> Boolean,
    ) {
        private val failed = AtomicReference<Throwable?>()
        private fun stop() = globalStop() || failed.get() != null
        val encStart = if (from == 0L) 0L else from - WARMUP
        val fed = AtomicLong()                         // samples of [from, until) handed to the encoder (progress)
        @Volatile var queued = 0L                      // frames queued to the encoder
        @Volatile var head: ByteArray? = null
        var srcRate = 0; var srcCh = 0; @Volatile var encCap = 0
        private val ex = MediaExtractor()
        private val dec: MediaCodec
        private lateinit var enc: MediaCodec
        private val encReady = java.util.concurrent.CountDownLatch(1)

        init {
            ex.setDataSource(PreadSource(channel))
            ex.selectTrack(track)
            val f = ex.getTrackFormat(track)
            srcRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100); srcCh = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            if (encStart > 0) {
                val target = firstUs + (encStart * 1_000_000 / RATE - PREROLL_US).coerceAtLeast(0)
                // MP3 seeks are estimates (Xing TOC / bitrate) and report the estimate as the time; walking the frames
                // is exact. MP4/ADTS seeks use the sample table / frame index, so they are exact.
                if (f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                    while (ex.sampleTime in 0 until target) if (!ex.advance()) break
                } else ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!).apply { configure(f, null, null, 0); start() }
        }

        /** Feeds on this thread, drains on another; returns when both are done (or [stop]). */
        fun run() {
            val drainer = thread(name = "opus-drain-$k") { guard { encReady.await(); if (!stop()) drain() } }
            guard { feed() }
            drainer.join()
            failed.get()?.let { throw it }
        }

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (e: Throwable) { failed.compareAndSet(null, e) } finally { if (failed.get() != null) encReady.countDown() }
        }

        private var closed = false
        fun close() {
            if (closed) return
            closed = true
            runCatching { dec.stop() }; runCatching { dec.release() }
            if (::enc.isInitialized) { runCatching { enc.stop() }; runCatching { enc.release() } }
            ex.release()
            runCatching { sink.close() }
        }

        // ---- feed thread: extractor → decoder → resampler → encoder input

        private var outCh = 0
        private lateinit var rs: Resampler
        private var floatPcm = false
        private var pcm = ShortArray(0)
        private var skipIn = -1L                       // input frames still to drop before the resampler starts
        private var outPos = 0L                        // global 48 kHz index of the next resampled sample
        private var encIdx = -1
        private var firstInUs = Long.MIN_VALUE         // timestamp of the first sample fed to the decoder
        private var encBuf: ShortBuffer? = null

        private fun startEncoder() {
            outCh = if (srcCh == 1) 1 else 2
            rs = Resampler(srcRate, RATE, outCh)
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, outCh).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                // C2's default input buffer is one 20 ms frame: a binder round trip per frame. Ask for ~0.5 s.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, RATE / 2 * outCh * 2)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            encReady.countDown()
        }

        private fun feed() {
            val info = MediaCodec.BufferInfo()
            val limitUs = if (until == Long.MAX_VALUE) Long.MAX_VALUE else firstUs + until * 1_000_000 / RATE + PREROLL_US
            var inEos = false
            while (true) {
                if (stop()) return
                while (!inEos) { // keep every free decoder input buffer filled
                    val i = dec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (n < 0 || ex.sampleTime > limitUs) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                    else { if (firstInUs == Long.MIN_VALUE) firstInUs = ex.sampleTime; dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!::enc.isInitialized) { // ponytail: a mid-stream rate/channel change after the first frame isn't supported
                        val f = dec.outputFormat
                        srcRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate); srcCh = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcCh)
                        floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    continue
                }
                if (o < 0) continue
                if (!::enc.isInitialized) startEncoder()
                if (info.size > 0) {
                    val b = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val samples = if (floatPcm) info.size / 4 else info.size / 2
                    if (pcm.size < samples) pcm = ShortArray(samples)
                    if (floatPcm) { val fb = b.asFloatBuffer(); for (j in 0 until samples) pcm[j] = (fb.get(j) * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                    else b.asShortBuffer().get(pcm, 0, samples)
                    dec.releaseOutputBuffer(o, false)
                    resample(samples / srcCh)
                } else dec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (skipIn < 0) skipIn = 0 // nothing decoded
                    if (::rs.isInitialized) toEncoder(rs.flush())
                    val i = encIdx.takeIf { it >= 0 } ?: dequeueEncoderInput()
                    if (i < 0) return
                    val written = if (encIdx >= 0) encBuf!!.position() else 0
                    enc.queueInputBuffer(i, 0, written * 2, queued * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued += written / outCh; encIdx = -1
                    return
                }
            }
        }

        /** Places a decoded buffer on the global timeline, drops what lies before this segment, resamples the rest. */
        private fun resample(frames: Int) {
            var off = 0; var len = frames
            if (skipIn < 0) {
                if (encStart == 0L) { skipIn = 0; outPos = 0 }
                else {
                    // Anchor on the decoder's timestamp, then start the resampler on a frame where input and output
                    // grids coincide (multiple of inRate/gcd), so its phase equals the single-pass phase exactly.
                    val a = ((firstInUs - firstUs) * srcRate + 500_000) / 1_000_000
                    val g = Resampler.gcd(srcRate, RATE)
                    val stepIn = (srcRate / g).toLong()
                    val a0 = Math.floorDiv(a + stepIn - 1, stepIn) * stepIn
                    skipIn = a0 - a
                    outPos = a0 / stepIn * (RATE / g)
                }
            }
            if (skipIn > 0) { val d = minOf(skipIn, len.toLong()).toInt(); off += d; len -= d; skipIn -= d }
            if (len == 0) return
            val src = if (off == 0) pcm else pcm.copyOfRange(off * srcCh, (off + len) * srcCh)
            toEncoder(rs.process(remix(src, len, srcCh, outCh), len))
        }

        /** Resampled samples starting at [outPos]: the part from [encStart] on goes into encoder input buffers, filled to the brim. */
        private fun toEncoder(s: ShortArray) {
            val frames = s.size / outCh
            var o = 0
            if (outPos < encStart) o = minOf(frames.toLong(), encStart - outPos).toInt()
            outPos += frames
            fed.set((outPos - from).coerceIn(0, until - from))
            var rem = (frames - o) * outCh; o *= outCh
            while (rem > 0) {
                if (encIdx < 0) {
                    encIdx = dequeueEncoderInput(); if (encIdx < 0) return
                    encBuf = enc.getInputBuffer(encIdx)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    if (encCap == 0) encCap = encBuf!!.capacity()
                }
                val b = encBuf!!
                val c = minOf(rem, b.remaining() / outCh * outCh)
                b.put(s, o, c); o += c; rem -= c
                if (b.remaining() < outCh) {
                    enc.queueInputBuffer(encIdx, 0, b.position() * 2, queued * 1_000_000L / RATE, 0)
                    queued += b.position() / outCh; encIdx = -1
                }
            }
        }

        private fun dequeueEncoderInput(): Int {
            while (!stop()) { val i = enc.dequeueInputBuffer(TIMEOUT_US); if (i >= 0) return i }
            return -1
        }

        // ---- drain thread: encoder output → sink

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            var pos = encStart
            while (!stop()) {
                val o = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    enc.outputFormat.getByteBuffer("csd-0")?.let(::bytes)?.let(::findOpusHead)?.let { head = it }
                    continue
                }
                if (o < 0) continue
                if (info.size > 0) {
                    val b = enc.getOutputBuffer(o)!!
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val p = bytes(b)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { if (head == null) head = findOpusHead(p) }
                    else {
                        if (pos in from until until) sink.packet(p)
                        pos += OggOpusWriter.packetSamples(p)
                    }
                }
                enc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    /** MediaExtractor input with 512 kB read-ahead over pread: the extractor's own reads are a few hundred bytes each. */
    private class PreadSource(private val ch: FileChannel) : MediaDataSource() {
        private val size = ch.size()
        private val cache = ByteBuffer.allocate(512 shl 10)
        private var cacheAt = -1L; private var cacheLen = 0

        override fun getSize() = size
        override fun close() {}
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            if (n > cache.capacity()) return ch.read(ByteBuffer.wrap(buffer, offset, n), position)
            if (cacheAt < 0 || position < cacheAt || position + n > cacheAt + cacheLen) {
                cache.clear()
                var got = 0
                while (got < cache.capacity()) { val r = ch.read(cache, position + got); if (r <= 0) break; got += r }
                cacheAt = position; cacheLen = got
            }
            val start = (position - cacheAt).toInt()
            val m = minOf(n, cacheLen - start)
            if (m <= 0) return -1
            System.arraycopy(cache.array(), start, buffer, offset, m)
            return m
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

    private fun describe(e: Throwable) = if (e is MediaCodec.CodecException)
        "CodecException code=${e.errorCode} transient=${e.isTransient} recoverable=${e.isRecoverable} ${e.diagnosticInfo}" else e.toString()

    private fun bytes(b: ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }

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
