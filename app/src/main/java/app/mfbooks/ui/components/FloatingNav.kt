@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class NavEntry(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Floating pill navigation: an Expressive [HorizontalFloatingToolbar] whose selected tab is marked by a pill that
 * slides and stretches between items (icon + label for the selected one, icon only for the rest), plus a search FAB.
 */
@Composable
fun FloatingNav(entries: List<NavEntry>, selected: Int, onSelect: (Int) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Pair<Dp, Dp>>() }
    val target = bounds[selected]
    val springy = spring<Dp>(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)
    val x by animateDpAsState(target?.first ?: 0.dp, springy, label = "pillX")
    val w by animateDpAsState(target?.second ?: 0.dp, springy, label = "pillW")
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search") }
        },
        modifier = modifier.widthIn(max = 480.dp),
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        Box(Modifier.height(IntrinsicSize.Min)) {
            if (target != null) Box(Modifier.offset(x = x).width(w).fillMaxHeight().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entries.forEachIndexed { i, e ->
                    val on = i == selected
                    val tint by animateColorAsState(if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
                    Row(
                        Modifier
                            .onPlaced { c -> with(density) { bounds[i] = c.positionInParent().x.toDp() to c.size.width.toDp() } }
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Tab) { onSelect(i) }
                            .semantics { this.selected = on }
                            .widthIn(min = 48.dp)
                            .padding(horizontal = if (on) 16.dp else 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (on) e.selectedIcon else e.icon, e.label, tint = tint)
                        AnimatedVisibility(on, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                            Text(e.label, Modifier.padding(start = 8.dp), color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
