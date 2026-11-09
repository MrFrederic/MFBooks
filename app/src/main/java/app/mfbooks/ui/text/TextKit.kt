package app.mfbooks.ui.text

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.text.Matching
import app.mfbooks.text.ParsedText
import app.mfbooks.text.ReaderNav
import app.mfbooks.text.TextParseException.Reason
import app.mfbooks.text.TextParseException
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PICKER_TYPES = arrayOf(
    "application/x-fictionbook+xml", "application/epub+zip", "text/plain", "application/zip", "application/octet-stream",
)

/** Picking, parsing and saving a text file: shared by the book screen and the Text and sync screen. */
class TextImportState(private val app: MFBooksApp, private val bookId: String, private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    var parsed by mutableStateOf<ParsedText?>(null); private set
    var error by mutableStateOf<Reason?>(null)
    var busy by mutableStateOf(false); private set
    internal var launch: () -> Unit = {}

    fun pick() { error = null; launch() }

    fun load(uri: Uri) {
        error = null; busy = true
        scope.launch {
            try { parsed = app.textStore.parse(uri) }
            catch (e: TextParseException) { error = e.reason }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = Reason.EMPTY } // a malformed file the parser didn't classify
            finally { busy = false }
        }
    }

    fun close() { parsed = null }

    fun add(p: ParsedText, lang: String, matchNow: Boolean, match: Boolean) {
        parsed = null; busy = true
        scope.launch {
            try {
                if (app.db.text().text(bookId) != null) Matching.cancel(app, bookId)
                app.textStore.save(bookId, p, lang)
                if (match) Matching.enqueue(app, bookId, matchNow)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar("Couldn't save the text") }
            finally { busy = false }
        }
    }
}

@Composable
fun rememberTextImport(bookId: String): TextImportState {
    val app = app(LocalContext.current)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val state = remember(bookId) { TextImportState(app, bookId, scope, snackbar) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(state::load) }
    state.launch = { picker.launch(PICKER_TYPES) }
    return state
}

/** The review sheet, shown once a picked file has been parsed. */
@Composable
fun TextImportHost(state: TextImportState, book: Book) {
    val p = state.parsed ?: return
    ReviewSheet(book, p, onAdd = { lang, now, match -> state.add(p, lang, now, match) }, onReplace = { state.close(); state.pick() }, onDismiss = state::close)
}

fun openReader(bookId: String) {
    ReaderNav.openText.value = bookId // Root loads the book if another one is playing
}

/** Primary and secondary button side by side, as on every Text panel card. */
@Composable
fun PanelButtons(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onPrimary, Modifier.weight(1f).height(48.dp)) { Text(primary, maxLines = 1) }
        FilledTonalButton(onSecondary, Modifier.weight(1f).height(48.dp)) { Text(secondary, maxLines = 1) }
    }
}

/** A file couldn't be used. */
@Composable
fun TextProblem(reason: Reason, onChoose: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                when (reason) {
                    Reason.DRM -> "This book is DRM-protected"
                    Reason.EMPTY -> "MFBooks couldn't find any book text in this file"
                    Reason.TOO_LARGE -> "This file is over the 64 MB limit"
                    Reason.UNSUPPORTED -> "MFBooks can't read this type of file"
                },
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(20.dp)) }
        }
        FilledTonalButton(onChoose, Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp)) { Text("Choose another file") }
    }
}

/** Chapter stats for the bar map, recomputed when the text or the map changes. Empty until loaded or when not [enabled]. */
@Composable
fun rememberChapterStats(bookId: String, textSha: String?, syncSha: String?, enabled: Boolean = true): State<List<ChapterStat>> {
    val app = app(LocalContext.current)
    return produceState(emptyList(), bookId, textSha, syncSha, enabled) {
        value = if (!enabled) emptyList() else withContext(Dispatchers.IO) {
            val doc = app.textStore.readText(bookId) ?: return@withContext emptyList()
            chapterStats(doc, app.textStore.readSync(bookId)?.ranges.orEmpty())
        }
    }
}

/** One bar per chapter: height is the chapter's length, colour how much of it is matched. Chapters missing from the audio are dimmer. */
@Composable
fun ChapterBarMap(stats: List<ChapterStat>, description: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bars = remember(stats) { mergeBars(stats) }
    val longest = bars.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
    Row(modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEach { b ->
            val done = b.word + b.none
            val color = when {
                b.none >= 0.1f -> cs.primary.copy(alpha = 0.35f)
                done >= 0.95f -> cs.primary
                done > 0.05f -> cs.primary.copy(alpha = 0.55f)
                else -> cs.surfaceContainerHighest
            }
            Box(Modifier.weight(1f).fillMaxHeight(0.3f + 0.7f * b.length / longest).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}
