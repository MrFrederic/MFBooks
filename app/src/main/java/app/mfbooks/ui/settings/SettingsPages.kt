@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.LastPage
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.mfbooks.data.AutoRewind
import app.mfbooks.data.ColorSource
import app.mfbooks.data.EndOfBook
import app.mfbooks.data.ProgressFormat
import app.mfbooks.data.RootFolder
import app.mfbooks.data.Settings
import app.mfbooks.data.ThemeMode
import app.mfbooks.playback.Command
import kotlinx.coroutines.launch

fun secs(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60} min" else "$s s"
private fun title(s: String) = s.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun fmtSpeed(x: Float) = (if ((x * 10).let { it == it.toInt().toFloat() }) "%.1f" else "%.2f").format(x) + "×"
private fun snapSpeed(x: Float) = Math.round(x * 20) / 20f

/** Everything a setting item needs: current values, writers and the dialog / navigation hooks of the hosting screen. */
class Env(
    val s: Settings,
    val roots: List<RootFolder>,
    val set: ((Settings) -> Settings) -> Unit,
    val navigate: (String) -> Unit,
    val snack: (String) -> Unit,
    val copy: (String) -> Unit,
    val rescan: () -> Unit,
    val removeRoot: (RootFolder) -> Unit,
    val addFolder: () -> Unit,
    val version: String,
    /** Shows a dialog or sheet; the content gets a `dismiss` callback. */
    val dialog: (@Composable (dismiss: () -> Unit) -> Unit) -> Unit,
)

/** A settings page; [summary] says what the category contains (not current values). */
enum class SettingsPage(val route: String, val title: String, val icon: ImageVector, val summary: String) {
    Library("library", "Library", Icons.Rounded.LibraryBooks, "Folders, tag titles, rescan"),
    Playback("playback", "Playback", Icons.Rounded.PlayCircle, "Auto-rewind, speed, end of book"),
    Interruptions("interruptions", "Interruptions & headset", Icons.Rounded.Headphones, "Calls, notifications, headset connect"),
    Controls("controls", "Controls", Icons.Rounded.TouchApp, "Skip sizes, buttons, headset"),
    Sleep("sleep", "Sleep timer", Icons.Rounded.Bedtime, "Duration, fade-out, shake"),
    Appearance("appearance", "Appearance", Icons.Rounded.Palette, "Theme, colours, player layout"),
    Storage("storage", "Storage & data", Icons.Rounded.Storage, "Convert to Opus, statistics"),
    Automation("automation", "Automation", Icons.Rounded.Bolt, "Tasker actions"),
    About("about", "About", Icons.Rounded.Info, "Version");

    companion object {
        fun of(route: String?) = entries.firstOrNull { it.route == route } ?: Library
        val groups = listOf(listOf(Library, Playback, Interruptions, Controls, Sleep), listOf(Appearance, Storage, Automation, About))
    }
}

// --- item builders ---------------------------------------------------------------------------------------------

private fun Env.toggle(title: String, checked: Boolean, icon: ImageVector, summary: String? = null, keywords: String = "", onChange: (Boolean) -> Unit) =
    Item(title, keywords) { sh -> SettingRow(sh, title, summary, icon, onClick = { onChange(!checked) }, trailing = { Switch(checked, onChange) }) }

private fun <T> Env.choice(title: String, options: List<Choice<T>>, selected: T, icon: ImageVector, keywords: String = "", onSelect: (T) -> Unit) =
    Item(title, keywords) { sh ->
        SettingRow(sh, title, options.firstOrNull { it.value == selected }?.label ?: selected.toString(), icon,
            onClick = { dialog { dismiss -> ChoiceDialog(title, options, selected, { dismiss(); onSelect(it) }, dismiss) } })
    }

private fun <T> opts(vararg p: Pair<T, String>) = p.map { Choice(it.first, it.second) }

private fun actionLabel(a: String, s: Settings): String {
    val (name, arg) = a.split(':', limit = 2).let { it[0] to it.getOrNull(1)?.toIntOrNull() }
    return when (name) {
        "FWD" -> "Forward ${secs(arg ?: 30)}"
        "REWIND" -> "Rewind ${secs(arg ?: 30)}"
        "FWD_SMALL" -> "Forward ${secs(s.fwdSmallSec)} (small skip)"
        "FWD_BIG" -> "Forward ${secs(s.fwdBigSec)} (big skip)"
        "REWIND_SMALL" -> "Rewind ${secs(s.rewindSmallSec)} (small skip)"
        "REWIND_BIG" -> "Rewind ${secs(s.rewindBigSec)} (big skip)"
        "PLAY_PAUSE" -> "Play / pause"
        "NONE" -> "Do nothing"
        "NEXT_BOOKMARK" -> "Jump to next bookmark"
        else -> title(name)
    }
}

