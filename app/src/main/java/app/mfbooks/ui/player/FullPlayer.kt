@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.mfbooks.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.MFBooksApp
import app.mfbooks.ui.components.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Devices
import app.mfbooks.data.ProgressFormat
import app.mfbooks.data.Settings
import app.mfbooks.playback.NowPlaying
import app.mfbooks.playback.SleepMode
import app.mfbooks.server.Server
import app.mfbooks.ui.components.Cover
import app.mfbooks.ui.components.CoverBackdrop
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.formatHm
import app.mfbooks.data.ReaderFont
import app.mfbooks.playback.CastSupport
import app.mfbooks.text.ReaderNav
import app.mfbooks.ui.reader.FOLLOW_ANCHOR
import app.mfbooks.ui.reader.Reader
import app.mfbooks.ui.reader.ReadingSettingsSheet
import app.mfbooks.ui.reader.readerFamily
import app.mfbooks.ui.server.ConnectSheet
import app.mfbooks.ui.server.PlayingOnStrip
import app.mfbooks.ui.theme.LocalGlow
import app.mfbooks.ui.theme.comfortable

private enum class Sheet { Speed, Sleep, Eq, Boost, Bookmark, Lists, Reading }

/** Cover or text; per process, so reopening the player keeps it. */
private val textChosen = mutableStateOf(false)

private class ToolActions(
    val onSleep: () -> Unit, val onSleepLong: () -> Unit, val onBoost: () -> Unit, val onEq: () -> Unit, val onSpeed: () -> Unit, val onSpeedLong: () -> Unit,
    val onBookmarks: () -> Unit, val onBookmarkAdd: () -> Unit, val onLock: () -> Unit,
    val onSkipSilence: () -> Unit, val onHistory: () -> Unit, val onChapters: () -> Unit, val onAddWithNote: () -> Unit, val onDetails: () -> Unit,
    val onSettings: () -> Unit, val onConnect: (() -> Unit)?,
)

internal val tnum = TextStyle(fontFeatureSettings = "tnum")

/** Current line position while the reading sheet covers the lower part of the text. */
private const val READING_SHEET_ANCHOR = 0.06f

/**
 * Layout, top to bottom: compact tool row · book title + whole-book progress · big cover (tap = play/pause) ·
 * file / chapter pickers · chapter slider · transport. With [Settings.lockControls] everything except play/pause,
 * the chapter picker and the unlock toggle is hidden or disabled.
 */
