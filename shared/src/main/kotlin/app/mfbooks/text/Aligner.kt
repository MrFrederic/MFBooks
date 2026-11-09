package app.mfbooks.text

object Approximate {
    /** Knots at matched chapters when at least 70% of [chapters] match the TOC, otherwise only the book's two ends. */
    fun build(doc: BookTextDoc, textSha: String, tracks: List<SyncTrack>, chapters: List<ChapterRef>, user: List<UserKnot> = emptyList()): SyncDoc =
        TODO("shared agent")
}

object Aligner {
    /**
     * [words] are sorted by time; [coveredMs] are the stretches of audio already transcribed. Audio outside them falls back to
     * [Approximate] knots with [RangeKind.APPROX] ranges, so a partial transcript gives a usable map. [user] knots always win.
     */
    fun align(
        doc: BookTextDoc, textSha: String, words: List<TimedWord>, coveredMs: List<IntRange>,
        tracks: List<SyncTrack>, chapters: List<ChapterRef>, engine: String, user: List<UserKnot> = emptyList(),
    ): SyncDoc = TODO("shared agent")
}

/** Runtime lookup. Binary search over two [IntArray]s, cheap enough to call every frame. */
class SyncMap private constructor() {
    /** Text position for book time [ms]. Kind [RangeKind.NONE] means the narration isn't in the text here. */
    fun at(ms: Int): SyncPosition = TODO("shared agent")

    /** Book time at which text position [char] is read. */
    fun msAt(char: Int): Int = TODO("shared agent")

    companion object {
        /**
         * Null if [current] isn't the audio [doc] was made from: track count differs, or a duration is off by over 1.5 s.
         * Knots are rebased per track on [current] so small conversion errors don't add up across files.
         */
        fun of(doc: SyncDoc, current: List<SyncTrack>): SyncMap? = TODO("shared agent")
    }
}

fun SyncDoc.withUserKnot(knot: UserKnot): SyncDoc = TODO("shared agent")

/** Fraction of the text with word-level timing, 0..1. */
fun SyncDoc.coverage(): Float = TODO("shared agent")