private val skipSizes = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300)
private val pickerSeconds = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 300)

private fun Env.slot(title: String, icon: ImageVector, value: String, prefix: String, secondsLabel: String, actions: List<String>, onSelect: (String) -> Unit) =
    Item(title, "bluetooth car notification lock screen button") { sh ->
        SettingRow(sh, title, actionLabel(value, s), icon, onClick = {
            dialog { dismiss ->
                ActionSheet(title, prefix, secondsLabel, pickerSeconds, actions.map { Choice(it, actionLabel(it, s)) }, value, { dismiss(); onSelect(it) }, dismiss)
            }
        })
    }

private fun Env.press(i: Int, label: String) = choice(
    label, listOf("PLAY_PAUSE", "FWD_SMALL", "REWIND_SMALL", "FWD_BIG", "REWIND_BIG", "ADD_BOOKMARK", "NEXT_CHAPTER", "PREV_CHAPTER", "NEXT_BOOKMARK", "TOGGLE_SPEED", "NONE").map { Choice(it, actionLabel(it, s)) },
    s.headsetPresses.getOrElse(i) { "NONE" }, Icons.Rounded.Headset, "headset button media button earphones",
) { v -> set { cur -> cur.copy(headsetPresses = (0 until 4).map { j -> if (j == i) v else cur.headsetPresses.getOrElse(j) { "NONE" } }) } }

// --- pages ------------------------------------------------------------------------------------------------------

