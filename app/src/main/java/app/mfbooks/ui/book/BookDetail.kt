@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mfbooks.ui.book

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.formatClock
import app.mfbooks.ui.components.formatHm
import app.mfbooks.ui.components.rememberBookItems
import app.mfbooks.ui.theme.comfortable
import app.mfbooks.ui.theme.tabular
import coil3.compose.AsyncImage
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.data.Chapter
import app.mfbooks.ui.components.BookCover
import app.mfbooks.ui.components.CoverBackdrop
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.PlayingBars
import app.mfbooks.ui.components.SectionHeader
import app.mfbooks.ui.library.BookActionsSheet
import app.mfbooks.ui.server.BookCloudPanel
import app.mfbooks.ui.theme.LocalGlow

@Composable
fun BookDetailScreen(bookId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val items by rememberBookItems()
    val item = items?.firstOrNull { it.book.id == bookId }
    val chapters by app.db.library().chaptersFlow(bookId).collectAsStateWithLifecycle(emptyList())
    val now by app.player.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var actions by remember { mutableStateOf<Book?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }

    val b = item?.book
    val loaded = now.book?.id == bookId
    val currentIdx = if (loaded) now.chapterIndex
    else item?.let { i -> chapters.indexOfLast { it.startInBookMs <= i.positionMs }.takeIf { i.positionMs > 0 } } ?: -1
    val resume = b != null && (b.state == BookState.STARTED || loaded)
    Box(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        if (item == null || b == null) return@Box
        CoverBackdrop(b.coverUri, b.seedColor, Modifier.fillMaxWidth().height(520.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromePadding.current + 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BookCover(b, Modifier.fillMaxWidth(0.66f).shadow(24.dp, RoundedCornerShape(28.dp)))
                    Text(b.title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    b.author?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    b.narrator?.let { Text("Narrated by $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        b.series?.let { Meta(it + (b.seriesIndex?.let { i -> " #$i" } ?: "")) }
                        b.year?.let { Meta(it) }
                        b.genre?.let { Meta(it) }
                        Meta(formatHm(b.durationMs))
                        Meta("${chapters.size} chapters")
                        Meta(b.state.name.lowercase().replaceFirstChar { it.uppercase() }, highlight = b.state == BookState.STARTED)
                    }
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val pausing = loaded && now.isPlaying
                        Button({ if (loaded) app.player.playPause() else app.player.playBook(bookId) }, Modifier.height(56.dp),
                            contentPadding = ButtonDefaults.contentPaddingFor(56.dp)) {
                            Icon(if (pausing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
                            Text(if (pausing) "Pause" else if (resume) "Resume" else "Play", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalIconButton({ actions = b }, modifier = Modifier.size(56.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.MoreHoriz, "Book actions")
                        }
                    }
                }
            }
            if (b.state == BookState.STARTED || loaded) item {
                val pos = if (loaded) now.positionInBookMs else item.positionMs
                val frac = if (b.durationMs > 0) (pos.toFloat() / b.durationMs).coerceIn(0f, 1f) else 0f
                Panel(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.headlineSmallEmphasized.tabular(), color = MaterialTheme.colorScheme.primary)
                        Text("${formatHm(b.durationMs - pos)} left", Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearWavyProgressIndicator({ frac }, Modifier.fillMaxWidth().padding(top = 12.dp), amplitude = { if (loaded && now.isPlaying) 1f else 0f })
                }
            }
            b.description?.takeIf { it.isNotBlank() }?.let { d ->
                item {
                    Panel(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).animateContentSize()) {
                        Column(Modifier.clickable { expanded = !expanded }) {
                            Text("About", style = MaterialTheme.typography.titleMedium)
                            Text(d, Modifier.padding(top = 6.dp), maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (truncated || expanded) Text(if (expanded) "Show less" else "Show more", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    Column(Modifier.clickable { info = true }) {
                        Text("Details", style = MaterialTheme.typography.titleMedium)
                        listOfNotNull(
                            b.author?.let { "Author" to it }, b.narrator?.let { "Narrator" to it },
                            b.series?.let { "Series" to it + (b.seriesIndex?.let { i -> " #$i" } ?: "") },
                            b.year?.let { "Year" to it }, b.genre?.let { "Genre" to it }, b.publisher?.let { "Publisher" to it },
                            "Duration" to formatHm(b.durationMs), "Chapters" to chapters.size.toString(),
                        ).forEach { (k, v) ->
                            Row(Modifier.padding(top = 6.dp)) {
                                Text(k, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("Book info", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            item { BookCloudPanel(b, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            item { SectionHeader("Chapters") }
            itemsIndexed(chapters, key = { _, c -> c.index }) { i, c ->
                ChapterRow(c, current = i == currentIdx, listened = i < currentIdx, playing = loaded && now.isPlaying && i == currentIdx,
                    Modifier.padding(horizontal = 8.dp)) { app.player.playBook(bookId, c.index) }
            }
        }
        val solid = scroll.state.overlappedFraction > 0.01f
        TopAppBar(
            title = { if (solid) Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                FilledTonalIconButton(onBack, modifier = Modifier.padding(start = 4.dp), shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            scrollBehavior = scroll,
        )
    }
    actions?.let { BookActionsSheet(it) { actions = null } }
    if (info) b?.let { BookActionsSheet(it, startInfo = true) { info = false } }
}

@Composable
private fun Meta(text: String, highlight: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip(CircleShape).background(if (highlight) cs.primaryContainer else cs.surfaceContainerHighest.copy(alpha = 0.7f))
        .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge.tabular(), color = if (highlight) cs.onPrimaryContainer else cs.onSurfaceVariant)
}

/** Chapter row: number or live bars, title, duration; current one highlighted, listened ones dimmed. */
@Composable
fun ChapterRow(c: Chapter, current: Boolean, listened: Boolean, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (current) cs.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).alpha(if (listened) 0.55f else 1f)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (current) cs.primary else cs.surfaceContainerHigh), Alignment.Center) {
            if (current) PlayingBars(playing, color = cs.onPrimary)
            else Text("${c.index + 1}", style = MaterialTheme.typography.labelLarge.tabular(), color = cs.onSurfaceVariant)
        }
        Text(c.title, Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge, color = if (current) cs.onSecondaryContainer else cs.onSurface)
        Text(formatClock(c.durationMs), style = MaterialTheme.typography.bodySmall.tabular(), color = cs.onSurfaceVariant)
    }
}
