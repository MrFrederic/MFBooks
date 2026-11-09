@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mfbooks.MFBooksApp
import app.mfbooks.data.HighlightStyle
import app.mfbooks.data.ReaderDensity
import app.mfbooks.data.ReaderFont
import app.mfbooks.data.Settings
import app.mfbooks.ui.player.HoldIconButton
import app.mfbooks.ui.player.SheetBase
import app.mfbooks.ui.player.tnum
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Long-press menu. A null action hides its row: without a map there is no time to seek or bookmark to. */
@Composable
internal fun SentenceSheet(
    sentence: String, where: String?, onDismiss: () -> Unit,
    onPlay: (() -> Unit)?, onBookmark: (() -> Unit)?, onCopy: () -> Unit, onNarratorHere: (() -> Unit)?,
) {
    SheetBase(onDismiss, sentence, where) {
        Column {
            onPlay?.let { MenuRow(Icons.Rounded.PlayArrow, "Play from here") { it(); onDismiss() } }
            onBookmark?.let { MenuRow(Icons.Rounded.Bookmark, "Add bookmark") { it(); onDismiss() } }
            MenuRow(Icons.Rounded.ContentCopy, "Copy sentence") { onCopy(); onDismiss() }
            onNarratorHere?.let { MenuRow(Icons.Rounded.GpsFixed, "Narrator is here") { it(); onDismiss() } }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer), Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = cs.primary)
        }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The Aa sheet. No scrim: the text above it is the live preview, so the reader moves the current line up while this is open. */
@Composable
internal fun ReadingSettingsSheet(s: Settings, onDismiss: () -> Unit) {
    val app = MFBooksApp.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    fun set(f: (Settings) -> Settings) { scope.launch { app.prefs.update(f) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), scrimColor = Color.Transparent) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Reading", style = MaterialTheme.typography.headlineSmallEmphasized)
            Column {
                Label("Font")
                Connected(ReaderFont.entries, s.readerFont, { f -> set { it.copy(readerFont = f) } }, 48.dp, { null }) { f ->
                    Text(when (f) { ReaderFont.LITERATA -> "Literata"; ReaderFont.SERIF -> "Serif"; ReaderFont.SANS -> "Sans" },
                        style = TextStyle(fontFamily = readerFamily(f, 16), fontSize = 16.sp), maxLines = 1)
                }
            }
            SizeRow(s.readerSizeSp) { v -> set { it.copy(readerSizeSp = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Density("Line spacing", "Compact", "Relaxed", s.readerLineSpacing) { v -> set { it.copy(readerLineSpacing = v) } }
                Density("Margins", "Narrow", "Wide", s.readerMargins) { v -> set { it.copy(readerMargins = v) } }
            }
            Column {
                Label("Highlight")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HighlightStyle.entries.forEach { h -> Tile(h, s.highlightStyle == h) { set { it.copy(highlightStyle = h) } } }
                }
            }
            Column {
                SwitchRow("Dim narrated text", s.dimNarrated) { v -> set { it.copy(dimNarrated = v) } }
                SwitchRow("Justify text", s.readerJustify) { v -> set { it.copy(readerJustify = v) } }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium.merge(tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SizeRow(current: Int, onChange: (Int) -> Unit) {
    val state = remember { SliderState(current.toFloat(), 13, 14f..28f) }
    val source = remember { MutableInteractionSource() }
    var shown by remember { mutableIntStateOf(current) }
    fun step(v: Int) {
        val c = v.coerceIn(14, 28)
        state.value = c.toFloat()
        if (c != shown) { shown = c; onChange(c) }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Text size")
            Label("$shown")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HoldIconButton({ step(shown - 1) }, {}, "Smaller text", 44.dp, RoundedCornerShape(16.dp), Color.Transparent) { Text("A", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp)) }
            Slider(
                state, onValueChange = { step(it.roundToInt()) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Text size $shown" },
                interactionSource = source,
                thumb = { SliderDefaults.Thumb(source, Modifier, SliderDefaults.colors(), true, DpSize(4.dp, 32.dp)) },
                track = { SliderDefaults.Track(it, Modifier.height(12.dp)) },
            )
            HoldIconButton({ step(shown + 1) }, {}, "Larger text", 44.dp, RoundedCornerShape(16.dp), Color.Transparent) { Text("A", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp)) }
        }
    }
}

@Composable
private fun RowScope.Density(title: String, low: String, high: String, value: ReaderDensity, onPick: (ReaderDensity) -> Unit) {
    Column(Modifier.weight(1f)) {
        Label(title)
        Connected(ReaderDensity.entries, value, onPick, 44.dp, { d -> when (d) { ReaderDensity.COMPACT -> low; ReaderDensity.NORMAL -> "Normal"; ReaderDensity.RELAXED -> high } }) { d ->
            Text(when (d) { ReaderDensity.COMPACT -> "S"; ReaderDensity.NORMAL -> "M"; ReaderDensity.RELAXED -> "L" }, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun <T> Connected(options: List<T>, selected: T, onPick: (T) -> Unit, height: Dp, label: (T) -> String?, content: @Composable (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, o ->
            ToggleButton(
                o == selected, { onPick(o) },
                Modifier.weight(1f).height(height).semantics { role = Role.RadioButton; label(o)?.let { contentDescription = it } },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { content(o) }
        }
    }
}

@Composable
private fun RowScope.Tile(h: HighlightStyle, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val label = when (h) { HighlightStyle.PLAYHEAD -> "Playhead"; HighlightStyle.BAND -> "Band"; HighlightStyle.WORD -> "Word only"; HighlightStyle.OFF -> "Off" }
    Column(
        Modifier.weight(1f).height(88.dp).clip(shape).background(if (on) cs.secondaryContainer else cs.surfaceContainerHighest)
            .then(if (on) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .selectable(on, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        TilePreview(h, if (on) cs.surfaceContainerHighest else cs.secondaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun TilePreview(h: HighlightStyle, band: Color) {
    val cs = MaterialTheme.colorScheme
    val dim = cs.onSurfaceVariant.copy(alpha = 0.7f)
    when (h) {
        HighlightStyle.PLAYHEAD -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(3.dp, 26.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary))
            TwoLines(cs.onSurface, 36.dp, 26.dp)
        }
        HighlightStyle.BAND -> Box(Modifier.clip(RoundedCornerShape(7.dp)).background(band).padding(horizontal = 6.dp, vertical = 5.dp)) { TwoLines(cs.onSurface, 36.dp, 26.dp) }
        HighlightStyle.WORD -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { PreviewLine(14.dp, dim); PreviewLine(18.dp, cs.primary); PreviewLine(8.dp, dim) }
            PreviewLine(30.dp, dim)
        }
        HighlightStyle.OFF -> TwoLines(dim, 40.dp, 30.dp)
    }
}

@Composable private fun PreviewLine(w: Dp, c: Color) = Box(Modifier.size(w, 5.dp).clip(RoundedCornerShape(3.dp)).background(c))
@Composable private fun TwoLines(c: Color, a: Dp, b: Dp) = Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { PreviewLine(a, c); PreviewLine(b, c) }

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(value, onChange, Modifier.semantics { contentDescription = label })
    }
}
