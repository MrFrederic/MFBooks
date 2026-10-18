@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

data class NavEntry(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val Narrow = 48.dp

/**
 * Floating pill navigation: an Expressive [HorizontalFloatingToolbar] whose selected tab is marked by a pill that
 * slides between items (icon + label for the selected one, icon only for the rest), plus a search FAB.
 * Every selected item is as wide as the widest label needs, and the items' widths animate in lockstep, so the toolbar's
 * total width never changes and it stays put (centred) while tabs switch.
 */
@Composable
fun FloatingNav(entries: List<NavEntry>, selected: Int, onSelect: (Int) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val labelW = with(LocalDensity.current) { entries.maxOf { measurer.measure(it.label, style, maxLines = 1).size.width }.toDp() }
    val wide = 16.dp + 24.dp + 8.dp + labelW + 16.dp
    val pillX by animateDpAsState(Narrow * selected, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "pillX")
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search") }
        },
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        Box(Modifier.height(Narrow)) {
            Box(Modifier.offset(x = pillX).width(wide).height(Narrow).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entries.forEachIndexed { i, e ->
                    val on = i == selected
                    // No bounce here: overshooting widths would make the toolbar breathe.
                    val f by animateFloatAsState(if (on) 1f else 0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), label = "tab$i")
                    val tint by animateColorAsState(if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
                    Box(
                        Modifier
                            .width(lerp(Narrow, wide, f)).height(Narrow)
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Tab) { onSelect(i) }
                            .semantics { this.selected = on; contentDescription = e.label }
                            .clipToBounds(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(start = lerp(12.dp, 16.dp, f)), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) e.selectedIcon else e.icon, null, tint = tint)
                            Text(e.label, Modifier.padding(start = 8.dp).graphicsLayer { alpha = f }.clearAndSetSemantics { }, color = tint, style = style, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
