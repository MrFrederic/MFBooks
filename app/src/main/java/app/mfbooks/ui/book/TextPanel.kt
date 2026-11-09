@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.book

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.data.Book
import app.mfbooks.data.BookText
import app.mfbooks.data.MatchWhen
import app.mfbooks.data.Settings
import app.mfbooks.data.TextState
import app.mfbooks.library.DocEntry
import app.mfbooks.text.Matching
import app.mfbooks.ui.components.IconTile
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.app
import app.mfbooks.ui.settings.ChoiceDialog
import app.mfbooks.ui.text.ChapterBarMap
import app.mfbooks.ui.text.PanelButtons
import app.mfbooks.ui.text.TextImportHost
import app.mfbooks.ui.text.TextImportState
import app.mfbooks.ui.text.TextProblem
import app.mfbooks.ui.text.languageChoices
import app.mfbooks.ui.text.megabytes
import app.mfbooks.ui.text.openReader
import app.mfbooks.ui.text.rememberChapterStats
import app.mfbooks.ui.text.rememberTextImport
import app.mfbooks.ui.theme.tabular
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The book screen's read-along card: add text, follow matching, open the reader, or deal with a problem. */
@Composable
fun TextPanel(book: Book, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val rows by remember(book.id) { app.db.text().textFlow(book.id).map { listOfNotNull(it) } }.collectAsStateWithLifecycle(null)
    val settings by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val import = rememberTextImport(book.id)
    val loaded = rows ?: return
    val row = loaded.firstOrNull()
    val error = import.error

    var suggestion by remember(book.id) { mutableStateOf<DocEntry?>(null) }
    val wanted = row == null && settings.offerFolderText
    var checked by remember(book.id) { mutableStateOf(0) }
    LaunchedEffect(book.id, wanted, checked) {
        suggestion = if (wanted && !app.textStore.suggestionDismissed(book.id)) app.textStore.folderCandidates(book.id).firstOrNull() else null
    }

    TextImportHost(import, book)
    val cardModifier = modifier.animateContentSize()
    when {
        error != null -> TextProblem(error, import::pick, { import.error = null }, cardModifier)
        row == null -> Panel(cardModifier) {
            val found = suggestion
            val scope = rememberCoroutineScope()
            Header(subtitle = found?.name, onClose = found?.let { { scope.launch { app.textStore.dismissSuggestion(book.id); checked++ } } })
            when {
                import.busy -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                found != null -> PanelButtons("Use this file", { import.load(found.uri) }, "Choose another", import::pick)
                else -> Button(import::pick, Modifier.fillMaxWidth().padding(top = 16.dp).height(44.dp)) { Text("Add text") }
            }
        }
        else -> Panel(cardModifier) { RowState(book, row, settings, import, onManage) }
    }
}

@Composable
private fun ColumnScope.RowState(book: Book, row: BookText, settings: Settings, import: TextImportState, onManage: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(false) }
    val read = { openReader(book.id) }
    val startNow = { Matching.enqueue(ctx, book.id, true) }
    when (row.state) {
        TextState.ALIGNING -> MatchingCard(book, row, read, onManage)
        TextState.QUEUED -> Waiting(if (settings.matchWhen == MatchWhen.ANY) "Waiting to start" else "Waiting for charger", null, read, startNow, onManage)
        TextState.PAUSED -> if (row.error == Matching.NEEDS_VOSK) VoskNeeded(book.id, row.lang, read, onManage) else Waiting("Matching paused", row.error, read, startNow, onManage)
        TextState.NO_MATCH -> {
            Text("Text doesn't match the audio", style = MaterialTheme.typography.titleMedium)
            Body(row.error ?: "${percent(row.coverage)}% matched")
            PanelButtons("Replace text", import::pick, "Change language", { language = true })
            TextButton({ scope.launch { app.db.text().setState(book.id, TextState.PARSED, null, System.currentTimeMillis()) } }, Modifier.fillMaxWidth().height(44.dp)) {
                Text("Keep approximate sync")
            }
        }
        TextState.AUDIO_CHANGED -> {
            Text("The audio has changed", style = MaterialTheme.typography.titleMedium)
            Button({ Matching.enqueue(ctx, book.id, false) }, Modifier.fillMaxWidth().padding(top = 14.dp).height(44.dp)) { Text("Match again") }
        }
        TextState.READY -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text("Read along is ready", style = MaterialTheme.typography.titleMedium)
            }
            Body("${percent(row.coverage)}% matched", Modifier.padding(top = 8.dp))
            PanelButtons("Read along", read, "Manage", onManage)
        }
        else -> { // PARSED: nothing queued, approximate sync only
            Header(subtitle = "Approximate sync")
            PanelButtons("Read along", read, "Manage", onManage)
        }
    }
    if (language) ChoiceDialog("Language", languageChoices(row.lang), row.lang, { l ->
        language = false
        scope.launch { app.db.text().setLang(book.id, l, System.currentTimeMillis()); Matching.enqueue(ctx, book.id, false) }
    }, { language = false })
}

