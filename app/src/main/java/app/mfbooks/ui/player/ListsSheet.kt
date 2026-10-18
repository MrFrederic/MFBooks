@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.player

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Track
import app.mfbooks.playback.NowPlaying
import app.mfbooks.ui.book.ChapterRow
import app.mfbooks.ui.components.EmptyPlaceholder
import app.mfbooks.ui.components.formatClock
import kotlinx.coroutines.launch

internal enum class ListTab(val label: String, val icon: ImageVector) {
    Chapters("Chapters", Icons.AutoMirrored.Rounded.FormatListBulleted), Files("Files", Icons.Rounded.AudioFile),
    Bookmarks("Bookmarks", Icons.Rounded.Bookmarks), History("History", Icons.Rounded.History),
}

/**
 * Chapters · Files (multi-file books only) · Bookmarks · History, switched by a connected button group.
 * [chaptersOnly] (controls locked): just the chapter list, no tabs.
 */
@Composable
internal fun ListsSheet(np: NowPlaying, tracks: List<Track>, initialTab: ListTab, chaptersOnly: Boolean, onDismiss: () -> Unit) {
    val app = MFBooksApp.get(LocalContext.current)
    val player = app.player
    val bookId = np.book?.id ?: return
    val scope = rememberCoroutineScope()
    val tabs = if (chaptersOnly) listOf(ListTab.Chapters) else ListTab.entries.filter { it != ListTab.Files || tracks.size > 1 }
    var tab by remember { mutableStateOf(initialTab.takeIf { it in tabs } ?: ListTab.Chapters) }
    val bookmarks by app.db.userData().bookmarks(bookId).collectAsStateWithLifecycle(emptyList())
    val history by app.db.userData().history(bookId).collectAsStateWithLifecycle(emptyList())

    fun chapterOf(track: Int, pos: Long) = np.chapters.lastOrNull { it.trackIndex == track && it.startInTrackMs <= pos }
    /** Position in the whole book for a (track, position-in-track) pair. */
    fun bookPos(track: Int, pos: Long): Long = chapterOf(track, pos)?.let { it.startInBookMs + pos - it.startInTrackMs } ?: pos

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            if (tabs.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                tabs.forEachIndexed { i, t ->
                    ToggleButton(
                        tab == t, { tab = t }, Modifier.weight(1f).semantics { role = Role.Tab },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        if (tab == t && tabs.size <= 3) Icon(t.icon, null, Modifier.padding(end = 6.dp).size(18.dp))
                        Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val pad = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp)
            when (tab) {
                ListTab.Chapters -> {
                    val state = rememberLazyListState()
                    LaunchedEffect(Unit) { state.scrollToItem((np.chapterIndex - 2).coerceAtLeast(0)) }
                    LazyColumn(state = state, contentPadding = pad) {
                        itemsIndexed(np.chapters) { i, c ->
                            ChapterRow(c, i == np.chapterIndex, i < np.chapterIndex, np.isPlaying && i == np.chapterIndex) { player.setChapter(i); onDismiss() }
                        }
                    }
                }
                ListTab.Files -> {
                    val state = rememberLazyListState()
                    val cur = np.chapter?.trackIndex ?: 0
                    LaunchedEffect(Unit) { state.scrollToItem((cur - 2).coerceAtLeast(0)) }
                    LazyColumn(state = state, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        itemsIndexed(tracks, key = { _, t -> t.index }) { i, t ->
                            SegmentedListItem(
                                onClick = { player.jumpTo(t.index, 0); onDismiss() },
                                shapes = ListItemDefaults.segmentedShapes(i, tracks.size),
                                selected = t.index == cur,
                                supportingContent = { Text("${t.index + 1} of ${tracks.size} · ${formatClock(t.durationMs)}", style = tnum) },
                            ) { Text(t.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                ListTab.Bookmarks -> LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    if (bookmarks.isEmpty()) item { EmptyPlaceholder(Icons.Rounded.BookmarkBorder, "No bookmarks yet", "Long-press the bookmark button in the player to save a spot.") }
                    itemsIndexed(bookmarks, key = { _, b -> b.id }) { i, b ->
                        SegmentedListItem(
                            onClick = { player.jumpTo(b.trackIndex, b.positionMs); onDismiss() },
                            shapes = ListItemDefaults.segmentedShapes(i, bookmarks.size),
                            supportingContent = { Text(listOfNotNull(formatClock(bookPos(b.trackIndex, b.positionMs)), b.note).joinToString(" · "), maxLines = 2, style = tnum) },
                            trailingContent = {
                                IconButton({ scope.launch { app.db.userData().deleteBookmark(b) } }) { Icon(Icons.Rounded.Delete, "Delete bookmark ${b.title}") }
                            },
                        ) { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                ListTab.History -> LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    if (history.isEmpty()) item { EmptyPlaceholder(Icons.Rounded.History, "No history yet", "Jumps, seeks and pauses show up here so you can undo them.") }
                    itemsIndexed(history, key = { _, e -> e.id }) { i, e ->
                        val ch = chapterOf(e.trackIndex, e.positionMs)
                        val ago = DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        SegmentedListItem(
                            onClick = { player.jumpTo(e.trackIndex, e.positionMs); onDismiss() },
                            shapes = ListItemDefaults.segmentedShapes(i, history.size),
                            supportingContent = { Text("Chapter ${(ch?.index ?: e.trackIndex) + 1} · ${formatClock(bookPos(e.trackIndex, e.positionMs))} · $ago", style = tnum) },
                        ) { Text(e.action.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
        }
    }
}
