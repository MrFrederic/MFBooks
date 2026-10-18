@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.data.BookState
import app.mfbooks.data.ListeningSession
import app.mfbooks.ui.components.BookCover
import app.mfbooks.ui.components.EmptyPlaceholder
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.formatHm
import app.mfbooks.ui.components.rememberBookItems
import app.mfbooks.ui.theme.glowBackdrop
import app.mfbooks.ui.theme.tabular
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private enum class Period(val label: String, val days: Long?) { WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365), ALL("All time", null) }

@Composable
fun StatsScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val from = period.days?.let { today.minusDays(it - 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: 0L
    val sessions by remember(from) { app.db.userData().sessionsSince(from) }.collectAsStateWithLifecycle(emptyList())
    val books by rememberBookItems()
    val byId = books.orEmpty().associateBy { it.book.id }
    val finishedThisYear = books.orEmpty().count {
        it.book.state == BookState.FINISHED && (it.book.lastPlayedAt ?: 0) >= today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val total = sessions.sumOf { it.listenedMs }
    data class PerBook(val id: String, val title: String, val ms: Long)
    val perBook = sessions.groupBy { it.bookId }.map { (id, l) -> PerBook(id, l.last().bookTitle, l.sumOf { it.listenedMs }) }.sortedByDescending { it.ms }
    val cs = MaterialTheme.colorScheme
    val palette = listOf(cs.primary, cs.tertiary, cs.secondary, cs.inversePrimary, cs.error)

    Box(Modifier.fillMaxSize().glowBackdrop()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromePadding.current + 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TopAppBar({ Text("Stats", style = MaterialTheme.typography.headlineSmallEmphasized) },
                    actions = { IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Period.entries.forEach { p -> ToggleButton(period == p, { period = p }) { Text(p.label) } }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Total time listened", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Text(formatHm(total), style = MaterialTheme.typography.displayMediumEmphasized.tabular(), color = cs.primary)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("${sessions.size}", "Sessions", Modifier.weight(1f))
                    Small("${perBook.size}", "Books", Modifier.weight(1f))
                    Small("$finishedThisYear", "Finished in ${today.year}", Modifier.weight(1f))
                }
            }
            if (perBook.isEmpty()) item {
                EmptyPlaceholder(Icons.Rounded.Insights, "No listening yet", "Nothing recorded for this period. Press play and come back.")
            } else {
                item {
                    val top = perBook.first()
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            byId[top.id]?.let { BookCover(it.book, Modifier.size(92.dp), corner = 18.dp) }
                            Column(Modifier.padding(start = 16.dp)) {
                                Text("Your most played", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                                Text(top.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${formatHm(top.ms)} • ${sessions.count { it.bookId == top.id }} sessions", style = MaterialTheme.typography.bodyMedium.tabular(),
                                    color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Share(perBook.map { it.title to it.ms }, total, palette) }
                item {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(if (period == Period.YEAR || period == Period.ALL) "Listening by month" else "Listening by day",
                            style = MaterialTheme.typography.titleMedium, color = cs.primary)
                        BarChart(sessions, period, today, zone)
                    }
                }
                item {
                    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Books", style = MaterialTheme.typography.titleMedium, color = cs.primary, modifier = Modifier.padding(bottom = 8.dp))
                        perBook.forEachIndexed { i, b ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", Modifier.size(28.dp), style = MaterialTheme.typography.titleMedium.tabular(), color = cs.onSurfaceVariant)
                                byId[b.id]?.let { BookCover(it.book, Modifier.size(44.dp), corner = 12.dp) }
                                Text(b.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(formatHm(b.ms), style = MaterialTheme.typography.labelLarge.tabular())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Small(value: String, label: String, modifier: Modifier) {
    Panel(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMediumEmphasized.tabular(), color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2)
    }
}

/** Donut of time per book (top 5 + other) with a legend. */
@Composable
private fun Share(list: List<Pair<String, Long>>, total: Long, palette: List<Color>) {
    val top = list.take(5)
    val other = (total - top.sumOf { it.second }).coerceAtLeast(0)
    val parts = top.mapIndexed { i, (t, ms) -> Triple(t, ms, palette[i]) } +
        if (other > 0) listOf(Triple("Other", other, MaterialTheme.colorScheme.outlineVariant)) else emptyList()
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(132.dp).semantics { contentDescription = "Share of listening time per book" }) {
                val w = 22.dp.toPx()
                var start = -90f
                parts.forEach { (_, ms, c) ->
                    val sweep = 360f * ms / total.coerceAtLeast(1)
                    drawArc(c, start + 1.5f, (sweep - 3f).coerceAtLeast(0.5f), false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEach { (t, ms, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                        Text(t, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("${(ms * 100 / total.coerceAtLeast(1))}%", style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Bars per day (Week/Month); per month for Year/All. Compose Canvas, no chart library. */
@Composable
private fun BarChart(sessions: List<ListeningSession>, period: Period, today: LocalDate, zone: ZoneId) {
    val byMonth = period == Period.YEAR || period == Period.ALL
    fun key(d: LocalDate) = if (byMonth) d.withDayOfMonth(1) else d
    val sums = sessions.groupBy { key(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()) }.mapValues { e -> e.value.sumOf { it.listenedMs } }
    val first = when (period) {
        Period.ALL -> sums.keys.minOrNull() ?: key(today)
        else -> key(today.minusDays(period.days!! - 1))
    }
    val buckets = generateSequence(first) { if (byMonth) it.plusMonths(1) else it.plusDays(1) }.takeWhile { it <= key(today) }.toList()
    val max = (buckets.maxOfOrNull { sums[it] ?: 0L } ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val desc = "Listening per ${if (byMonth) "month" else "day"}, " + formatHm(sums.values.sum()) + " total"
    Canvas(Modifier.fillMaxWidth().padding(top = 16.dp).height(150.dp).semantics { contentDescription = desc }) {
        if (buckets.isEmpty()) return@Canvas
        val slot = size.width / buckets.size
        val w = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        buckets.forEachIndexed { i, d ->
            val x = i * slot + (slot - w) / 2
            drawRoundRect(empty, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            val h = (size.height * ((sums[d] ?: 0L).toFloat() / max)).coerceAtLeast(if ((sums[d] ?: 0L) > 0) w else 0f)
            if (h > 0f) drawRoundRect(if (d == key(today)) bar else bar.copy(alpha = 0.7f), Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
    val label = { d: LocalDate -> if (byMonth) d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else if (period == Period.WEEK) d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else "${d.dayOfMonth}" }
    if (period == Period.WEEK) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        buckets.forEach { Text(label(it), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        buckets.firstOrNull()?.let { Text(label(it) + if (byMonth) " ${it.year % 100}" else " ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", style = MaterialTheme.typography.labelSmall) }
        buckets.lastOrNull()?.let { Text(if (byMonth) label(it) else "Today", style = MaterialTheme.typography.labelSmall) }
    }
}
