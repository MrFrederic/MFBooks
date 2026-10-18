@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.ui.components.BookCover
import app.mfbooks.ui.components.GroupRow
import app.mfbooks.ui.components.SettingsGroup
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.formatHm
import kotlinx.coroutines.launch

/** Long-press sheet (§7.3). ponytail: queue / cover / edit / delete actions omitted for now. */
@Composable
fun BookActionsSheet(book: Book, startInfo: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(startInfo) }
    var confirmRestart by remember { mutableStateOf(false) }
    val dao = app(ctx).db.library()
    fun act(f: suspend () -> Unit) { scope.launch { f(); onDismiss() } }

    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("Play from beginning?") },
        text = { Text("Your saved position in this book will be reset.") },
        confirmButton = { TextButton({ app(ctx).player.playBook(book.id, fromStart = true); onDismiss() }) { Text("Restart") } },
        dismissButton = { TextButton({ confirmRestart = false }) { Text("Cancel") } },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.size(64.dp), corner = 16.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(book.author, formatHm(book.durationMs)).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedContent(info, label = "sheet") { showInfo ->
                if (!showInfo) {
                    SettingsGroup(null, listOfNotNull(
                        GroupRow("Play from beginning", icon = Icons.Rounded.Replay, onClick = { confirmRestart = true }).takeIf { book.state == BookState.STARTED },
                        GroupRow("Mark as New", icon = Icons.Rounded.FiberNew, onClick = { act { dao.setState(book.id, BookState.NEW) } }).takeIf { book.state != BookState.NEW },
                        GroupRow("Mark as Started", icon = Icons.Rounded.PlayCircle, onClick = { act { dao.setState(book.id, BookState.STARTED) } }).takeIf { book.state != BookState.STARTED },
                        GroupRow("Mark as Finished", icon = Icons.Rounded.CheckCircle, onClick = { act { dao.setState(book.id, BookState.FINISHED) } }).takeIf { book.state != BookState.FINISHED },
                        GroupRow(if (book.hidden) "Unhide" else "Hide", icon = if (book.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            onClick = { act { dao.setHidden(book.id, !book.hidden) } }),
                        GroupRow("Book info", icon = Icons.Rounded.Info, onClick = { info = true }),
                    ), Modifier.padding(top = 8.dp))
                } else {
                    Column {
                        SettingsGroup("Details", listOfNotNull(
                            GroupRow("Path", book.relativePath),
                            book.author?.let { GroupRow("Author", it) },
                            book.narrator?.let { GroupRow("Narrator", it) },
                            GroupRow("Duration", formatHm(book.durationMs)),
                            GroupRow("Files", book.fileCount.toString()),
                            book.series?.let { GroupRow("Series", it + (book.seriesIndex?.let { i -> " #$i" } ?: "")) },
                            book.year?.let { GroupRow("Year", it) },
                            book.genre?.let { GroupRow("Genre", it) },
                            book.publisher?.let { GroupRow("Publisher", it) },
                        ))
                    }
                }
            }
        }
    }
}