@Composable
fun FullPlayer(onCollapse: () -> Unit, onOpenBook: (String) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val app = MFBooksApp.get(LocalContext.current)
    val player = app.player
    val scope = rememberCoroutineScope()
    val np by player.state.collectAsStateWithLifecycle()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val book = np.book ?: return
    val tracks by remember(book.id) { app.db.library().tracksFlow(book.id) }.collectAsStateWithLifecycle(emptyList())
    val locked = settings.lockControls
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var listTab by remember { mutableStateOf(ListTab.Chapters) }
    var toast by remember { mutableStateOf<String?>(null) }
    var connect by remember { mutableStateOf(false) }
    val link by Server.link.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    fun openLists(tab: ListTab) { listTab = tab; sheet = Sheet.Lists }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    val bookText by remember(book.id) { app.db.text().textFlow(book.id) }.collectAsStateWithLifecycle(null)
    val readerTarget by ReaderNav.openText.collectAsStateWithLifecycle()
    LaunchedEffect(readerTarget, book.id) { if (readerTarget == book.id) { textChosen.value = true; ReaderNav.openText.value = null } }
    val readable = bookText
    val textView = textChosen.value && readable != null
    var focus by remember { mutableStateOf(false) }
    LaunchedEffect(textView) { if (!textView) focus = false }
    BackHandler(textView && focus) { focus = false }
    // The app-wide snackbar host sits under this full-screen player.
    val readerSnackbar = remember { SnackbarHostState() }
    val chapterTitle = np.chapter?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${np.chapterIndex + 1}"
    val actions = ToolActions(
        onSleep = { sheet = Sheet.Sleep },
        onSleepLong = {
            val on = np.sleep.mode != SleepMode.Off
            player.setSleep(if (on) SleepMode.Off else if (settings.sleepMinutes < 0) SleepMode.EndOfChapter else SleepMode.Duration(settings.sleepMinutes))
            toast = if (on) "Sleep timer off" else if (settings.sleepMinutes < 0) "Sleep timer: end of chapter" else "Sleep timer: ${settings.sleepMinutes} min"
        },
        onBoost = { sheet = Sheet.Boost }, onEq = { sheet = Sheet.Eq },
        onSpeed = { sheet = Sheet.Speed }, onSpeedLong = player::toggleSpeed,
        onBookmarks = { openLists(ListTab.Bookmarks) },
        onBookmarkAdd = {
            player.addBookmark()
            toast = "Bookmark added at ${np.chapter?.title ?: book.title} · ${formatClock(np.positionInChapterMs)}"
        },
        onLock = { scope.launch { app.prefs.update { it.copy(lockControls = !locked) } } },
        onSkipSilence = { player.setSkipSilence(!np.skipSilence) },
        onHistory = { openLists(ListTab.History) }, onChapters = { openLists(ListTab.Chapters) },
        onAddWithNote = { sheet = Sheet.Bookmark }, onDetails = { onOpenBook(book.id) }, onSettings = onSettings,
        onConnect = if (link != null) { { connect = true } } else null)

    Box(modifier.fillMaxSize().background(cs.background)) {
        // Same blurred-cover haze as the book page, stretched over the whole player.
        if (textView) {
            // A short glow only: a wash behind the text muddies the narrated and upcoming greys.
            val glow = LocalGlow.current
            val tint = book.seedColor?.let { comfortable(Color(it), glow.dark) } ?: glow.color
            if (!focus) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint.copy(alpha = if (glow.dark) 0.45f else 0.3f), 0.36f to Color.Transparent)))
        } else CoverBackdrop(book.coverUri, book.seedColor, Modifier.fillMaxSize())
        if (textView && readable != null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().then(if (focus) Modifier else Modifier.navigationBarsPadding().padding(bottom = 8.dp))) {
                if (focus) FocusHeader(chapterTitle, !locked, onExit = { focus = false }, onReadingSettings = { sheet = Sheet.Reading })
                else {
                    TextToolRow(np, locked, actions, onCollapse, onCover = { textChosen.value = false }, onReadingSettings = { sheet = Sheet.Reading }, onFocus = { focus = true })
                    Box(Modifier.padding(horizontal = 16.dp)) { PlayingOnStrip() }
                }
                CompositionLocalProvider(LocalSnackbar provides readerSnackbar) {
                    Reader(
                        book, readable, tracks, settings, locked,
                        anchor = if (sheet == Sheet.Reading) READING_SHEET_ANCHOR else FOLLOW_ANCHOR,
                        bottomInset = if (focus) 100.dp else 18.dp,
                        modifier = Modifier.weight(1f).fillMaxWidth().then(if (settings.keepScreenReading && np.isPlaying) Modifier.keepScreenOn() else Modifier),
                    )
                }
                if (!focus) Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val h = 44.dp
                        HoldIconButton(actions.onSleep, actions.onSleepLong, "Sleep timer. Long press to toggle", h, RoundedCornerShape(16.dp),
                            if (np.sleep.mode != SleepMode.Off) cs.primary else Color.Transparent, enabled = !locked) { SleepGlyph(np) }
                        PickerRow(chapterTitle, MaterialTheme.typography.titleMedium, chevron = !locked, onClick = actions.onChapters, modifier = Modifier.weight(1f))
                        HoldIconButton(actions.onSpeed, actions.onSpeedLong, "Playback speed ${speedLabel(np.speed)}. Long press to toggle", h, RoundedCornerShape(16.dp),
                            if (np.speed != 1f) cs.primary else Color.Transparent, Modifier.height(h).widthIn(min = 56.dp), enabled = !locked) {
                            Text(speedLabel(np.speed), style = MaterialTheme.typography.labelLarge.merge(tnum), maxLines = 1)
                        }
                    }
                    ChapterSeek(np, enabled = !locked)
                    TextTransport(np, settings, locked, actions.onChapters)
                }
            }
            if (focus) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.58f to cs.background)))
                FocusPill(np, book.title, settings, locked, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 16.dp))
            }
            SnackbarHost(readerSnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (focus) 92.dp else 214.dp))
        } else Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToolRow(np, locked, onCollapse, actions)
            PlayingOnStrip()
            if (readable != null) ViewSwitch(text = false, onChange = { textChosen.value = it }, Modifier.padding(top = 4.dp))

            // Whole-book block: title, thin progress bar, "Read x of y · n% · Left z".
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(listOfNotNull(book.author, book.title).joinToString(" – "), Modifier.basicMarquee(), style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1, textAlign = TextAlign.Center)
                LinearWavyProgressIndicator(
                    progress = { if (np.bookDurationMs > 0) (np.positionInBookMs.toFloat() / np.bookDurationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    amplitude = { if (np.isPlaying) 1f else 0f },
                )
                Text(bookProgressText(np, settings.progressFormat, tracks.size), Modifier.padding(top = 6.dp).semantics { contentDescription = "Book progress" },
                    style = MaterialTheme.typography.bodyMedium.merge(tnum), color = cs.onSurface.copy(alpha = 0.85f), maxLines = 1, textAlign = TextAlign.Center)
            }

            // Cover fills whatever height is left. Tap = play/pause; swipe left/right = chapter, down = collapse.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 440.dp)
                Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                    Cover(
                        book.coverUri, book.title, book.seedColor,
                        Modifier
                            .size(side)
                            .shadow(24.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black, spotColor = Color.Black)
                            .clip(RoundedCornerShape(32.dp))
                            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = if (np.isPlaying) "Pause" else "Play") { player.playPause() }
                            .pointerInput(locked) {
                                var dx = 0f; var dy = 0f
                                detectDragGestures(onDragStart = { dx = 0f; dy = 0f }, onDragEnd = {
                                    when {
                                        abs(dy) > abs(dx) && dy > 120f -> onCollapse()
                                        locked -> Unit
                                        dx < -100f -> player.nextChapter()
                                        dx > 100f -> player.prevChapter()
                                    }
                                }) { _, d -> dx += d.x; dy += d.y }
                            },
                        corner = 32.dp,
                    )
                    val glyph by animateFloatAsState(if (!np.isPlaying && !np.isBuffering) 1f else 0f, label = "playGlyph")
                    Box(Modifier.size(84.dp).graphicsLayer { alpha = glyph; scaleX = 0.8f + 0.2f * glyph; scaleY = scaleX }
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(52.dp), tint = Color.White)
                    }
                }
            }

            // File and chapter pickers. Locked: chevrons and prev/next vanish; the chapter row still opens the chapter list.
            val file = tracks.getOrNull(np.chapter?.trackIndex ?: 0)?.fileName
            if (file != null) PickerRow(file, MaterialTheme.typography.bodyMedium, chevron = !locked && tracks.size > 1,
                onClick = if (!locked && tracks.size > 1) ({ openLists(ListTab.Files) }) else null)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!locked) HoldIconButton(player::prevChapter, { openLists(ListTab.Chapters) }, "Previous chapter", 44.dp, RoundedCornerShape(16.dp), cs.onSurface.copy(alpha = 0.10f)) {
                    Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(24.dp))
                }
                PickerRow(chapterTitle, MaterialTheme.typography.titleLarge, chevron = !locked, onClick = { openLists(ListTab.Chapters) }, modifier = Modifier.weight(1f))
                if (!locked) HoldIconButton(player::nextChapter, { openLists(ListTab.Chapters) }, "Next chapter", 44.dp, RoundedCornerShape(16.dp), cs.onSurface.copy(alpha = 0.10f)) {
                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(24.dp))
                }
            }

            ChapterSeek(np, enabled = !locked)
            Transport(np, settings, locked)
        }

        // Transient confirmation (e.g. bookmark added); sits under the tool row.
        AnimatedVisibility(toast != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 118.dp, start = 24.dp, end = 24.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(20.dp), color = cs.inverseSurface, contentColor = cs.inverseOnSurface, shadowElevation = 6.dp) {
                Text(toast.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (connect) ConnectSheet { connect = false }
    when (sheet) {
        Sheet.Speed -> SpeedSheet(np.speed, onEq = { sheet = Sheet.Eq }, onBoost = { sheet = Sheet.Boost }) { sheet = null }
        Sheet.Sleep -> SleepSheet(np.sleep.mode) { sheet = null }
        Sheet.Eq -> EqSheet(book.id) { sheet = null }
        Sheet.Boost -> BoostSheet(np.boostMb) { sheet = null }
        Sheet.Bookmark -> BookmarkSheet("${np.chapter?.title ?: book.title} · ${formatClock(np.positionInBookMs)}") { sheet = null }
        Sheet.Lists -> ListsSheet(np, tracks, listTab, chaptersOnly = locked) { sheet = null }
        Sheet.Reading -> ReadingSettingsSheet(settings) { sheet = null }
        null -> Unit
    }
}

internal fun speedLabel(s: Float) = (if (s * 100 % 10 == 0f) "%.1f×" else "%.2f×").format(s)

/** "Read 32:16 of 8:55:01 · 6% · Left 8:22:45", trimmed per the progress-format setting. */
private fun bookProgressText(np: NowPlaying, format: ProgressFormat, files: Int): String {
    val pos = np.positionInBookMs; val total = np.bookDurationMs
    val pct = if (total > 0) (pos * 100f / total).roundToInt() else 0
    val read = "Read ${formatClock(pos)} of ${formatClock(total)}"
    val left = "Left ${formatClock(total - pos)}"
    return when (format) {
        ProgressFormat.TIME -> read
        ProgressFormat.TIME_PERCENT -> "$read · $pct%"
        ProgressFormat.TIME_LEFT -> "$read · $left"
        ProgressFormat.TIME_PERCENT_LEFT -> "$read · $pct% · $left"
        ProgressFormat.FILES -> "File ${(np.chapter?.trackIndex ?: 0) + 1} of ${maxOf(files, 1)} · Chapter ${np.chapterIndex + 1} of ${np.chapters.size} · $pct%"
    }
}

/** "1 m" for whole minutes, otherwise "10 s". */
private fun secLabel(sec: Int) = if (sec >= 60 && sec % 60 == 0) "${sec / 60} m" else "$sec s"

/** Compact tool row: collapse, then rarely-used quick toggles as small icons. Locked: all but collapse and the lock itself are disabled. */
@Composable
private fun ToolRow(np: NowPlaying, locked: Boolean, onCollapse: () -> Unit, a: ToolActions) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val sleepOn = np.sleep.mode != SleepMode.Off
    val none = Color.Transparent
    // Active toggles are filled primary; HoldIconButton picks the matching on-colour for the icon.
    val active = cs.primary
    val h = 44.dp
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .pointerInput(Unit) { var dy = 0f; detectDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 80f) onCollapse() }) { _, d -> dy += d.y } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val m = Modifier.weight(1f).height(h)
        HoldIconButton(onCollapse, {}, "Collapse player", h, RoundedCornerShape(16.dp), none, m) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(28.dp)) }
        HoldIconButton(a.onSleep, a.onSleepLong, "Sleep timer. Long press to toggle", h, RoundedCornerShape(16.dp), if (sleepOn) active else none, m, enabled = !locked) { SleepGlyph(np) }
        HoldIconButton(a.onBoost, {}, if (np.boostMb > 0) "Volume boost +${np.boostMb / 100} dB" else "Volume boost", h, RoundedCornerShape(16.dp), if (np.boostMb > 0) active else none, m, enabled = !locked) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(22.dp))
        }
        HoldIconButton(a.onEq, {}, "Equalizer", h, RoundedCornerShape(16.dp), none, m, enabled = !locked) { Icon(Icons.Rounded.Equalizer, null, Modifier.size(22.dp)) }
        HoldIconButton(a.onSpeed, a.onSpeedLong, "Playback speed ${speedLabel(np.speed)}. Long press to toggle", h, RoundedCornerShape(16.dp), if (np.speed != 1f) active else none,
            Modifier.weight(1.3f).height(h), enabled = !locked) {
            Text(speedLabel(np.speed), style = MaterialTheme.typography.labelLarge.merge(tnum), maxLines = 1)
        }
        HoldIconButton(a.onBookmarks, a.onBookmarkAdd, "Bookmarks. Long press to add a bookmark", h, RoundedCornerShape(16.dp), none, m, enabled = !locked) {
            Icon(Icons.Rounded.Bookmarks, null, Modifier.size(22.dp))
        }
        HoldIconButton(a.onLock, {}, if (locked) "Unlock controls" else "Lock controls", h, RoundedCornerShape(16.dp), if (locked) cs.errorContainer else none, m,
            content = if (locked) cs.error else null) {
            Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, Modifier.size(22.dp))
        }
        if (a.onConnect != null) HoldIconButton(a.onConnect, {}, "Devices", h, RoundedCornerShape(16.dp), none, m) { Icon(Icons.Rounded.Devices, null, Modifier.size(22.dp)) }
        CastButton(m)
        Box(m) {
            HoldIconButton({ menu = true }, {}, "More options", h, RoundedCornerShape(16.dp), none, Modifier.fillMaxWidth().height(h), enabled = !locked) {
                Icon(Icons.Rounded.MoreVert, null)
            }
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp)) {
                @Composable fun item(text: String, icon: ImageVector, trailing: Boolean = false, onClick: () -> Unit) = DropdownMenuItem(
                    { Text(text) }, { menu = false; onClick() }, leadingIcon = { Icon(icon, null) },
                    trailingIcon = if (trailing) { { Icon(Icons.Rounded.Check, "On") } } else null)
                item("Chapters", Icons.AutoMirrored.Rounded.FormatListBulleted, onClick = a.onChapters)
                item("Add bookmark with note", Icons.Rounded.BookmarkAdd, onClick = a.onAddWithNote)
                item("Skip silence", Icons.Rounded.GraphicEq, trailing = np.skipSilence, onClick = a.onSkipSilence)
                item("History", Icons.Rounded.History, onClick = a.onHistory)
                item("Book details", Icons.AutoMirrored.Rounded.MenuBook, onClick = a.onDetails)
                item("Settings", Icons.Rounded.Settings, onClick = a.onSettings)
            }
        }
    }
}

