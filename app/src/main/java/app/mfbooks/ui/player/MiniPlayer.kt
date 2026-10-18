@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Settings
import app.mfbooks.ui.components.Cover
import app.mfbooks.ui.components.formatHm
import app.mfbooks.ui.theme.LocalGlow
import app.mfbooks.ui.theme.tabular

/**
 * Floating pill: round cover ringed by a wavy chapter-progress indicator, chapter / book, rewind · play · forward.
 * Tap or swipe up expands it.
 */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val app = MFBooksApp.get(LocalContext.current)
    val player = app.player
    val np by player.state.collectAsStateWithLifecycle()
    val s by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val book = np.book ?: return
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(68.dp)
            .semantics { contentDescription = "Mini player, ${book.title}. Tap to expand" }
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -40f) onExpand() }) { _, dy -> drag += dy }
            },
        onClick = onExpand,
        shape = CircleShape,
        // A whisper of the cover colour over the container, like a lit-from-behind pill.
        color = androidx.compose.ui.graphics.lerp(cs.surfaceContainerHigh, LocalGlow.current.color, 0.18f),
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp), Alignment.Center) {
                CircularWavyProgressIndicator(
                    progress = { if (np.chapterDurationMs > 0) (np.positionInChapterMs.toFloat() / np.chapterDurationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.size(50.dp),
                    amplitude = { if (np.isPlaying) 1f else 0f },
                )
                Cover(book.coverUri, book.title, book.seedColor, Modifier.size(38.dp).clip(CircleShape), corner = 19.dp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(np.chapter?.title ?: book.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(book.title.takeIf { np.chapter != null }, "${formatHm(np.bookDurationMs - np.positionInBookMs)} left").joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Controls locked: only play/pause stays.
            if (!s.lockControls) IconButton({ player.skipBy(-s.rewindSmallSec * 1000L) }, Modifier.size(40.dp)) {
                SeekIcon(s.rewindSmallSec, forward = false, desc = "Rewind ${s.rewindSmallSec} seconds", size = 26.dp)
            }
            FilledIconButton(player::playPause, modifier = Modifier.padding(horizontal = 2.dp).size(46.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (np.isPlaying) "Pause" else "Play")
            }
            if (!s.lockControls) IconButton({ player.skipBy(s.fwdSmallSec * 1000L) }, Modifier.size(40.dp)) {
                SeekIcon(s.fwdSmallSec, forward = true, desc = "Forward ${s.fwdSmallSec} seconds", size = 26.dp)
            }
        }
    }
}
