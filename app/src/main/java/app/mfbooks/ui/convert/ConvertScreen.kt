@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.mfbooks.ui.convert

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.mfbooks.convert.ConvertStore
import app.mfbooks.convert.ConvertWorker
import app.mfbooks.convert.Eligibility
import app.mfbooks.convert.formatBytes
import app.mfbooks.data.Book
import app.mfbooks.data.Settings
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.SectionHeader
import app.mfbooks.ui.components.app
import app.mfbooks.ui.theme.glowBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class BookPlan(val book: Book, val files: Int, val bytes: Long, val saving: Long)
private class RootPlan(val uri: String, val name: String, val books: List<BookPlan>) {
    val files get() = books.sumOf { it.files }
    val saving get() = books.sumOf { it.saving }
}

/** Opus space saver (§4.6): pick folders/books, read the warnings, start; live progress from the WorkManager job. */
@Composable
fun ConvertScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val store = remember { ConvertStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val roots by app.db.library().roots().collectAsStateWithLifecycle(emptyList())
    val saved by store.savedBytes.collectAsStateWithLifecycle(0L)
    val convertedFiles by store.convertedFiles.collectAsStateWithLifecycle(0L)
    val storedThreshold by store.batteryThreshold.collectAsStateWithLifecycle(80)
    val work by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(ConvertWorker.NAME) }
        .collectAsStateWithLifecycle(emptyList())
    val info = work.lastOrNull { !it.state.isFinished } ?: work.lastOrNull()
    val active = info != null && !info.state.isFinished
    val running = info?.state == WorkInfo.State.RUNNING

    // Eligible files per root/book, recomputed whenever a run ends (converted files drop out).
    var plans by remember { mutableStateOf<List<RootPlan>?>(null) }
    LaunchedEffect(roots, active) {
        plans = withContext(Dispatchers.IO) {
            val dao = app.db.library()
            roots.map { r ->
                RootPlan(r.uri, r.name, dao.booksInRoot(r.uri).mapNotNull { b ->
                    val el = dao.tracks(b.id).filter(Eligibility::isEligible)
                    if (el.isEmpty()) null else BookPlan(b, el.size, el.sumOf { it.sizeBytes }, el.sumOf(Eligibility::estimatedSaving))
                }.sortedBy { it.book.relativePath.lowercase() })
            }
        }
    }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { if (selected.isEmpty()) selected += store.queueNow() } // show what a running job is doing
    val expanded = remember { mutableStateListOf<String>() }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var threshold by remember(storedThreshold) { mutableFloatStateOf(storedThreshold.toFloat()) }

    val allBooks = plans.orEmpty().flatMap { it.books }
    val chosen = allBooks.filter { it.book.id in selected }
    val canStart = settings.saveInBookFolders && confirmed && chosen.isNotEmpty() && !active

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(Modifier.fillMaxSize().glowBackdrop(0.5f).nestedScroll(scroll.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize()) {
            LargeFlexibleTopAppBar(
                title = { Text("Convert to Opus") },
                subtitle = { Text("Shrink speech audiobooks to 32 kb/s") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                scrollBehavior = scroll,
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromePadding.current + 24.dp)) {
                item("saved") {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Savings, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Space saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(formatBytes(saved), style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                if (convertedFiles > 0) Text("$convertedFiles files converted so far", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            if (saved > 0) TextButton({ scope.launch { store.resetSaved() } }) { Text("Reset") }
                        }
                    }
                }

                if (!settings.saveInBookFolders) item("sidecar") {
                    Panel(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Text("Needs \"Save progress in book folders\"", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Text(
                            "Opus files can't carry the chapters and tags of the originals, so MFBooks keeps them in each book's .mfbooks.json. Turn the setting on to convert.",
                            Modifier.padding(top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button({ scope.launch { app.prefs.update { it.copy(saveInBookFolders = true) } } }) { Text("Turn on") }
                    }
                }

                if (info != null) item("job") { JobPanel(info, onCancel = { ConvertWorker.cancel(ctx) }) }

                item("pick") { SectionHeader("What to convert") }
                val ps = plans
                when {
                    ps == null -> item("loading") { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
                    ps.all { it.books.isEmpty() } -> item("none") {
                        Text("Nothing to convert: every file is already Opus/Vorbis, another format, or at most ${Eligibility.MIN_KBPS} kb/s.",
                            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> ps.forEach { root ->
                        item("root:${root.uri}") {
                            val ids = root.books.map { it.book.id }
                            val n = ids.count { it in selected }
                            val state = when (n) { 0 -> ToggleableState.Off; ids.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                            val open = root.uri in expanded
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                                val rows = 1 + if (open) root.books.size else 0
                                SegmentedListItem(
                                    onClick = { if (open) expanded -= root.uri else expanded += root.uri },
                                    shapes = ListItemDefaults.segmentedShapes(0, rows),
                                    enabled = root.books.isNotEmpty() && !active,
                                    leadingContent = {
                                        TriStateCheckbox(state, { if (state == ToggleableState.On) selected -= ids.toSet() else selected += ids.filter { it !in selected } },
                                            enabled = root.books.isNotEmpty() && !active)
                                    },
                                    trailingContent = { if (root.books.isNotEmpty()) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand") },
                                    supportingContent = {
                                        Text(if (root.books.isEmpty()) "Nothing eligible"
                                             else "${root.books.size} books · ${root.files} files · saves ~${formatBytes(root.saving)}")
                                    },
                                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)),
                                ) { Text(root.name, style = MaterialTheme.typography.titleMedium) }
                                if (open) root.books.forEachIndexed { i, bp ->
                                    val on = bp.book.id in selected
                                    SegmentedListItem(
                                        onClick = { if (on) selected -= bp.book.id else selected += bp.book.id },
                                        shapes = ListItemDefaults.segmentedShapes(i + 1, rows),
                                        enabled = !active,
                                        leadingContent = { Checkbox(on, { if (on) selected -= bp.book.id else selected += bp.book.id }, enabled = !active) },
                                        supportingContent = { Text("${bp.files} files · ${formatBytes(bp.bytes)} → saves ~${formatBytes(bp.saving)}") },
                                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)),
                                    ) { Text(bp.book.relativePath, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }

                item("battery") {
                    SectionHeader("When to run")
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("While charging, or on battery above ${threshold.toInt()} %", style = MaterialTheme.typography.titleSmall)
                                Text("Pauses below that and resumes on the charger", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Slider(threshold, { threshold = it }, valueRange = 20f..100f, steps = 15,
                            onValueChangeFinished = { scope.launch { store.setBatteryThreshold(threshold.toInt()) } })
                    }
                }

                item("warn") {
                    SectionHeader("Before you start")
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Warning(Icons.Rounded.WarningAmber, "Irreversible", "Originals are deleted once each Opus file checks out. Quality can't be restored.")
                        Warning(Icons.Rounded.Backup, "Keep a backup", "Copy the books somewhere else first if you might want the originals.")
                        Warning(Icons.Rounded.MusicOff, "Speech only", "32 kb/s Opus is fine for narration. Don't convert music.")
                        Warning(Icons.Rounded.Toc, "Chapters live in MFBooks", "Converted files become .opus. MFBooks keeps their chapters and tags in .mfbooks.json; other players may show them without chapters.")
                        Row(Modifier.fillMaxWidth().clickable(enabled = !active) { confirmed = !confirmed }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(confirmed, { confirmed = it }, enabled = !active)
                            Text("I understand and have a backup", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

                item("start") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (active) OutlinedButton({ ConvertWorker.cancel(ctx) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel conversion") }
                        else Button(
                            onClick = {
                                scope.launch {
                                    store.setQueue(chosen.map { it.book.id }.toSet())
                                    ConvertWorker.enqueue(ctx)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp), enabled = canStart,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(if (chosen.isEmpty()) "Pick books to convert"
                                 else "Convert ${chosen.sumOf { it.files }} files · save ~${formatBytes(chosen.sumOf { it.saving })}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobPanel(info: WorkInfo, onCancel: () -> Unit) {
    val p = if (info.state.isFinished) info.outputData else info.progress
    val done = p.getInt(ConvertWorker.P_DONE, 0)
    val total = p.getInt(ConvertWorker.P_TOTAL, 0)
    val file = p.getFloat(ConvertWorker.P_FILE, 0f)
    val failed = p.getInt(ConvertWorker.P_FAILED, 0)
    val current = p.getString(ConvertWorker.P_CURRENT)
    Panel(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        val onC = MaterialTheme.colorScheme.onSecondaryContainer
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                Text(if (total > 0) "Converting ${minOf(done + 1, total)} of $total" else "Preparing…", style = MaterialTheme.typography.titleLarge, color = onC)
                current?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = onC, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator({ if (total > 0) ((done + file) / total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator({ file }, Modifier.fillMaxWidth())
                if (failed > 0) Text("$failed failed so far", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onCancel) { Text("Cancel") }
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                Text("Waiting to run", style = MaterialTheme.typography.titleLarge, color = onC)
                Text("Starts when charging (or above the battery threshold) with enough free storage.", style = MaterialTheme.typography.bodyMedium, color = onC)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onCancel) { Text("Cancel") }
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Restore, null, tint = onC)
                Spacer(Modifier.width(12.dp))
                Text("Last run: " + (info.outputData.getString(ConvertWorker.OUT_MESSAGE) ?: info.state.name.lowercase()),
                    style = MaterialTheme.typography.bodyLarge, color = onC)
            }
            WorkInfo.State.CANCELLED -> Text("Last run was cancelled. Run again to continue where it stopped.", style = MaterialTheme.typography.bodyLarge, color = onC)
        }
    }
}

@Composable
private fun Warning(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
