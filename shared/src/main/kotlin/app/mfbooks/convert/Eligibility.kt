package app.mfbooks.convert

/** §4.6 eligibility, widened to AAC: MP3 or AAC-in-MP4 above 50 kb/s. Opus/Vorbis/FLAC/etc. are never touched. */
object Eligibility {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "mp4", "aac")
    const val MIN_KBPS = 50
    const val BITRATE = 32_000

    /** Average bitrate from size and duration (bits/ms == kb/s). Embedded art inflates it a little; fine for a > 50 cut-off. */
    fun kbps(sizeBytes: Long, durationMs: Long) = if (durationMs > 0) sizeBytes * 8 / durationMs else 0

    fun isEligible(fileName: String, sizeBytes: Long, durationMs: Long) =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS && kbps(sizeBytes, durationMs) > MIN_KBPS

    /** Opus at 32 kb/s ≈ 4 kB/s plus ~2 % Ogg framing. */
    fun estimatedOutBytes(durationMs: Long) = durationMs * (BITRATE / 8) / 1000 * 102 / 100
}