/** Moon, plus the time left when the timer runs. */
@Composable
private fun SleepGlyph(np: NowPlaying) {
    val sleep = np.sleep
    val on = sleep.mode != SleepMode.Off
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Bedtime, null, Modifier.size(if (on) 18.dp else 22.dp))
        if (on) Text(
            when (val mode = sleep.mode) {
                SleepMode.Off -> ""
                SleepMode.EndOfChapter -> "Zzz"
                is SleepMode.Duration -> formatClock(if (sleep.remainingMs > 0) sleep.remainingMs else mode.minutes * 60_000L)
            }, style = MaterialTheme.typography.labelSmall.merge(tnum).copy(fontSize = 9.sp), maxLines = 1)
    }
}

/** Connected Cover / Text group, shown only for books that have a text. */
@Composable
private fun ViewSwitch(text: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(!text, { onChange(false) }, Modifier.height(40.dp).semantics { role = Role.Tab },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), contentPadding = PaddingValues(horizontal = 18.dp)) {
            Text("Cover", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        ToggleButton(text, { onChange(true) }, Modifier.height(40.dp).semantics { role = Role.Tab },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), contentPadding = PaddingValues(horizontal = 20.dp)) {
            Text("Text", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun AaButton(enabled: Boolean, onClick: () -> Unit) {
    val family = remember { readerFamily(ReaderFont.LITERATA, 20) }
    HoldIconButton(onClick, {}, "Reading settings", 44.dp, RoundedCornerShape(16.dp), Color.Transparent, Modifier.size(44.dp, 48.dp), enabled = enabled) {
        Text("Aa", style = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/**
 * Tool row in text view: collapse, Cover/Text, Aa, overflow. Everything the cover's row offers is in the overflow; when
 * locked that is only the unlock.
 * ponytail: Cast stays a real route button inside the menu, since Media3 exposes only the button.
 */
@Composable
private fun TextToolRow(np: NowPlaying, locked: Boolean, a: ToolActions, onCollapse: () -> Unit, onCover: () -> Unit, onReadingSettings: () -> Unit, onFocus: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val castAvailable = remember { CastSupport.init(context) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
            .pointerInput(Unit) { var dy = 0f; detectDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 80f) onCollapse() }) { _, d -> dy += d.y } },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HoldIconButton(onCollapse, {}, "Collapse player", 48.dp, RoundedCornerShape(16.dp), Color.Transparent) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(28.dp)) }
        ViewSwitch(text = true, onChange = { if (!it) onCover() })
        Row {
            AaButton(!locked, onReadingSettings)
            Box {
                HoldIconButton({ menu = true }, {}, "More options", 44.dp, RoundedCornerShape(16.dp), Color.Transparent, Modifier.size(44.dp, 48.dp)) { Icon(Icons.Rounded.MoreVert, null) }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp)) {
                    @Composable fun item(text: String, icon: ImageVector, trailing: Boolean = false, onClick: () -> Unit) = DropdownMenuItem(
                        { Text(text) }, { menu = false; onClick() }, leadingIcon = { Icon(icon, null) },
                        trailingIcon = if (trailing) { { Icon(Icons.Rounded.Check, "On") } } else null)
                    if (locked) item("Unlock controls", Icons.Rounded.Lock, onClick = a.onLock)
                    else {
                        item("Focus reading", Icons.Rounded.CenterFocusStrong, onClick = onFocus)
                        item("Chapters", Icons.AutoMirrored.Rounded.FormatListBulleted, onClick = a.onChapters)
                        item("Bookmarks", Icons.Rounded.Bookmarks, onClick = a.onBookmarks)
                        item("Add bookmark", Icons.Rounded.BookmarkAdd, onClick = a.onBookmarkAdd)
                        item("Add bookmark with note", Icons.Rounded.BookmarkAdd, onClick = a.onAddWithNote)
                        item("Volume boost", Icons.AutoMirrored.Rounded.VolumeUp, trailing = np.boostMb > 0, onClick = a.onBoost)
                        item("Equalizer", Icons.Rounded.Equalizer, onClick = a.onEq)
                        item("Skip silence", Icons.Rounded.GraphicEq, trailing = np.skipSilence, onClick = a.onSkipSilence)
                        item("History", Icons.Rounded.History, onClick = a.onHistory)
                        item("Lock controls", Icons.Rounded.LockOpen, onClick = a.onLock)
                        a.onConnect?.let { item("Devices", Icons.Rounded.Devices, onClick = it) }
                        if (castAvailable) Row(Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            CastButton(Modifier.size(48.dp))
                            Text("Cast", style = MaterialTheme.typography.bodyLarge)
                        }
                        item("Book details", Icons.AutoMirrored.Rounded.MenuBook, onClick = a.onDetails)
                        item("Settings", Icons.Rounded.Settings, onClick = a.onSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusHeader(title: String, settingsEnabled: Boolean, onExit: () -> Unit, onReadingSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HoldIconButton(onExit, {}, "Exit focused reading", 48.dp, RoundedCornerShape(16.dp), Color.Transparent) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(28.dp)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AaButton(settingsEnabled, onReadingSettings)
    }
}

/** The small floating bar that replaces the controls in focused reading. */
@Composable
private fun FocusPill(np: NowPlaying, title: String, s: Settings, locked: Boolean, modifier: Modifier) {
    val player = MFBooksApp.get(LocalContext.current).player
    val cs = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().height(68.dp), RoundedCornerShape(34.dp), cs.surfaceContainerHigh, cs.onSurface) {
        Row(Modifier.padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatHm(np.bookDurationMs - np.positionInBookMs)} left", style = MaterialTheme.typography.labelMedium.merge(tnum), color = cs.onSurfaceVariant, maxLines = 1)
            }
            HoldIconButton({ player.skipBy(-s.rewindSmallSec * 1000L) }, { player.skipBy(-s.rewindBigSec * 1000L) }, "Rewind ${secLabel(s.rewindSmallSec)}. Long press: ${secLabel(s.rewindBigSec)}",
                48.dp, RoundedCornerShape(16.dp), Color.Transparent, enabled = !locked) { SeekIcon(s.rewindSmallSec, forward = false, desc = null, size = 26.dp) }
            PlayButton(np, 52.dp, 26.dp, RoundedCornerShape(18.dp))
            HoldIconButton({ player.skipBy(s.fwdSmallSec * 1000L) }, { player.skipBy(s.fwdBigSec * 1000L) }, "Forward ${secLabel(s.fwdSmallSec)}. Long press: ${secLabel(s.fwdBigSec)}",
                48.dp, RoundedCornerShape(16.dp), Color.Transparent, enabled = !locked) { SeekIcon(s.fwdSmallSec, forward = true, desc = null, size = 26.dp) }
        }
    }
}

/** Text with an optional dropdown chevron; clickable when [onClick] is set. */
@Composable
private fun PickerRow(text: String, style: TextStyle, chevron: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClickLabel = "Open list", onClick = onClick) else Modifier)
                .padding(start = 12.dp, end = if (chevron) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp).animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chevron) Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ChapterSeek(np: NowPlaying, enabled: Boolean) {
    val player = MFBooksApp.get(LocalContext.current).player
    val dur = np.chapterDurationMs.coerceAtLeast(1)
    val state = remember { SliderState(0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dragged by source.collectIsDraggedAsState()
    if (!pressed && !dragged && !state.isDragging) state.value = (np.positionInChapterMs.toFloat() / dur).coerceIn(0f, 1f)
    val shown = (state.value * dur).toLong()
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Slider(
            state,
            onValueChange = { state.value = it },
            onValueChangeFinished = { player.seekInChapter((state.value * dur).toLong()) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = "Chapter position ${formatClock(shown)} of ${formatClock(dur)}"
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Forward 30 seconds") { player.skipBy(30_000); true },
                    CustomAccessibilityAction("Back 30 seconds") { player.skipBy(-30_000); true },
                    CustomAccessibilityAction("Next chapter") { player.nextChapter(); true },
                    CustomAccessibilityAction("Previous chapter") { player.prevChapter(); true },
                )
            },
            interactionSource = source,
            thumb = { SliderDefaults.Thumb(source, Modifier, SliderDefaults.colors(), enabled, DpSize(5.dp, 40.dp)) },
            track = { SliderDefaults.Track(it, Modifier.height(18.dp), enabled = enabled) },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(shown), style = MaterialTheme.typography.labelLarge.merge(tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("-" + formatClock(dur - shown), style = MaterialTheme.typography.labelLarge.merge(tnum), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Big play/pause flanked by the small skips (long press = big skip), with the big skips at the ends. Every button is
 * labelled with its size. Locked: only play/pause is left.
 */
@Composable
private fun Transport(np: NowPlaying, s: Settings, locked: Boolean) {
    val player = MFBooksApp.get(LocalContext.current).player
    val cs = MaterialTheme.colorScheme
    val container = cs.onSurface.copy(alpha = 0.10f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        // Scale on narrow screens; 340 dp of content at full size.
        val k = (maxWidth / 340.dp).coerceIn(0.8f, 1.15f)
        Row(Modifier.fillMaxWidth().animateContentSize(), horizontalArrangement = if (locked) Arrangement.Center else Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            if (!locked) SkipButton(secLabel(s.rewindBigSec), "Rewind ${secLabel(s.rewindBigSec)}", 50.dp * k, 92.dp * k, container, { player.skipBy(-s.rewindBigSec * 1000L) }) {
                Icon(Icons.Rounded.FastRewind, null, Modifier.size(24.dp * k))
            }
            if (!locked) SkipButton(secLabel(s.rewindSmallSec), "Rewind ${secLabel(s.rewindSmallSec)}. Long press: ${secLabel(s.rewindBigSec)}", 66.dp * k, 92.dp * k, container,
                { player.skipBy(-s.rewindSmallSec * 1000L) }, { player.skipBy(-s.rewindBigSec * 1000L) }) {
                SeekIcon(s.rewindSmallSec, forward = false, desc = null, size = 34.dp * k)
            }
            PlayButton(np, 92.dp * k, 44.dp * k, RoundedCornerShape(30.dp), 48.dp * k)
            if (!locked) SkipButton(secLabel(s.fwdSmallSec), "Forward ${secLabel(s.fwdSmallSec)}. Long press: ${secLabel(s.fwdBigSec)}", 66.dp * k, 92.dp * k, container,
                { player.skipBy(s.fwdSmallSec * 1000L) }, { player.skipBy(s.fwdBigSec * 1000L) }) {
                SeekIcon(s.fwdSmallSec, forward = true, desc = null, size = 34.dp * k)
            }
            if (!locked) SkipButton(secLabel(s.fwdBigSec), "Forward ${secLabel(s.fwdBigSec)}", 50.dp * k, 92.dp * k, container, { player.skipBy(s.fwdBigSec * 1000L) }) {
                Icon(Icons.Rounded.FastForward, null, Modifier.size(24.dp * k))
            }
        }
    }
}

/** Text view's transport: chapter skips outside, small skips (long press = big skip) next to play. Locked: only play/pause. */
@Composable
private fun TextTransport(np: NowPlaying, s: Settings, locked: Boolean, onChapters: () -> Unit) {
    val player = MFBooksApp.get(LocalContext.current).player
    val container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val k = (maxWidth / 340.dp).coerceIn(0.8f, 1.15f)
        Row(Modifier.fillMaxWidth().animateContentSize(), horizontalArrangement = if (locked) Arrangement.Center else Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (!locked) HoldIconButton(player::prevChapter, onChapters, "Previous chapter", 56.dp * k, RoundedCornerShape(16.dp), container) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(24.dp * k)) }
            if (!locked) HoldIconButton({ player.skipBy(-s.rewindSmallSec * 1000L) }, { player.skipBy(-s.rewindBigSec * 1000L) }, "Rewind ${secLabel(s.rewindSmallSec)}. Long press: ${secLabel(s.rewindBigSec)}",
                62.dp * k, RoundedCornerShape(20.dp), container) { SeekIcon(s.rewindSmallSec, forward = false, desc = null, size = 28.dp * k) }
            PlayButton(np, 76.dp * k, 36.dp * k, RoundedCornerShape(24.dp))
            if (!locked) HoldIconButton({ player.skipBy(s.fwdSmallSec * 1000L) }, { player.skipBy(s.fwdBigSec * 1000L) }, "Forward ${secLabel(s.fwdSmallSec)}. Long press: ${secLabel(s.fwdBigSec)}",
                62.dp * k, RoundedCornerShape(20.dp), container) { SeekIcon(s.fwdSmallSec, forward = true, desc = null, size = 28.dp * k) }
            if (!locked) HoldIconButton(player::nextChapter, onChapters, "Next chapter", 56.dp * k, RoundedCornerShape(16.dp), container) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(24.dp * k)) }
        }
    }
}

