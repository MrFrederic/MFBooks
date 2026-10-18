package app.mfbooks.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookState
import app.mfbooks.data.Chapter
import kotlinx.coroutines.flow.first

/**
 * Browse tree for Android Auto and other browsers (§6.2):
 * root → Continue / New / Library (→ authors → books) / Finished; book → chapters.
 * Ids: [ROOT], [RECENT], "tab/…", "author:<name>", "<bookId>", "<bookId>/<chapter>".
 */
class LibraryTree(private val context: Context) {
    private val dao get() = MFBooksApp.get(context).db.library()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val EMPTY = "empty"
        const val CONTINUE = "tab/continue"
        const val NEW = "tab/new"
        const val LIBRARY = "tab/library"
        const val FINISHED = "tab/finished"
        private const val AUTHOR = "author:"
        private const val GROUP_THRESHOLD = 20

        fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
        }
        val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        val CATEGORY_LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
    }

    fun root(recent: Boolean): MediaItem = folder(if (recent) RECENT else ROOT, "MFBooks", styleExtras(GRID, LIST))

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> {
            if (dao.booksNow().isEmpty()) listOf(emptyItem())
            else listOf(
                folder(CONTINUE, s(R.string.pb_tab_continue), styleExtras(GRID, GRID)),
                folder(NEW, s(R.string.pb_tab_new), styleExtras(GRID, GRID)),
                folder(LIBRARY, s(R.string.pb_tab_library), styleExtras(CATEGORY_LIST, GRID)),
                folder(FINISHED, s(R.string.pb_tab_finished), styleExtras(GRID, GRID)),
            )
        }
        parentId == RECENT -> listOfNotNull(dao.lastPlayed()?.let { bookItem(it) })
        parentId == EMPTY -> emptyList()
        parentId == CONTINUE -> books(dao.booksByState(BookState.STARTED))
        parentId == NEW -> books(dao.booksByState(BookState.NEW))
        parentId == FINISHED -> books(dao.booksByState(BookState.FINISHED))
        parentId == LIBRARY -> authors()
        parentId.startsWith(AUTHOR) -> {
            val name = parentId.removePrefix(AUTHOR)
            books(dao.booksNow().filter { authorOf(it) == name })
        }
        '/' in parentId -> emptyList()
        else -> dao.book(parentId)?.let { chapters(it) } ?: emptyList()
    }

    suspend fun item(id: String): MediaItem? = when {
        id == ROOT || id == RECENT -> root(id == RECENT)
        id == EMPTY -> emptyItem()
        id.startsWith("tab/") || id.startsWith(AUTHOR) ->
            children(ROOT).firstOrNull { it.mediaId == id } ?: authors().firstOrNull { it.mediaId == id }
        '/' in id -> {
            val bookId = id.substringBeforeLast('/')
            dao.book(bookId)?.let { b -> chapters(b).firstOrNull { it.mediaId == id } }
        }
        else -> dao.book(id)?.let { bookItem(it) }
    }

    suspend fun search(query: String): List<MediaItem> = books(dao.search(query.trim()))

    private suspend fun books(list: List<Book>): List<MediaItem> {
        val progress = dao.books().first().associate { it.book.id to it.progress }
        return list.map { bookItem(it, progress[it.id]) }
    }

    private suspend fun authors(): List<MediaItem> {
        val byAuthor = dao.booksNow().groupBy { authorOf(it) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val grouped = byAuthor.size > GROUP_THRESHOLD
        return byAuthor.map { (name, books) ->
            val extras = styleExtras(GRID, GRID)
            if (grouped) extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, name.take(1).uppercase())
            val md = MediaMetadata.Builder().setTitle(name).setSubtitle(context.getString(R.string.pb_books_count, books.size))
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .setExtras(extras).build()
            MediaItem.Builder().setMediaId(AUTHOR + name).setMediaMetadata(md).build()
        }
    }

    private fun authorOf(b: Book) = b.author?.takeIf { it.isNotBlank() } ?: s(R.string.pb_unknown_author)

    suspend fun bookItem(b: Book): MediaItem = bookItem(b, dao.progress(b.id))

    suspend fun bookItem(b: Book, p: BookProgress?): MediaItem {
        val posMs = p?.let { bookPosition(b.id, it) } ?: 0L
        val fraction = if (b.durationMs > 0) (posMs.toDouble() / b.durationMs).coerceIn(0.0, 1.0) else 0.0
        val extras = styleExtras(GRID, LIST)
        val status = when {
            b.state == BookState.FINISHED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            b.state == BookState.NEW && posMs == 0L -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        }
        extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
        if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
            extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, fraction)
        val left = if (b.state == BookState.FINISHED) s(R.string.pb_finished) else leftText(b.durationMs - posMs)
        val md = MediaMetadata.Builder()
            .setTitle(b.title).setArtist(b.author).setAlbumTitle(b.title).setSubtitle(b.author).setDescription(left)
            .setArtworkUri(b.coverUri?.let { coverUri(b.id) })
            .setDurationMs(b.durationMs)
            .setIsBrowsable(true).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(extras).build()
        return MediaItem.Builder().setMediaId(b.id).setMediaMetadata(md).build()
    }

    private suspend fun chapters(b: Book): List<MediaItem> =
        chaptersOrTracks(b.id, dao.chapters(b.id), dao.tracks(b.id)).mapIndexed { i, c -> chapterItem(b, c, i) }

    private fun chapterItem(b: Book, c: Chapter, i: Int): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(c.title).setSubtitle(formatClock(c.durationMs)).setArtist(b.author).setAlbumTitle(b.title)
            .setArtworkUri(b.coverUri?.let { coverUri(b.id) })
            .setTrackNumber(i + 1).setDurationMs(c.durationMs)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()
        return MediaItem.Builder().setMediaId(chapterMediaId(b.id, i)).setMediaMetadata(md).build()
    }

    private suspend fun bookPosition(bookId: String, p: BookProgress): Long {
        val tracks = dao.tracks(bookId)
        return tracks.filter { it.index < p.trackIndex }.sumOf { it.durationMs } + p.positionInTrackMs
    }

    private fun emptyItem(): MediaItem {
        val md = MediaMetadata.Builder().setTitle(s(R.string.pb_empty_library)).setSubtitle(s(R.string.pb_empty_library_sub))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        return MediaItem.Builder().setMediaId(EMPTY).setMediaMetadata(md).build()
    }

    private fun folder(id: String, title: String, extras: Bundle): MediaItem {
        val md = MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).setExtras(extras).build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md).build()
    }

    private fun leftText(ms: Long): String {
        val m = (ms.coerceAtLeast(0) / 60_000).toInt()
        return if (m >= 60) context.getString(R.string.pb_left_hm, m / 60, m % 60) else context.getString(R.string.pb_left_m, m)
    }

    private fun s(id: Int) = context.getString(id)
}

/** 12:34 / 1:02:03 */
fun formatClock(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600; val m = t / 60 % 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Paging helper for onGetChildren / onGetSearchResult. */
fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0 || page < 0) return this
    val from = page.toLong() * pageSize
    if (from >= size) return emptyList()
    return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
}
