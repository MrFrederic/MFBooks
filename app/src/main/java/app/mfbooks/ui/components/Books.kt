@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mfbooks.MFBooksApp
import app.mfbooks.ui.theme.tabular
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.combine
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.ui.server.CloudBadge

/** A book plus listened position, for lists. */
@Stable
data class BookItem(val book: Book, val positionMs: Long) {
    val fraction: Float get() = if (book.durationMs > 0) (positionMs.toFloat() / book.durationMs).coerceIn(0f, 1f) else 0f
    val leftMs: Long get() = (book.durationMs - positionMs).coerceAtLeast(0)
}

fun app(ctx: Context) = MFBooksApp.get(ctx)

/** All books (including hidden ones; callers filter) with positions. */
@Composable
fun rememberBookItems(): State<List<BookItem>?> {
    val ctx = LocalContext.current
    return produceState<List<BookItem>?>(null) {
        val db = app(ctx).db.library()
        combine(db.allBooksIncludingHidden(), db.positions()) { books, pos ->
            val m = pos.associate { it.bookId to it.positionMs }
            books.map { BookItem(it.book, m[it.book.id] ?: 0L) }
        }.collect { value = it }
    }
}

private fun initials(title: String) =
    title.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** Cover, or an initials placeholder on an Expressive cookie shape tinted from the cover seed. */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, corner: Dp = 28.dp, square: Boolean = true) =
    Cover(book.coverUri, book.title, book.seedColor, modifier, corner, square)

@Composable
fun Cover(uri: String?, title: String, seed: Int?, modifier: Modifier = Modifier, corner: Dp = 28.dp, square: Boolean = true) {
    val shape = RoundedCornerShape(corner)
    val m = (if (square) modifier.aspectRatio(1f) else modifier).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    if (uri != null) {
        AsyncImage(uri, "Cover of $title", m, contentScale = ContentScale.Crop)
    } else {
        val tint = seed?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
        BoxWithConstraints(m, Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side * 0.72f).clip(MaterialShapes.Cookie9Sided.toShape()).background(tint), Alignment.Center) {
                Text(initials(title), color = if (tint.luminance() > 0.5f) Color.Black else Color.White,
                    style = MaterialTheme.typography.headlineMediumEmphasized.copy(fontSize = (side.value * 0.2f).sp))
            }
        }
    }
}

/** Compact cover tile for horizontal rows and the library grid: cover, title, a coloured status line. */
@Composable
fun BookTile(item: BookItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, playing: Boolean = false) {
    val b = item.book
    Column(modifier.clip(RoundedCornerShape(24.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp)) {
        Box {
            BookCover(b, Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(20.dp)), corner = 20.dp)
            when {
                playing -> Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).background(MaterialTheme.colorScheme.primary, CircleShape), Alignment.Center) {
                    PlayingBars(true, color = MaterialTheme.colorScheme.onPrimary, barWidth = 2.5.dp, modifier = Modifier.size(14.dp))
                }
                b.state == BookState.FINISHED -> Icon(Icons.Rounded.CheckCircle, "Finished",
                    Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    tint = MaterialTheme.colorScheme.primary)
            }
            CloudBadge(b, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (b.state == BookState.STARTED) {
                LinearProgressIndicator({ item.fraction }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(5.dp),
                    trackColor = Color.Black.copy(alpha = 0.35f), drawStopIndicator = {})
            }
        }
        Text(b.title, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmallEmphasized)
        Text(listOfNotNull(b.author, statusLine(item)).joinToString(" • "), Modifier.padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.primary)
    }
}

/** List row: thumb, title, author · status, thin progress. */
@Composable
fun BookRow(item: BookItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, playing: Boolean = false) {
    val b = item.book
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            BookCover(b, Modifier.size(60.dp), corner = 14.dp)
            CloudBadge(b, Modifier.align(Alignment.BottomEnd).padding(2.dp))
            if (playing) Box(Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)), Alignment.Center) {
                PlayingBars(true, color = Color.White)
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(b.author, statusLine(item)).joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (b.state == BookState.STARTED) {
                LinearProgressIndicator({ item.fraction }, Modifier.padding(top = 6.dp).width(140.dp), drawStopIndicator = {})
            }
        }
    }
}

fun statusLine(i: BookItem) = when (i.book.state) {
    BookState.NEW -> formatHm(i.book.durationMs)
    BookState.STARTED -> "${formatHm(i.leftMs)} left"
    BookState.FINISHED -> "Finished"
}