fun Env.sections(page: SettingsPage): List<Section> = when (page) {
    SettingsPage.Library -> listOf(
        Section("Folders", roots.map { r ->
            Item(r.name) { sh -> SettingRow(sh, r.name, icon = Icons.Rounded.Folder,
                trailing = { IconButton({ dialog { d -> RemoveRootDialog(r, { d(); removeRoot(r) }, d) } }) { Icon(Icons.Rounded.Delete, "Remove folder ${r.name}") } }) }
        } + Item("Add folder", "audiobooks directory") { sh -> SettingRow(sh, "Add folder", icon = Icons.Rounded.CreateNewFolder, onClick = addFolder) }),
        Section("Books", listOf(
            toggle("Use tag titles", s.useTagTitles, Icons.Rounded.Sell, "Prefer titles from file tags over file names", "metadata") { v -> set { it.copy(useTagTitles = v) } },
            toggle("Show hidden books", s.showHiddenBooks, Icons.Rounded.VisibilityOff, "Include books hidden with .nomedia") { v -> set { it.copy(showHiddenBooks = v) } },
        )),
        Section("Progress files", listOf(
            toggle("Save progress in book folders", s.saveInBookFolders, Icons.Rounded.SaveAlt, "Keeps a sidecar file next to each book", "sidecar position backup") { v -> set { it.copy(saveInBookFolders = v) } },
        )),
        Section("Maintenance", listOf(
            Item("Rescan", "refresh library") { sh -> SettingRow(sh, "Rescan", icon = Icons.Rounded.Refresh, onClick = rescan) },
        )),
    )
    SettingsPage.Playback -> listOf(
        Section("Resuming", listOf(
            choice("Auto-rewind", listOf(
                Choice(AutoRewind.OFF, "Off"),
                Choice(AutoRewind.SMALL, "Up to 15 s"),
                Choice(AutoRewind.MEDIUM, "Up to 30 s"),
                Choice(AutoRewind.AUTO, "Auto"),
            ), s.autoRewind, Icons.Rounded.Replay, "resume pause") { v -> set { it.copy(autoRewind = v) } },
        )),
        Section("Speed & sound", listOf(
            Item("Default speed", "playback speed") { sh ->
                SliderRow(sh, "Default speed", Icons.Rounded.Speed, s.defaultSpeed, 0.5f..3f, 0, { fmtSpeed(snapSpeed(it)) }) { v -> set { it.copy(defaultSpeed = snapSpeed(v)) } }
            },
            toggle("Skip silence by default", s.defaultSkipSilence, Icons.Rounded.GraphicEq) { v -> set { it.copy(defaultSkipSilence = v) } },
        )),
        Section("When it ends", listOf(
            choice("At the end of the book", listOf(
                Choice(EndOfBook.NOTHING, "Do nothing"),
                Choice(EndOfBook.SHOW_LIBRARY, "Show library"),
                Choice(EndOfBook.NEXT_BOOK, "Play next book"),
                Choice(EndOfBook.NEXT_BOOK_SAME_SETTINGS, "Play next book with same settings"),
            ), s.endOfBook, Icons.AutoMirrored.Rounded.LastPage, "finish last") { v -> set { it.copy(endOfBook = v) } },
            Item("Force stop after", "timeout quit exit") { sh ->
                SliderRow(sh, "Force stop after", Icons.Rounded.TimerOff, s.forceStopHours.toFloat(), 0f..12f, 11, { if (it.toInt() == 0) "Off" else "${it.toInt()} h" }) { v -> set { it.copy(forceStopHours = v.toInt()) } }
            },
        ), footer = "Stops the service after this many hours without playing, to save battery."),
    )
    SettingsPage.Interruptions -> listOf(
        Section("Other sounds", listOf(
            toggle("Pause during messages", s.pauseOnMessage, Icons.Rounded.Sms, keywords = "notifications") { v -> set { it.copy(pauseOnMessage = v) } },
            toggle("Stop when other apps play", s.stopWhenOthersPlaying, Icons.Rounded.MusicOff, keywords = "audio focus") { v -> set { it.copy(stopWhenOthersPlaying = v) } },
            toggle("Resume after call", s.resumeAfterCall, Icons.Rounded.Call) { v -> set { it.copy(resumeAfterCall = v) } },
        )),
        Section("Headset", listOf(
            toggle("Play on headset connect", s.headsetConnectPlay, Icons.Rounded.Headphones, keywords = "bluetooth") { v -> set { it.copy(headsetConnectPlay = v) } },
        )),
    )
    SettingsPage.Controls -> listOf(
        Section("In-app skip sizes", listOf(
            Item("Rewind, small") { sh -> StepSliderRow(sh, "Rewind, small", Icons.Rounded.FastRewind, skipSizes, s.rewindSmallSec, ::secs) { v -> set { it.copy(rewindSmallSec = v) } } },
            Item("Rewind, big") { sh -> StepSliderRow(sh, "Rewind, big", Icons.Rounded.FastRewind, skipSizes, s.rewindBigSec, ::secs) { v -> set { it.copy(rewindBigSec = v) } } },
            Item("Forward, small") { sh -> StepSliderRow(sh, "Forward, small", Icons.Rounded.FastForward, skipSizes, s.fwdSmallSec, ::secs) { v -> set { it.copy(fwdSmallSec = v) } } },
            Item("Forward, big") { sh -> StepSliderRow(sh, "Forward, big", Icons.Rounded.FastForward, skipSizes, s.fwdBigSec, ::secs) { v -> set { it.copy(fwdBigSec = v) } } },
        )),
        Section("Bluetooth, car & notification buttons", listOf(
            slot("Next button", Icons.Rounded.SkipNext, s.nextAction, "FWD", "Skip forward by",
                listOf("NEXT_CHAPTER", "NEXT_FILE", "NEXT_BOOKMARK", "ADD_BOOKMARK", "NEXT_BOOK", "TOGGLE_SPEED", "FWD_SMALL", "FWD_BIG")) { v -> set { it.copy(nextAction = v) } },
            slot("Previous button", Icons.Rounded.SkipPrevious, s.prevAction, "REWIND", "Skip back by",
                listOf("PREV_CHAPTER", "PREV_FILE", "PREV_BOOK", "REWIND_SMALL", "REWIND_BIG")) { v -> set { it.copy(prevAction = v) } },
        )),
        Section("Headset button presses", listOf(press(0, "Single press"), press(1, "Double press"), press(2, "Triple press"), press(3, "Four presses"))),
    )
    SettingsPage.Sleep -> listOf(
        Section("Timer", listOf(
            choice("Default duration", (listOf(5, 10, 15, 20, 30, 45, 60, 90).map { Choice(it, "$it min") } + Choice(-1, "End of chapter")), s.sleepMinutes, Icons.Rounded.Bedtime) { v -> set { it.copy(sleepMinutes = v) } },
            Item("Fade-out length", "volume") { sh -> StepSliderRow(sh, "Fade-out length", Icons.Rounded.Gradient, listOf(0, 10, 30, 60, 120), s.sleepFadeSec, { if (it == 0) "None" else secs(it) }) { v -> set { it.copy(sleepFadeSec = v) } } },
            Item("Chime volume") { sh -> StepSliderRow(sh, "Chime volume", Icons.Rounded.NotificationsActive, listOf(0, 1, 2, 3), s.chimeVolume, { listOf("Off", "Quiet", "Medium", "Loud")[it] }) { v -> set { it.copy(chimeVolume = v) } } },
        )),
        Section("Shake to extend", listOf(
            Item("Shake sensitivity") { sh -> StepSliderRow(sh, "Shake sensitivity", Icons.Rounded.Vibration, listOf(1, 3, 6, 12, 18), s.shakeForce, { "$it m/s²" }) { v -> set { it.copy(shakeForce = v) } } },
            choice("Shake restarts timer", listOf(
                Choice("ALWAYS", "Always"),
                Choice("FADE", "Only while fading out"),
                Choice("NEVER", "Never"),
            ), s.trackMotion, Icons.Rounded.ScreenRotation, "motion accelerometer") { v -> set { it.copy(trackMotion = v) } },
        )),
        Section("Driving", listOf(
            toggle("Disable in car", s.disableSleepInCar, Icons.Rounded.DirectionsCar) { v -> set { it.copy(disableSleepInCar = v) } },
        )),
    )
    SettingsPage.Appearance -> listOf(
        Section("Look", listOf(
            choice("Theme", ThemeMode.entries.map { Choice(it, if (it == ThemeMode.BLACK) "Black (AMOLED)" else title(it.name)) }, s.theme, Icons.Rounded.DarkMode, "dark light night amoled") { v -> set { it.copy(theme = v) } },
            choice("App colours", listOf(Choice(ColorSource.SYSTEM, "Wallpaper"), Choice(ColorSource.COVER, "Book cover")), s.colorSource, Icons.Rounded.Palette, "dynamic material you") { v -> set { it.copy(colorSource = v) } },
        )),
        Section("Player", listOf(
            choice("Progress format", listOf(
                Choice(ProgressFormat.TIME, "Time"), Choice(ProgressFormat.TIME_PERCENT, "Time and percent"), Choice(ProgressFormat.TIME_LEFT, "Time left"),
                Choice(ProgressFormat.TIME_PERCENT_LEFT, "Time left and percent"), Choice(ProgressFormat.FILES, "Files"),
            ), s.progressFormat, Icons.Rounded.Percent) { v -> set { it.copy(progressFormat = v) } },
            toggle("Show chapters", s.showChapters, Icons.AutoMirrored.Rounded.FormatListBulleted) { v -> set { it.copy(showChapters = v) } },
            toggle("Keep screen on while charging", s.keepScreenOnCharging, Icons.Rounded.BatteryChargingFull, keywords = "awake wake lock") { v -> set { it.copy(keepScreenOnCharging = v) } },
        )),
    )
    SettingsPage.Storage -> listOf(
        Section("Space", listOf(
            Item("Convert to Opus", "compress shrink transcode mp3 m4b space") { sh -> SettingRow(sh, "Convert to Opus", "Shrink books to save space", Icons.Rounded.Compress, onClick = { navigate("convert") }) },
        )),
        Section("Data", listOf(
            toggle("Listening statistics", s.statsEnabled, Icons.Rounded.Insights, "Record time spent listening for the Stats tab") { v -> set { it.copy(statsEnabled = v) } },
        )),
    )
    SettingsPage.Automation -> listOf(
        Section("Tasker and other apps", Command.entries.map { c ->
            Item(c.action) { sh -> SettingRow(sh, c.name, c.action, onClick = { copy(c.action) }) }
        }, footer = "Broadcast these actions to control playback from Tasker and other apps. Tap to copy.", searchable = false),
    )
    SettingsPage.About -> listOf(Section(null, listOf(Item("MFBooks") { sh -> SettingRow(sh, "MFBooks", "Version $version", Icons.Rounded.Info) })))
}

@Composable
private fun RemoveRootDialog(r: RootFolder, onRemove: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Remove ${r.name}?") },
        text = { Text("Its books disappear from the library. Your files are not touched.") },
        confirmButton = { TextButton(onRemove) { Text("Remove") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}
