@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.ui.components.BookCover
import app.mfbooks.ui.components.BookItem
import app.mfbooks.ui.components.BookTile
import app.mfbooks.ui.components.EmptyPlaceholder
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.PlayingBars
import app.mfbooks.ui.components.SectionHeader
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.formatHm
import app.mfbooks.ui.components.rememberAddFolder
import app.mfbooks.ui.components.rememberBookItems
import app.mfbooks.ui.library.BookActionsSheet
import app.mfbooks.ui.theme.glowBackdrop
import app.mfbooks.ui.theme.tabular

@Composable
fun HomeScreen(onOpenBook: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val items by rememberBookItems()
    val scan by app.scanner.progress.collectAsStateWithLifecycle()
    val warnings by app.scanner.warnings.collectAsStateWithLifecycle()
    val now by app.player.state.collectAsStateWithLifecycle()
    val addFolder = rememberAddFolder()
    var sheet by remember { mutableStateOf<Book?>(null) }

    val visible = items?.filter { !it.book.hidden }.orEmpty()
    val current = now.book?.let { b -> visible.firstOrNull { it.book.id == b.id } }
    // Hero carousel: the loaded book first, then the other started books by last played.
    val hero = (listOfNotNull(current) + visible.filter { it.book.state == BookState.STARTED || it.book.lastPlayedAt != null && it.book.state != BookState.FINISHED }
        .sortedByDescending { it.book.lastPlayedAt ?: 0 }).distinctBy { it.book.id }.take(10)
    val heroIds = hero.map { it.book.id }.toSet()
    val rows = listOf(
        "Up next" to visible.filter { it.book.queuePosition != null }.sortedBy { it.book.queuePosition },
        "Keep listening" to visible.filter { it.book.state == BookState.STARTED && it.book.id !in heroIds }.sortedByDescending { it.book.lastPlayedAt },
        "Recently added" to visible.filter { it.book.state == BookState.NEW && it.book.id !in heroIds }.sortedByDescending { it.book.addedAt }.take(20),
        "Finished" to visible.filter { it.book.state == BookState.FINISHED }.sortedByDescending { it.book.lastPlayedAt ?: 0 }.take(20),
    ).filter { it.second.isNotEmpty() }
    fun playToggle(id: String) = if (now.book?.id == id) app.player.playPause() else app.player.playBook(id)

    Box(Modifier.fillMaxSize().glowBackdrop()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromePadding.current + 16.dp)) {
            item {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_mfbooks_mono), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("MFBooks", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
                        }
                    },
                    actions = {
                        IconButton(onSearch) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
            scan?.let { p ->
                item {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ContainedLoadingIndicator(Modifier.size(48.dp))
                            Column {
                                Text("Scanning library…", style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(if (p.total > 0) "${p.done} / ${p.total}" else null, p.current).joinToString(" · "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            items(warnings) { w ->
                Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(w, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when {
                items == null -> {}
                visible.isEmpty() -> item {
                    EmptyPlaceholder(Icons.Rounded.CreateNewFolder, "No audiobooks yet",
                        "Pick the folder that holds your audiobooks. One folder per book, grouped by author:\n\nAudiobooks/Author/Title/",
                        Modifier.padding(top = 48.dp)) {
                        if (scan == null) Button(addFolder, Modifier.padding(top = 8.dp)) { Text("Add your audiobooks folder") }
                    }
                }
                else -> {
                    if (hero.isNotEmpty()) item(key = "hero") {
                        SectionHeader("Continue listening")
                        HeroCarousel(hero, now.book?.id, now.isPlaying, now.chapter?.title, onOpenBook, { sheet = it }, ::playToggle)
                    }
                    rows.forEach { (title, list) ->
                        item(key = title) {
                            SectionHeader(title)
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(list, key = { it.book.id }) {
                                    BookTile(it, { onOpenBook(it.book.id) }, { sheet = it.book }, Modifier.width(148.dp),
                                        playing = now.isPlaying && now.book?.id == it.book.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    sheet?.let { BookActionsSheet(it) { sheet = null } }
}

/** Big rounded cover cards, the centre one widest; tap opens the book, the round button plays/pauses. */
@Composable
private fun HeroCarousel(
    list: List<BookItem>, currentId: String?, playing: Boolean, chapter: String?,
    onOpen: (String) -> Unit, onLong: (Book) -> Unit, onPlay: (String) -> Unit,
) {
    val state = rememberCarouselState { list.size }
    // ponytail: a lone book gets a plain full-width card; the hero carousel would bleed it to the screen edge.
    if (list.size == 1) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(320.dp)) { HeroCard(list[0], currentId, playing, chapter, onOpen, onLong, onPlay, 1f) }
        return
    }
    HorizontalCenteredHeroCarousel(
        state = state, itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(320.dp),
    ) { i ->
        val info = carouselItemDrawInfo
        Box(Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge)
            .maskBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)), MaterialTheme.shapes.extraLarge)) {
            HeroCard(list[i], currentId, playing, chapter, onOpen, onLong, onPlay, ((info.size / info.maxSize - 0.6f) / 0.35f).coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun HeroCard(it: BookItem, currentId: String?, playing: Boolean, chapter: String?,
                     onOpen: (String) -> Unit, onLong: (Book) -> Unit, onPlay: (String) -> Unit, textAlpha: Float) {
        val b = it.book
        val isCurrent = b.id == currentId
        Box(
            Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge)
                .combinedClickable(onClick = { onOpen(b.id) }, onLongClick = { onLong(b) }),
        ) {
            BookCover(b, Modifier.fillMaxSize(), corner = 0.dp, square = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            if (isCurrent && playing) Box(Modifier.align(Alignment.TopStart).padding(14.dp).size(32.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium), Alignment.Center) {
                PlayingBars(true, Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, barWidth = 3.dp)
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)
                .graphicsLayer { alpha = textAlpha }, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(b.title, color = Color.White, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (isCurrent) chapter else null) ?: b.author.orEmpty(), color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator({ it.fraction }, Modifier.weight(1f).height(5.dp), color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f), drawStopIndicator = {})
                        Spacer(Modifier.width(10.dp))
                        Text("${formatHm(it.leftMs)} left", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium.tabular())
                    }
                }
                Spacer(Modifier.width(12.dp))
                val p = isCurrent && playing
                FilledIconButton({ onPlay(b.id) }, modifier = Modifier.size(52.dp), shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause ${b.title}" else "Play ${b.title}", Modifier.size(28.dp))
                }
            }
        }
}