/** Play/pause with the Expressive shape morph; [playingShape] is its shape while playing. */
@Composable
private fun PlayButton(np: NowPlaying, size: Dp, iconSize: Dp, playingShape: RoundedCornerShape, spinner: Dp = iconSize + 4.dp) {
    val player = MFBooksApp.get(LocalContext.current).player
    val cs = MaterialTheme.colorScheme
    FilledIconToggleButton(
        checked = np.isPlaying,
        onCheckedChange = { player.playPause() },
        modifier = Modifier.size(size),
        shapes = IconButtonDefaults.toggleableShapes(CircleShape, IconButtonDefaults.largePressedShape, playingShape),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = cs.primary, contentColor = cs.onPrimary, checkedContainerColor = cs.primary, checkedContentColor = cs.onPrimary),
    ) {
        AnimatedContent(np.isBuffering to np.isPlaying, label = "play") { (buffering, playing) ->
            when {
                buffering -> LoadingIndicator(Modifier.size(spinner), color = cs.onPrimary)
                else -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(iconSize))
            }
        }
    }
}

/** Skip button with its size as a label underneath; the label column is as wide as the button so the row stays even. */
@Composable
private fun SkipButton(label: String, desc: String, size: Dp, rowHeight: Dp, container: Color, onClick: () -> Unit, onLong: () -> Unit = onClick, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(rowHeight), Alignment.Center) { HoldIconButton(onClick, onLong, desc, size, RoundedCornerShape(size / 3), container) { icon() } }
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium.merge(tnum), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Circular-arrow icon with the seconds drawn inside; mirrored for forward. */
@Composable
internal fun SeekIcon(seconds: Int, forward: Boolean, desc: String?, size: Dp) {
    Box(Modifier.semantics { desc?.let { contentDescription = it } }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Replay, null, Modifier.size(size).graphicsLayer { if (forward) scaleX = -1f })
        Text(seconds.toString(), Modifier.padding(top = size / 9).clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall.merge(tnum).copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * (size / 30.dp)))
    }
}