@Composable
private fun ColumnScope.MatchingCard(book: Book, row: BookText, read: () -> Unit, onManage: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val stats by rememberChapterStats(book.id, row.textSha, row.syncSha)
    val pct = if (book.durationMs > 0) ((row.syncedAudioMs * 100 / book.durationMs).toInt()).coerceIn(0, 99) else 0
    val left = rememberTimeLeft(row, book.durationMs)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Matching", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("$pct%", style = MaterialTheme.typography.headlineSmallEmphasized.tabular(), color = cs.primary)
        ManageIcon(onManage)
    }
    ChapterBarMap(stats, "$pct percent of the book is matched", Modifier.padding(top = 14.dp))
    left?.let { Body(it, Modifier.padding(top = 12.dp)) }
    PanelButtons("Read now", read, "Pause matching", { Matching.pause(ctx, book.id) })
}

@Composable
private fun ColumnScope.VoskNeeded(bookId: String, lang: String, read: () -> Unit, onManage: () -> Unit) {
    val ctx = LocalContext.current
    val progress = Matching.downloads.collectAsState().value[lang]
    val failed = lang in Matching.downloadFailed.collectAsState().value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Download speech engine", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        ManageIcon(onManage)
    }
    Body(when {
        progress != null -> "Downloading, ${(progress * 100).toInt()}%"
        failed -> "Download failed"
        else -> megabytes(Matching.voskDownloadBytes(ctx, lang))
    })
    PanelButtons(if (failed) "Try again" else "Download", { if (progress == null) Matching.downloadVosk(ctx, lang, bookId) }, "Read now", read)
}

@Composable
private fun ColumnScope.Waiting(title: String, body: String?, read: () -> Unit, start: () -> Unit, onManage: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        ManageIcon(onManage)
    }
    body?.let { Body(it) }
    PanelButtons("Read now", read, "Start matching", start)
}

// ponytail: the design has Manage only on the ready card; this keeps cancel, replace and remove reachable while a job runs
@Composable
private fun ManageIcon(onManage: () -> Unit) {
    IconButton(onManage, Modifier.padding(start = 4.dp).size(32.dp)) { Icon(Icons.Rounded.Tune, "Manage", Modifier.size(20.dp)) }
}

@Composable
private fun Header(subtitle: String? = null, onClose: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        IconTile(Icons.AutoMirrored.Rounded.MenuBook)
        Column(Modifier.weight(1f)) {
            Text("Read along", style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Body(it) }
        }
        onClose?.let { IconButton(it, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(20.dp)) } }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier.padding(top = 4.dp)) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun percent(fraction: Float) = (fraction * 100).roundToInt()

/**
 * ponytail: [Matching] has no estimate of its own, so the rate comes from two progress updates seen while this card is
 * on screen. Null until then, and the card shows no time.
 */
@Composable
private fun rememberTimeLeft(row: BookText, durationMs: Long): String? {
    var mark by remember(row.bookId) { mutableStateOf<Pair<Long, Long>?>(null) }
    var leftMs by remember(row.bookId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(row.syncedAudioMs) {
        mark?.let { (ms, at) ->
            val audio = row.syncedAudioMs - ms
            val wall = row.updatedAt - at
            if (audio > 0 && wall > 0) leftMs = (durationMs - row.syncedAudioMs).coerceAtLeast(0) * wall / audio
        }
        mark = row.syncedAudioMs to row.updatedAt
    }
    return leftMs?.let { ms ->
        val minutes = ms / 60_000
        if (minutes >= 60) "About ${(minutes + 30) / 60} h left" else "About ${minutes.coerceAtLeast(1)} m left"
    }
}
