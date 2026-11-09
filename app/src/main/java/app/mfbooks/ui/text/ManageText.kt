@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.data.BookText
import app.mfbooks.text.Matching
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.LocalGutter
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.app
import app.mfbooks.ui.player.SheetBase
import app.mfbooks.ui.settings.ChoiceDialog
import app.mfbooks.ui.theme.tabular
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private const val OFFSET_STEP = 100
private const val OFFSET_MAX = 5000

@Composable
fun ManageTextScreen(bookId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val scope = rememberCoroutineScope()
    val row by app.db.text().textFlow(bookId).collectAsStateWithLifecycle(null)
    val book by app.db.library().bookFlow(bookId).collectAsStateWithLifecycle(null)
    val import = rememberTextImport(bookId)
    var gaps by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val r = row

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Text and sync") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        if (r == null) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LocalGutter.current).padding(horizontal = 12.dp).padding(top = 4.dp, bottom = LocalChromePadding.current + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            import.error?.let { TextProblem(it, import::pick, { import.error = null }, Modifier.fillMaxWidth()) }
            val stats by rememberChapterStats(bookId, r.textSha, r.syncSha)
            val size by produceState(0L, r.textSha, r.syncSha) { value = app.textStore.sizeOnDisk(bookId) }
            val pct = (r.coverage * 100).roundToInt()
            Panel(Modifier.fillMaxWidth()) {
                Text(r.sourceName, style = MaterialTheme.typography.titleMedium)
                Text("%,d words, %.1f MB".format(Locale.US, r.words, size / 1_000_000.0), Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChapterBarMap(stats, "$pct percent of the book is synced", Modifier.padding(top = 18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$pct% synced", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (stats.any { it.none > 0f }) TextButton({ gaps = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("See gaps") }
                }
            }
            Offset(r) { scope.launch { app.db.text().setOffset(bookId, it, System.currentTimeMillis()) } }
            VoskOffer(r.lang)
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                ActionRow(0, "Language", langName(r.lang), trailing = Icons.Rounded.ExpandMore, onClick = { language = true })
                ActionRow(1, "Match again", leading = Icons.Rounded.Refresh, onClick = { Matching.enqueue(ctx, bookId, false, keepLanguage = true) })
                ActionRow(2, "Replace text", leading = Icons.Rounded.SwapHoriz, onClick = import::pick)
                ActionRow(3, "Remove text", leading = Icons.Rounded.Delete, danger = true, onClick = { removing = true })
            }
        }
    }

    book?.let { TextImportHost(import, it) }
    if (r != null && gaps) rememberChapterStats(bookId, r.textSha, r.syncSha).value.let { stats ->
        SheetBase({ gaps = false }, "Gaps") {
            stats.filter { it.none > 0f }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${(s.none * 100).roundToInt().coerceAtLeast(1)}%", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (r != null && language) ChoiceDialog("Language", languageChoices(r.lang), r.lang, { l ->
        language = false
        scope.launch { app.db.text().setLang(bookId, l, System.currentTimeMillis()) }
    }, { language = false })
    if (r != null && removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Remove text?") },
        text = { Text("Deletes the saved copy of ${r.sourceName}, its sync and its transcript, here and in the book folder. Audio, bookmarks and the original file are kept.") },
        confirmButton = {
            TextButton({
                removing = false
                scope.launch { Matching.cancel(ctx, bookId); app.textStore.remove(bookId); onBack() }
            }) { Text("Remove") }
        },
        dismissButton = { TextButton({ removing = false }) { Text("Cancel") } },
    )
}

/** Stored [BookText.offsetMs] is added to playback time, so "Later" lowers it; the screen shows the highlight's shift, positive for later. */
@Composable
private fun Offset(r: BookText, onChange: (Int) -> Unit) {
    val shown = -r.offsetMs
    Panel(Modifier.fillMaxWidth()) {
        Text("Sync offset", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OffsetButton("Earlier", r.offsetMs < OFFSET_MAX) { onChange((r.offsetMs + OFFSET_STEP).coerceAtMost(OFFSET_MAX)) }
            Text(if (shown == 0) "0.0 s" else "%+.1f s".format(Locale.US, shown / 1000.0), style = MaterialTheme.typography.titleLarge.tabular())
            OffsetButton("Later", r.offsetMs > -OFFSET_MAX) { onChange((r.offsetMs - OFFSET_STEP).coerceAtLeast(-OFFSET_MAX)) }
        }
    }
}

@Composable
private fun OffsetButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick, Modifier.width(72.dp).height(48.dp), enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text(label) }
}

@Composable
private fun ActionRow(index: Int, title: String, summary: String? = null, leading: ImageVector? = null, trailing: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (danger) cs.error else cs.primary
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 4),
        leadingContent = leading?.let { icon -> { Icon(icon, null, tint = tint) } },
        trailingContent = trailing?.let { icon -> { Icon(icon, null) } },
        supportingContent = summary?.let { s -> { Text(s) } },
        colors = ListItemDefaults.segmentedColors(containerColor = cs.surfaceContainer, contentColor = if (danger) cs.error else cs.onSurface),
    ) { Text(title) }
}
