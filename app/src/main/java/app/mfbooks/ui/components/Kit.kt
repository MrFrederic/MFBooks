@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of the floating chrome (mini player + toolbar + nav bar inset) that scrolling content must clear. */
val LocalChromePadding = compositionLocalOf { 0.dp }

/** Set by full-screen overlays (library search) to hide the floating chrome. ponytail: a global flag, not a nav-aware API. */
val chromeHidden = androidx.compose.runtime.mutableStateOf(false)

/** Section title in the primary colour, like "Keep listening". */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.primary)
        trailing?.invoke()
    }
}

/** Big Expressive-shape icon, title, body and an optional action: used for empty states. */
@Composable
fun EmptyPlaceholder(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(112.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        action?.invoke()
    }
}

/** Three bouncing bars: "this is what's playing". Static when [playing] is false. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, barWidth: Dp = 3.dp) {
    val t = rememberInfiniteTransition(label = "bars")
    Row(modifier.size(18.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(barWidth / 2, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        listOf(520, 380, 640).forEachIndexed { i, ms ->
            val h by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar$i")
            Box(Modifier.width(barWidth).fillMaxHeight(if (playing) h else 0.3f + i * 0.2f).clip(RoundedCornerShape(barWidth)).background(color))
        }
    }
}

/** One row of a [SettingsGroup]: icon tile, title, optional summary and trailing content. */
class GroupRow(
    val title: String,
    val summary: String? = null,
    val icon: ImageVector? = null,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

/** M3 Expressive settings group: a label and connected segmented list items. */
@Composable
fun SettingsGroup(title: String?, rows: List<GroupRow>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        title?.let {
            Text(it, Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { i, r ->
                val shapes = ListItemDefaults.segmentedShapes(i, rows.size)
                val lead: (@Composable () -> Unit)? = r.icon?.let { ic -> { IconTile(ic) } }
                val sup: (@Composable () -> Unit)? = r.summary?.let { s -> { Text(s) } }
                SegmentedListItem(
                    onClick = r.onClick ?: {},
                    shapes = shapes,
                    enabled = true,
                    leadingContent = lead,
                    trailingContent = r.trailing,
                    supportingContent = sup,
                    onLongClick = r.onLongClick,
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)),
                ) { Text(r.title) }
            }
        }
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A rounded card surface used by Stats and Home. */
@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(color).padding(20.dp), content = content)
}
