package app.mfbooks.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.mfbooks.data.AutoRewind
import app.mfbooks.data.Book
import app.mfbooks.data.Chapter
import app.mfbooks.data.Track

/**
 * One playlist item in book time (§5.1). Items are 1:1 with chapters; [startInBookMs] / [startInTrackMs] already include
 * any skip-start trim, so `positionInBook = startInBookMs + player.currentPosition`.
 */
data class ItemSpan(val startInBookMs: Long, val durationMs: Long, val trackIndex: Int, val startInTrackMs: Long) {
    val endInBookMs get() = startInBookMs + durationMs
}

/** Extras keys on every chapter item's [MediaMetadata.extras]. */
object ItemExtras {
    const val BOOK_ID = "mfbooks.bookId"
    const val CHAPTER = "mfbooks.chapter"
    const val START_IN_BOOK = "mfbooks.startInBookMs"
    const val BOOK_DURATION = "mfbooks.bookDurationMs"
    const val TRACK = "mfbooks.track"
    const val START_IN_TRACK = "mfbooks.startInTrackMs"
}

fun coverUri(bookId: String): Uri = "content://app.mfbooks.covers/$bookId".toUri()

fun chapterMediaId(bookId: String, chapter: Int) = "$bookId/$chapter"

/** The one position-in-book helper. */
fun positionInBook(spans: List<ItemSpan>, index: Int, positionInItemMs: Long): Long =
    (spans.getOrNull(index)?.startInBookMs ?: 0L) + positionInItemMs

/** Book time → (item index, offset in item), clamped to [0, book end]. A position in a trimmed gap snaps to the next item. */
fun locate(spans: List<ItemSpan>, bookMs: Long): Pair<Int, Long> {
    if (spans.isEmpty()) return 0 to 0L
    if (bookMs <= spans.first().startInBookMs) return 0 to 0L
    if (bookMs >= spans.last().endInBookMs) return spans.lastIndex to spans.last().durationMs
    val i = spans.indexOfLast { it.startInBookMs <= bookMs }
    val off = bookMs - spans[i].startInBookMs
    return if (off >= spans[i].durationMs && i < spans.lastIndex) i + 1 to 0L else i to off
}

/** Seconds skip in book time (§5.6): crosses chapter boundaries, clamped at both book ends. */
fun seekByMs(spans: List<ItemSpan>, index: Int, positionInItemMs: Long, deltaMs: Long): Pair<Int, Long> =
    locate(spans, positionInBook(spans, index, positionInItemMs) + deltaMs)

/** Saved (track, position in track) → (item index, offset). */
fun locateInTrack(spans: List<ItemSpan>, trackIndex: Int, positionInTrackMs: Long): Pair<Int, Long> {
    val inTrack = spans.withIndex().filter { it.value.trackIndex == trackIndex }
    if (inTrack.isEmpty()) return 0 to 0L
    val (i, s) = inTrack.lastOrNull { it.value.startInTrackMs <= positionInTrackMs } ?: inTrack.first()
    return i to (positionInTrackMs - s.startInTrackMs).coerceIn(0L, s.durationMs)
}

/** Spans for [chapters]; skip start/end trims only the first/last chapter of each file (§5.1). */
fun buildSpans(chapters: List<Chapter>, skipStartMs: Long = 0, skipEndMs: Long = 0): List<ItemSpan> =
    chapters.mapIndexed { i, c ->
        var trimS = if (i == 0 || chapters[i - 1].trackIndex != c.trackIndex) skipStartMs else 0
        var trimE = if (i == chapters.lastIndex || chapters[i + 1].trackIndex != c.trackIndex) skipEndMs else 0
        if (c.durationMs - trimS - trimE < 1_000) { trimS = 0; trimE = 0 }
        ItemSpan(c.startInBookMs + trimS, c.durationMs - trimS - trimE, c.trackIndex, c.startInTrackMs + trimS)
    }

/** §1.6 auto-rewind table. */
fun autoRewindMs(pauseMs: Long, level: AutoRewind): Long {
    if (level == AutoRewind.OFF || pauseMs < 0) return 0
    // AUTO: smooth curve, 1.2·√(pause s), capped 1…60 s (10 s → 4 s, 1 min → 9 s, 5 min → 21 s, ≥ 42 min → 60 s).
    if (level == AutoRewind.AUTO) return (kotlin.math.sqrt(pauseMs / 1000.0) * 1.2).coerceIn(1.0, 60.0).times(1000).toLong()
    val (small, medium) = when {
        pauseMs < 1_000 -> 1 to 1
        pauseMs < 10_000 -> 1 to 2
        pauseMs < 60_000 -> 2 to 5
        pauseMs < 120_000 -> 5 to 10
        pauseMs < 300_000 -> 7 to 15
        pauseMs < 600_000 -> 10 to 20
        pauseMs < 1_200_000 -> 12 to 25
        else -> 15 to 30
    }
    return (if (level == AutoRewind.SMALL) small else medium) * 1_000L
}

/** Books without chapter rows still play: one chapter per track. */
fun chaptersOrTracks(bookId: String, chapters: List<Chapter>, tracks: List<Track>): List<Chapter> {
    if (chapters.isNotEmpty()) return chapters
    var start = 0L
    return tracks.map { t ->
        Chapter(bookId, t.index, t.title ?: t.fileName.substringBeforeLast('.'), t.index, 0, t.durationMs, start)
            .also { start += t.durationMs }
    }
}

/** Book → playlist (§5.1). [spans] from [buildSpans] over the same [chapters]. */
fun buildMediaItems(book: Book, chapters: List<Chapter>, tracks: List<Track>, spans: List<ItemSpan>): List<MediaItem> {
    val byIndex = tracks.associateBy { it.index }
    return chapters.mapIndexedNotNull { i, c ->
        val track = byIndex[c.trackIndex] ?: return@mapIndexedNotNull null
        val s = spans[i]
        val lastInTrack = i == chapters.lastIndex || chapters[i + 1].trackIndex != c.trackIndex
        val untrimmedEnd = s.startInTrackMs + s.durationMs >= c.startInTrackMs + c.durationMs
        val end = if (lastInTrack && untrimmedEnd) C.TIME_END_OF_SOURCE else s.startInTrackMs + s.durationMs
        val extras = Bundle().apply {
            putString(ItemExtras.BOOK_ID, book.id)
            putInt(ItemExtras.CHAPTER, i)
            putLong(ItemExtras.START_IN_BOOK, s.startInBookMs)
            putLong(ItemExtras.BOOK_DURATION, book.durationMs)
            putInt(ItemExtras.TRACK, s.trackIndex)
            putLong(ItemExtras.START_IN_TRACK, s.startInTrackMs)
        }
        val md = MediaMetadata.Builder()
            .setTitle(c.title).setArtist(book.author).setAlbumTitle(book.title).setAlbumArtist(book.author)
            .setDisplayTitle(c.title).setSubtitle(book.title)
            .setArtworkUri(book.coverUri?.let { coverUri(book.id) })
            .setTrackNumber(i + 1).setTotalTrackCount(chapters.size)
            .setDurationMs(s.durationMs)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true).setIsBrowsable(false)
            .setExtras(extras).build()
        val b = MediaItem.Builder().setMediaId(chapterMediaId(book.id, i)).setUri(track.uri).setMediaMetadata(md)
        if (s.startInTrackMs > 0 || end != C.TIME_END_OF_SOURCE)
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startInTrackMs).setEndPositionMs(end).build()
            )
        b.build()
    }
}
