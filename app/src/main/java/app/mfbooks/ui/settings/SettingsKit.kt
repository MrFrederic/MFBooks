@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.mfbooks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** One setting. [render] draws it as a segmented list item with the shape for its position in the card. */
class Item(val title: String, val keywords: String = "", val render: @Composable (ListItemShapes) -> Unit)

/** A titled card of [Item]s. Sections with [searchable] = false stay out of the root search. */
class Section(val title: String?, val items: List<Item>, val footer: String? = null, val searchable: Boolean = true)

private val rowColors @Composable get() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))

/** Icon tile; [tone] 0..2 picks the primary/secondary/tertiary container so the root list isn't one flat colour. */
@Composable
fun Tile(icon: ImageVector, tone: Int = 0) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone % 3) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(bg), Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
    }
}

@Composable
fun SettingRow(
    shapes: ListItemShapes,
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    tone: Int = 0,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    value: String? = null,
) {
    val lead: (@Composable () -> Unit)? = icon?.let { { Tile(it, tone) } }
    val sup: (@Composable () -> Unit)? = if (summary == null && below == null) null else {
        {
            Column {
                summary?.let { Text(it) }
                below?.invoke()
            }
        }
    }
    val head: @Composable () -> Unit = {
        if (value == null) Text(title)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (onClick != null) SegmentedListItem(onClick = onClick, shapes = shapes, leadingContent = lead, trailingContent = trailing, supportingContent = sup, colors = rowColors) { head() }
    else SegmentedListItem(shapes = shapes, leadingContent = lead, trailingContent = trailing, supportingContent = sup, colors = rowColors) { head() }
}

/** Slider with a live value label. Commits on release so DataStore isn't written on every pixel. */
@Composable
fun SliderRow(
    shapes: ListItemShapes, title: String, icon: ImageVector?, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    label: (Float) -> String, onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    SettingRow(shapes, title, icon = icon, value = label(v),
        below = { Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(v) }) })
}

/** Slider over a fixed list of values (index-based), e.g. skip sizes 5 s … 5 min. */
@Composable
fun StepSliderRow(shapes: ListItemShapes, title: String, icon: ImageVector?, options: List<Int>, current: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    val idx = options.indices.minBy { abs(options[it] - current) }
    var v by remember(idx) { mutableFloatStateOf(idx.toFloat()) }
    SettingRow(shapes, title, icon = icon, value = label(options[v.roundToInt()]),
        below = { Slider(v, { v = it }, valueRange = 0f..(options.size - 1).toFloat(), steps = options.size - 2, onValueChangeFinished = { onSelect(options[v.roundToInt()]) }) })
}

/** A section card: connected segmented items under an optional label. */
@Composable
fun SectionCard(section: Section, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp)) {
        section.title?.let {
            Text(it, Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            section.items.forEachIndexed { i, item -> item.render(ListItemDefaults.segmentedShapes(i, section.items.size)) }
        }
        section.footer?.let {
            Text(it, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