/**
 * Tap = [onClick]; long press = [onLong] (with haptic). A real tonal icon button (so it keeps the Expressive press morph);
 * the long press is detected from its own press interactions, since a pointerInput tap detector would swallow the click.
 * Disabled buttons dim and ignore both. [content] overrides the icon tint.
 */
@Composable
internal fun HoldIconButton(
    onClick: () -> Unit, onLong: () -> Unit, desc: String, size: Dp, shape: RoundedCornerShape, container: Color,
    modifier: Modifier = Modifier.size(size), enabled: Boolean = true, content: Color? = null, icon: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    var longFired by remember { mutableStateOf(false) }
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val haptics = LocalHapticFeedback.current
    val latestLong by rememberUpdatedState(onLong)
    LaunchedEffect(source) {
        var job: Job? = null
        source.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> { longFired = false; job = launch { delay(timeout); longFired = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress); latestLong() } }
                is PressInteraction.Release, is PressInteraction.Cancel -> job?.cancel()
            }
        }
    }
    // Icon colour follows the container (onPrimary on primary, …); plain onSurface on transparent or translucent ones.
    val fg = content ?: MaterialTheme.colorScheme.contentColorFor(container).takeOrElse { MaterialTheme.colorScheme.onSurface }
    FilledTonalIconButton(
        onClick = { if (longFired) longFired = false else onClick() },
        modifier = modifier.semantics {
            contentDescription = desc
            customActions = listOf(CustomAccessibilityAction("Long press action") { onLong(); true })
        },
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(shape, RoundedCornerShape(12.dp)),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container, contentColor = fg,
            disabledContainerColor = Color.Transparent, disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)),
        interactionSource = source,
    ) { icon() }
}
