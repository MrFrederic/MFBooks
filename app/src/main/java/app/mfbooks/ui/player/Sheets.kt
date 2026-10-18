@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package app.mfbooks.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.MFBooksApp
import app.mfbooks.playback.SleepMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SheetBase(onDismiss: () -> Unit, title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

/** Big tabular readout in a tinted rounded block. */
@Composable
private fun Readout(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 18.dp),
        style = MaterialTheme.typography.displayMediumEmphasized.merge(tnum), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer)
}

/** Shape-morphing option buttons; one is checked. */
@Composable
private fun <T> Options(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> ToggleButton(v == selected, { onPick(v) }) { Text(label, style = MaterialTheme.typography.labelLarge.merge(tnum)) } }
    }
}

private fun Float.step(s: Float) = (this / s).roundToInt() * s

@Composable
fun SpeedSheet(current: Float, onEq: () -> Unit, onBoost: () -> Unit, onDismiss: () -> Unit) {
    val player = MFBooksApp.get(LocalContext.current).player
    var v by remember { mutableFloatStateOf(current) }
    SheetBase(onDismiss, "Playback speed") {
        Readout(speedLabel(v))
        Slider(v, { v = it.step(0.05f); player.setSpeed(v) }, valueRange = 0.5f..3f,
            modifier = Modifier.semantics { contentDescription = "Playback speed" })
        Options(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to speedLabel(it) }, v) { v = it; player.setSpeed(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onEq, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Equalizer, null, Modifier.size(18.dp)); Text("EQ", Modifier.padding(start = 6.dp)) }
            FilledTonalButton(onBoost, shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp)); Text("Boost", Modifier.padding(start = 6.dp)) }
            Box(Modifier.weight(1f))
            Button({ player.setSpeed(v, asDefault = true); onDismiss() }, shapes = ButtonDefaults.shapes()) { Text("Set as default") }
        }
    }
}

@Composable
fun SleepSheet(mode: SleepMode, onDismiss: () -> Unit) {
    val app = MFBooksApp.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf("") }
    fun set(m: SleepMode, minutes: Int? = null) {
        app.player.setSleep(m)
        minutes?.let { v -> scope.launch { app.prefs.update { it.copy(sleepMinutes = v) } } }
        onDismiss()
    }
    SheetBase(onDismiss, "Sleep timer", "Fades out, then pauses. Shake to extend.") {
        Options(listOf(5, 10, 15, 20, 30, 45, 60, 75, 90).map { SleepMode.Duration(it) as SleepMode to "$it min" } +
            listOf(SleepMode.EndOfChapter to "End of chapter", SleepMode.Off to "Off"), mode) { m ->
            set(m, when (m) { is SleepMode.Duration -> m.minutes; SleepMode.EndOfChapter -> -1; SleepMode.Off -> null })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Custom minutes") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            Button({ custom.toIntOrNull()?.takeIf { it > 0 }?.let { set(SleepMode.Duration(it), it) } }, modifier = Modifier.height(56.dp),
                enabled = (custom.toIntOrNull() ?: 0) > 0, shapes = ButtonDefaults.shapes()) { Text("Start") }
        }
    }
}

private val eqHz = listOf("60", "230", "910", "4k", "14k")
private val eqPresets = listOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
    "Voice clarity" to listOf(-3f, -1f, 3f, 4f, 1f),
    "Bass cut" to listOf(-10f, -5f, 0f, 0f, 0f),
    "Treble boost" to listOf(0f, 0f, 0f, 4f, 8f),
)

@Composable
fun EqSheet(bookId: String, onDismiss: () -> Unit) {
    val app = MFBooksApp.get(LocalContext.current)
    val stored by app.db.library().settingsFlow(bookId).collectAsStateWithLifecycle(null)
    val bands = remember { List(5) { SliderState(0f, 0, -15f..15f) } }
    var balance by remember { mutableFloatStateOf(0f) }
    var mono by remember { mutableStateOf(false) }
    var pitch by remember { mutableFloatStateOf(1f) }
    var loaded by remember { mutableStateOf(false) }
    fun apply() = app.player.setEq(bands.map { it.value }, balance, mono, pitch)
    LaunchedEffect(stored) {
        if (loaded) return@LaunchedEffect
        stored?.let { s ->
            s.eqBandsDb?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 }?.forEachIndexed { i, f -> bands[i].value = f }
            balance = s.balance ?: 0f; mono = s.mono ?: false; pitch = s.pitch ?: 1f
        }
        loaded = stored != null
    }
    SheetBase(onDismiss, "Equalizer", "Saved for this book") {
        Options(eqPresets.map { it.second to it.first }, bands.map { it.value }) { v -> v.forEachIndexed { i, f -> bands[i].value = f }; apply() }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            eqHz.forEachIndexed { i, hz ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%+.0f".format(bands[i].value), style = MaterialTheme.typography.labelLarge.merge(tnum))
                    // Positional args: the 6th (reverse direction) stays false so +15 dB sits at the top.
                    VerticalSlider(bands[i], { bands[i].value = it.step(0.5f) },
                        Modifier.height(190.dp).width(44.dp).padding(vertical = 8.dp).semantics { contentDescription = "$hz hertz band" },
                        true, ::apply, false)
                    Text(hz, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Balance  %+.2f".format(balance), style = MaterialTheme.typography.labelLarge.merge(tnum))
        Slider(balance, { balance = it }, onValueChangeFinished = ::apply, valueRange = -1f..1f, modifier = Modifier.semantics { contentDescription = "Balance" })
        Text("Pitch  %.2f×".format(pitch), style = MaterialTheme.typography.labelLarge.merge(tnum))
        Slider(pitch, { pitch = it.step(0.05f) }, onValueChangeFinished = ::apply, valueRange = 0.5f..2f, modifier = Modifier.semantics { contentDescription = "Pitch" })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mono", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(mono, { mono = it; apply() })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton({ bands.forEach { it.value = 0f }; balance = 0f; mono = false; pitch = 1f; apply() }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
        }
    }
}

@Composable
fun BoostSheet(boostMb: Int, onDismiss: () -> Unit) {
    val player = MFBooksApp.get(LocalContext.current).player
    var db by remember { mutableFloatStateOf(boostMb / 100f) }
    SheetBase(onDismiss, "Volume boost") {
        Readout("+%.0f dB".format(db))
        Slider(db, { db = it.roundToInt().toFloat() }, valueRange = 0f..15f, steps = 14,
            onValueChangeFinished = { player.setBoost((db * 100).roundToInt()) },
            modifier = Modifier.semantics { contentDescription = "Volume boost in decibels" })
        if (db > 10f) Row(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("Above 10 dB, loud passages may clip and distort.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BookmarkSheet(defaultTitle: String, onDismiss: () -> Unit) {
    val player = MFBooksApp.get(LocalContext.current).player
    var title by remember { mutableStateOf(defaultTitle) }
    var note by remember { mutableStateOf("") }
    SheetBase(onDismiss, "Add bookmark") {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 2, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancel") }
            Button({ player.addBookmark(title.ifBlank { null }, note.ifBlank { null }); onDismiss() }, modifier = Modifier.padding(start = 8.dp),
                shapes = ButtonDefaults.shapes()) { Text("Save") }
        }
    }
}
