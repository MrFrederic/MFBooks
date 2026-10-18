package app.mfbooks.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * One command vocabulary (§5.5) for the phone UI, Android Auto, notification, Tasker intents and widgets.
 * Session commands use action "app.mfbooks.action.<NAME>"; the exported [CommandReceiver] accepts the same actions.
 */
enum class Command {
    PLAY_PAUSE, PLAY, PAUSE, REWIND_SMALL, REWIND_BIG, FWD_SMALL, FWD_BIG,
    PREV_CHAPTER, NEXT_CHAPTER, PREV_FILE, NEXT_FILE, PREV_BOOK, NEXT_BOOK,
    ADD_BOOKMARK, NEXT_BOOKMARK, TOGGLE_SPEED, SET_SPEED, CYCLE_SPEED,
    SLEEP_ON, SLEEP_OFF, SLEEP_EXTEND, SLEEP_CYCLE, EXIT,
    /** Seconds skip by an explicit amount: extra [EXTRA_MS] (negative = rewind). */
    SEEK_BY,
    // Used by PlayerConnection (and accepted from Tasker too).
    /** [EXTRA_BOOK_ID], optional [EXTRA_CHAPTER], [EXTRA_FROM_START]. */
    PLAY_BOOK,
    /** [EXTRA_QUERY]; empty = last played book. */
    PLAY_SEARCH,
    /** Absolute position in book time: [EXTRA_MS]. */
    SEEK_IN_BOOK,
    /** [EXTRA_TRACK] + [EXTRA_MS] (position in that track), for bookmarks / history. */
    JUMP_TO,
    /** [EXTRA_CHAPTER]. */
    SET_CHAPTER,
    /** [EXTRA_ENABLED]. */
    SET_SKIP_SILENCE,
    /** [EXTRA_MB] (0..1500). */
    SET_BOOST,
    /** [EXTRA_BANDS] (FloatArray of 5 dB values), [EXTRA_BALANCE], [EXTRA_MONO], [EXTRA_PITCH]. */
    SET_EQ;

    val action get() = "app.mfbooks.action.$name"
    fun session() = SessionCommand(action, Bundle.EMPTY)

    companion object {
        const val EXTRA_SPEED = "speed"     // Float, for SET_SPEED
        const val EXTRA_MS = "ms"           // Long, for SEEK_BY
        const val EXTRA_MINUTES = "minutes" // Int, for SLEEP_ON (-1 = end of chapter)
        const val EXTRA_TITLE = "title"     // String?, for ADD_BOOKMARK
        const val EXTRA_QUIET = "quiet"     // Boolean: caller shows its own confirmation
        const val EXTRA_NOTE = "note"       // String?, for ADD_BOOKMARK
        const val EXTRA_AS_DEFAULT = "asDefault" // Boolean, for SET_SPEED
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER = "chapter" // Int
        const val EXTRA_FROM_START = "fromStart"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TRACK = "track"     // Int
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MB = "mb"           // Int
        const val EXTRA_BANDS = "bands"     // FloatArray
        const val EXTRA_BALANCE = "balance" // Float -1..1
        const val EXTRA_MONO = "mono"
        const val EXTRA_PITCH = "pitch"     // Float
        fun fromAction(action: String?) = entries.firstOrNull { it.action == action }
    }
}
