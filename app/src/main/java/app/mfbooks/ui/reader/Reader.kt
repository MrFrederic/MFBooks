@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.data.BookText
import app.mfbooks.data.Bookmark
import app.mfbooks.data.HighlightStyle
import app.mfbooks.data.ReaderDensity
import app.mfbooks.data.ReaderFont
import app.mfbooks.data.Settings
import app.mfbooks.data.TextState
import app.mfbooks.data.Track
import app.mfbooks.playback.NowPlaying
import app.mfbooks.playback.PlayerConnection
import app.mfbooks.text.Block
import app.mfbooks.text.BlockKind
import app.mfbooks.text.BookTextDoc
import app.mfbooks.text.RangeKind
import app.mfbooks.text.SyncDoc
import app.mfbooks.text.Approximate
import app.mfbooks.text.ChapterRef
import app.mfbooks.text.SyncMap
import app.mfbooks.text.SyncPosition
import app.mfbooks.text.SyncTrack
import app.mfbooks.text.UserKnot
import app.mfbooks.text.blockStarts
import app.mfbooks.text.withUserKnot
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.formatClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** Added to the lookup: readers notice a highlight that lags more than one that leads slightly. */
private const val LEAD_MS = 100L
/** Between polls the clock runs on its own, but never further than this if the polls stop. */
private const val MAX_EXTRAPOLATE_MS = 1_000L
// ponytail: map lookups run at 25 Hz, not per frame. SyncMap.at returns an object, and no word is shorter than 40 ms.
private const val LOOKUP_STEP_MS = 40L

private val BAR_SPRING = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
/** A smaller step back is clock disagreement between the 250 ms position update and the frame clock, not a seek. */
private const val CLOCK_JITTER_MS = 400L

internal fun readerFamily(font: ReaderFont, opsz: Int): FontFamily = when (font) {
    ReaderFont.LITERATA -> {
        fun face(w: Int, weight: FontWeight) = Font(R.font.literata, weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz.coerceIn(7, 72).toFloat())))
        FontFamily(face(400, FontWeight.Normal), face(500, FontWeight.Medium))
    }
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
}

private class BlockStyles(private val base: TextStyle, private val size: Int, lineFactor: Float) {
    private fun heading(k: Float) = base.copy(fontSize = (size * k).sp, lineHeight = (size * k * 1.3f).sp, fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Start, hyphens = Hyphens.None)
    private val h1 = heading(1.37f); private val h2 = heading(1.2f); private val h3 = heading(1.08f)
    private val poem = base.copy(textAlign = TextAlign.Start, hyphens = Hyphens.None)
    private val note = base.copy(fontSize = (size * 0.82f).sp, lineHeight = (size * 0.82f * lineFactor).sp)
    private val body = base
    fun of(k: String) = when (k) {
        BlockKind.H1 -> h1; BlockKind.H2 -> h2; BlockKind.H3 -> h3
        BlockKind.POEM -> poem; BlockKind.NOTE -> note
        else -> body
    }
}

private data class Look(
    val style: HighlightStyle, val narrated: Color, val upcoming: Color, val current: Color,
    val word: Color, val band: Color, val playhead: Color, val barX: Dp,
)

/** Lines of [a] until [b] as `left, top, right, bottom, baseline` per line. */
private fun lineRects(l: TextLayoutResult, a: Int, b: Int): FloatArray {
    val first = l.getLineForOffset(a); val last = l.getLineForOffset(b - 1)
    val out = FloatArray((last - first + 1) * 5)
    for (n in first..last) {
        val k = (n - first) * 5
        out[k] = if (n == first) l.getBoundingBox(a).left else l.getLineLeft(n)
        out[k + 1] = l.getLineTop(n)
        out[k + 2] = if (n == last) l.getBoundingBox(b - 1).right else l.getLineRight(n)
        out[k + 3] = l.getLineBottom(n)
        out[k + 4] = l.getLineBaseline(n)
    }
    return out
}

private val NO_RECTS = FloatArray(0)

/** Everything the active block draws. Rebuilt only when its layout, sentence or word changes, never per frame. */
private class Geometry {
    private var layout: TextLayoutResult? = null
    private var sentence = -1L
    private var word = -1L
    var past = Path(); var current = Path(); var upcoming = Path(); var wordPath = Path()
    var band = NO_RECTS; var wordLines = NO_RECTS
    var hasSentence = false
    var barTop = 0f; var barBottom = 0f
    var line = 0; var lineTop = 0f; var lineBottom = 0f

    fun sync(l: TextLayoutResult, s: Long, w: Long) {
        val relaid = l !== layout
        val len = l.layoutInput.text.length
        var a = rangeStart(s).coerceIn(0, len); val b = rangeEnd(s).coerceIn(a, len)
        if (relaid || s != sentence) {
            past = l.getPathForRange(0, a); current = l.getPathForRange(a, b); upcoming = l.getPathForRange(b, len)
            hasSentence = b > a
            band = if (hasSentence) lineRects(l, a, b) else NO_RECTS
            if (hasSentence) { barTop = band[1]; barBottom = band[band.size - 2] }
        }
        if (relaid || w != word || s != sentence) {
            val wa = rangeStart(w).coerceIn(0, len); val wb = rangeEnd(w).coerceIn(wa, len)
            wordPath = l.getPathForRange(wa, wb)
            wordLines = if (wb > wa) lineRects(l, wa, wb) else NO_RECTS
            a = if (wb > wa) wa else a
            line = l.getLineForOffset(a); lineTop = l.getLineTop(line); lineBottom = l.getLineBottom(line)
        }
        layout = l; sentence = s; word = w
    }
}

private class SentenceTarget(val block: Int, val range: Long, val text: String, val char: Int, val rects: FloatArray)

/**
 * Holds the playback-driven state. Per-word values are read only from draw lambdas, so a new word invalidates the active
 * block's drawing and nothing else; the list never recomposes for it.
 */
@Stable
private class ReadAlong(val doc: BookTextDoc, val player: PlayerConnection, val list: LazyListState, val scope: CoroutineScope, val reduced: Boolean) {
    val starts = doc.blockStarts()
    val layouts = arrayOfNulls<TextLayoutResult>(doc.blocks.size)
    private val texts = arrayOfNulls<AnnotatedString>(doc.blocks.size)

    var activeBlock by mutableIntStateOf(-1)
    var sentence by mutableLongStateOf(0L)
    var word by mutableLongStateOf(0L)
    var kind by mutableStateOf(RangeKind.WORD)
    var activeLayout by mutableStateOf<TextLayoutResult?>(null)
    var relaid by mutableIntStateOf(0)
    var following by mutableStateOf(true)
    var look by mutableStateOf<Look?>(null)
    var menu by mutableStateOf<SentenceTarget?>(null)

    var map: SyncMap? = null
    var syncDoc: SyncDoc? = null
    var offsetMs = 0
    var anchor = FOLLOW_ANCHOR
    var currentMs = 0L; private set

    private val geometry = Geometry()
    fun geometry(): Geometry? {
        val l = activeLayout ?: return null
        geometry.sync(l, sentence, word)
        return geometry
    }

    fun text(i: Int): AnnotatedString = texts[i] ?: build(doc.blocks[i]).also { texts[i] = it }

    private fun build(b: Block): AnnotatedString {
        if (b.em.isEmpty()) return AnnotatedString(b.s)
        return buildAnnotatedString {
            append(b.s)
            b.em.forEach { r -> if (r.size == 2) addStyle(SpanStyle(fontStyle = FontStyle.Italic), r[0].coerceIn(0, b.s.length), r[1].coerceIn(0, b.s.length)) }
        }
    }

    fun onLayout(i: Int, r: TextLayoutResult) {
        layouts[i] = r
        if (i == activeBlock) { activeLayout = r; relaid++ }
    }

    fun onDisposed(i: Int) {
        layouts[i] = null
        if (i == activeBlock) activeLayout = null
    }

    fun blockColor(i: Int, k: String): Color {
        val l = look ?: return Color.Unspecified
        val a = activeBlock
        val c = when { a < 0 -> l.current; i < a -> l.narrated; else -> l.upcoming }
        return if (k == BlockKind.NOTE) c.copy(alpha = c.alpha * 0.75f) else c
    }

    // ---- clock ---------------------------------------------------------------------------------------------------

    private var lastNp: NowPlaying? = null
    private var anchorMs = 0L
    private var anchorPlaying = false
    private var anchorNanos = 0L
    private var lastLookup = Long.MIN_VALUE / 2

    fun invalidateLookup() { lastLookup = Long.MIN_VALUE / 2 }

    fun tick(frameNanos: Long) {
        val np = player.state.value
        if (np !== lastNp) {
            lastNp = np
            if (np.positionInBookMs != anchorMs || np.isPlaying != anchorPlaying) {
                anchorMs = np.positionInBookMs; anchorPlaying = np.isPlaying; anchorNanos = frameNanos
            }
        }
        val elapsed = if (np.isPlaying) min(MAX_EXTRAPOLATE_MS, (frameNanos - anchorNanos) / 1_000_000) else 0L
        val now = anchorMs + (elapsed * np.speed).toLong()
        // Stepping back here would flip a sentence to and fro at its boundary and shake the playhead.
        currentMs = if (np.isPlaying && now < currentMs && currentMs - now < CLOCK_JITTER_MS) currentMs else now
        val lookup = currentMs + offsetMs + LEAD_MS
        if (abs(lookup - lastLookup) < LOOKUP_STEP_MS) return
        lastLookup = lookup
        map?.let { apply(it.at(lookup.toInt().coerceAtLeast(0))) }
    }

    private fun apply(p: SyncPosition) {
        val total = starts.last()
        if (total == 0) return
        val char = p.char.coerceIn(0, total - 1)
        val b = blockIndexOfChar(starts, char)
        val block = doc.blocks[b]
        val off = char - starts[b]
        val s: Long; val w: Long
        when (p.kind) {
            RangeKind.APPROX -> { s = trimmedRange(block.s, 0, block.s.length); w = 0L }
            RangeKind.NONE -> { s = packRange(off, off); w = 0L }
            else -> { s = sentenceRangeAt(block.s, block.sen, off); w = wordRangeAt(block.s, off) }
        }
        val blockChanged = b != activeBlock
        val sentenceChanged = blockChanged || s != sentence
        if (!sentenceChanged && w == word && p.kind == kind) return
        if (p.kind != kind) kind = p.kind
        if (blockChanged) { activeBlock = b; activeLayout = layouts[b] }
        sentence = s; word = w
        val g = geometry()
        if (sentenceChanged) slideBar(g)
        if (p.kind != RangeKind.NONE && (blockChanged || g == null || g.line != followedLine)) {
            followedLine = g?.line ?: -1
            requestFollow(animated = true)
        }
    }

    // ---- playhead ------------------------------------------------------------------------------------------------

    val deltaTop = Animatable(0f)
    val deltaBottom = Animatable(0f)
    var drawnTop = Float.NaN
    var drawnBottom = Float.NaN
    private var slide: Job? = null

    private fun slideBar(g: Geometry?) {
        if (reduced || g == null || !g.hasSentence || drawnTop.isNaN()) return
        val item = itemInfo(activeBlock) ?: return
        val pad = list.layoutInfo.beforeContentPadding
        val dTop = drawnTop - (pad + item.offset + g.barTop)
        val dBottom = drawnBottom - (pad + item.offset + g.barBottom)
        if (abs(dTop) > list.layoutInfo.viewportSize.height) return
        slide?.cancel()
        slide = scope.launch {
            deltaTop.snapTo(dTop); deltaBottom.snapTo(dBottom)
            launch { deltaTop.animateTo(0f, BAR_SPRING) }
            launch { deltaBottom.animateTo(0f, BAR_SPRING) }
        }
    }

    // ---- follow --------------------------------------------------------------------------------------------------

    private var followedLine = -1
    private var followJob: Job? = null

    fun itemInfo(index: Int): LazyListItemInfo? {
        val items = list.layoutInfo.visibleItemsInfo
        for (k in items.indices) if (items[k].index == index) return items[k]
        return null
    }

    fun requestFollow(animated: Boolean) { if (following) scrollToNarration(animated) }

    fun scrollToNarration(animated: Boolean) {
        val b = activeBlock
        if (b < 0) return
        followJob?.cancel()
        followJob = scope.launch {
            val viewport = list.layoutInfo.viewportSize.height
            val item = itemInfo(b)
            val g = geometry()
            if (item == null || g == null) { list.scrollToItem(b, -(viewport * anchor).toInt()); return@launch }
            val d = followDelta(item.offset + g.lineTop, viewport, anchor)
            if (abs(d) < 1f) return@launch
            if (animated && !reduced) list.animateScrollBy(d, tween(420, easing = FastOutSlowInEasing)) else list.scrollBy(d)
        }
    }

    fun stopFollowing() { following = false; followJob?.cancel() }

    /** -1 narration above the viewport, 1 below, 0 on screen. */
    fun side(): Int {
        val b = activeBlock
        if (b < 0) return 0
        val item = itemInfo(b) ?: return if (b < list.firstVisibleItemIndex) -1 else 1
        val g = geometry() ?: return 0
        return narrationSide(item.offset + g.lineTop, item.offset + g.lineBottom, list.layoutInfo.viewportSize.height)
    }

    fun arrowDown(): Boolean {
        val s = side()
        if (s != 0) return s > 0
        val item = itemInfo(activeBlock) ?: return true
        val g = geometry() ?: return true
        return item.offset + g.lineTop > list.layoutInfo.viewportSize.height * anchor
    }
}

/**
 * The book's text with the narration highlighted. [anchor] is where the current line sits (moved up while the settings
 * sheet covers the lower part); [bottomInset] keeps the pills clear of floating controls.
 */
@Composable
internal fun Reader(
    book: Book, text: BookText, tracks: List<Track>, settings: Settings, locked: Boolean,
    anchor: Float, bottomInset: Dp, modifier: Modifier = Modifier,
) {
    val app = MFBooksApp.get(LocalContext.current)
    val doc by produceState<BookTextDoc?>(null, book.id, text.textSha) {
        value = null
        value = withContext(Dispatchers.IO) { app.textStore.readText(book.id) }
    }
    val d = doc
    if (d == null) { Box(modifier); return }
    key(d) { ReaderBody(book, text, tracks, d, settings, locked, anchor, bottomInset, modifier) }
}

@Composable
private fun ReaderBody(
    book: Book, text: BookText, tracks: List<Track>, doc: BookTextDoc, settings: Settings, locked: Boolean,
    anchor: Float, bottomInset: Dp, modifier: Modifier,
) {
    val context = LocalContext.current
    val app = MFBooksApp.get(context)
    val player = app.player
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val reduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val ra = remember(doc, list, reduced) { ReadAlong(doc, player, list, scope, reduced) }
    var map by remember { mutableStateOf<SyncMap?>(null) }
    SideEffect { ra.offsetMs = text.offsetMs }
    LaunchedEffect(ra, anchor) { ra.anchor = anchor; ra.requestFollow(animated = true) }

    // Colours are drawn per block from Look; nothing recomposes the list when they or the position change.
    val margin = when (settings.readerMargins) { ReaderDensity.COMPACT -> 28.dp to 16.dp; ReaderDensity.NORMAL -> 34.dp to 24.dp; ReaderDensity.RELAXED -> 46.dp to 40.dp }
    val style = settings.highlightStyle
    val on = cs.onSurface
    val next = if (style == HighlightStyle.WORD || style == HighlightStyle.OFF) on else on.copy(alpha = 0.82f)
    val narrated = if (style == HighlightStyle.OFF) on else if (settings.dimNarrated) on.copy(alpha = 0.6f) else next
    SideEffect { ra.look = Look(style, narrated, next, on, cs.primary, cs.secondaryContainer, cs.primary, margin.first - 20.dp) }

    LaunchedEffect(book.id, text.syncSha, tracks) {
        val (sd, m) = withContext(Dispatchers.Default) {
            val current = tracks.map { t -> SyncTrack(t.fileName, t.durationMs) }
            val saved = app.textStore.readSync(book.id)
            saved?.let { SyncMap.of(it, current) }?.let { return@withContext saved to it }
            // The audio changed under this map: stay readable with an estimate. The saved map and transcript stay on disk.
            val doc = app.textStore.readText(book.id) ?: return@withContext saved to null
            val chapters = app.db.library().chapters(book.id).map { ChapterRef(it.title, it.startInBookMs.toInt()) }
            val estimate = Approximate.build(doc, text.textSha, current, chapters)
            estimate to SyncMap.of(estimate, current)
        }
        ra.syncDoc = sd
        ra.map = m
        map = m
    }
    LaunchedEffect(ra, map, text.offsetMs) {
        ra.invalidateLookup()
        if (map == null) return@LaunchedEffect
        val onFrame: (Long) -> Unit = ra::tick
        while (true) {
            withFrameNanos(onFrame)
            val np = player.state.value
            if (!np.isPlaying) { player.state.first { it.isPlaying || it.positionInBookMs != np.positionInBookMs }; ra.invalidateLookup() }
        }
    }
    LaunchedEffect(ra) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) ra.stopFollowing() } }
    LaunchedEffect(ra, settings.followNarration) { ra.following = settings.followNarration; ra.requestFollow(animated = false) }
    val followOn by rememberUpdatedState(settings.followNarration)
    LaunchedEffect(ra) { player.state.map { it.isPlaying }.distinctUntilChanged().collect { if (it && followOn) { ra.following = true; ra.requestFollow(true) } } }
    LaunchedEffect(ra) { snapshotFlow { ra.relaid }.collect { ra.requestFollow(animated = false) } }

    val lineFactor = when (settings.readerLineSpacing) { ReaderDensity.COMPACT -> 1.4f; ReaderDensity.NORMAL -> 1.63f; ReaderDensity.RELAXED -> 1.85f }
    val styles = remember(settings.readerFont, settings.readerSizeSp, lineFactor, settings.readerJustify, doc.lang) {
        val sp = settings.readerSizeSp
        BlockStyles(TextStyle(
            fontFamily = readerFamily(settings.readerFont, sp), fontSize = sp.sp, lineHeight = (sp * lineFactor).sp,
            textAlign = if (settings.readerJustify) TextAlign.Justify else TextAlign.Start,
            hyphens = if (settings.readerJustify) Hyphens.Auto else Hyphens.None,
            lineBreak = LineBreak.Paragraph,
            localeList = LocaleList(when (doc.lang) { "ru" -> "ru-RU"; "en" -> "en-US"; "" -> "en-US"; else -> doc.lang }),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ), sp, lineFactor)
    }
    val gap = when (settings.readerLineSpacing) { ReaderDensity.COMPACT -> 12.dp; ReaderDensity.NORMAL -> 18.dp; ReaderDensity.RELAXED -> 24.dp }

    fun positionFor(char: Int): Long? = map?.let { (it.msAt(char).toLong() - text.offsetMs).coerceAtLeast(0) }

    fun hit(i: Int, pos: Offset): SentenceTarget? {
        val l = ra.layouts[i] ?: return null
        val b = doc.blocks[i]
        if (b.s.isEmpty()) return null
        val r = sentenceRangeAt(b.s, b.sen, l.getOffsetForPosition(pos))
        val a = rangeStart(r); val e = rangeEnd(r)
        if (e <= a) return null
        return SentenceTarget(i, r, b.s.substring(a, e), ra.starts[i] + a, lineRects(l, a, e))
    }

    fun seekTo(char: Int) {
        val to = positionFor(char) ?: return
        val back = player.state.value.positionInBookMs
        player.seekInBook(to)
        if (settings.followNarration) ra.following = true
        ra.invalidateLookup()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar("Playing from here", "Undo") == SnackbarResult.ActionPerformed) player.seekInBook(back)
        }
    }

    val onTap by rememberUpdatedState { i: Int, pos: Offset -> if (!locked && settings.tapToSeek) hit(i, pos)?.let { seekTo(it.char) } }
    val onLong by rememberUpdatedState { i: Int, pos: Offset -> if (!locked) hit(i, pos)?.let { ra.menu = it } }
    val a11ySeek by rememberUpdatedState { i: Int -> seekTo(ra.starts[i]) }
    val a11yMenu by rememberUpdatedState { i: Int ->
        val b = doc.blocks[i]
        val r = sentenceRangeAt(b.s, b.sen, 0)
        val l = ra.layouts[i]
        if (l != null && rangeEnd(r) > rangeStart(r)) ra.menu = SentenceTarget(i, r, b.s.substring(rangeStart(r), rangeEnd(r)), ra.starts[i] + rangeStart(r), lineRects(l, rangeStart(r), rangeEnd(r)))
    }

    Column(modifier) {
        AnimatedVisibility(ra.kind == RangeKind.APPROX && text.state != TextState.READY, Modifier.align(Alignment.CenterHorizontally)) {
            Surface(Modifier.padding(top = 4.dp, bottom = 2.dp).height(32.dp), RoundedCornerShape(16.dp), cs.secondaryContainer, cs.onSecondaryContainer) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator({ text.coverage.coerceIn(0f, 1f) }, Modifier.size(16.dp), cs.primary, 3.dp, cs.onSecondaryContainer.copy(alpha = 0.3f))
                    Text("Approximate", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LaunchedEffect(ra, maxHeight) { ra.requestFollow(animated = false) }
            LazyColumn(
                Modifier.fillMaxSize().fadeEdges(), list,
                contentPadding = PaddingValues(top = 28.dp, bottom = maxHeight * 0.66f),
            ) {
                items(doc.blocks.size, contentType = { doc.blocks[it].k }) { i ->
                    val b = doc.blocks[i]
                    val after = if (i + 1 < doc.blocks.size && doc.blocks[i + 1].k.startsWith("h")) gap * 2 else gap
                    if (b.k == BlockKind.IMAGE) Illustration(Modifier.padding(start = margin.first, end = margin.second, bottom = after))
                    else {
                        val indent = when (b.k) { BlockKind.QUOTE -> 16.dp; BlockKind.POEM -> 24.dp; else -> 0.dp }
                        BlockText(i, b, ra, styles, locked, Modifier.padding(start = margin.first + indent, end = margin.second, bottom = after),
                            onTap = { p -> onTap(i, p) }, onLong = { p -> onLong(i, p) }, onA11ySeek = { a11ySeek(i) }, onA11yMenu = { a11yMenu(i) })
                    }
                }
            }
            Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind { drawPlayhead(ra) })

            val showBack by remember(ra, settings.followNarration) {
                derivedStateOf { ra.activeBlock >= 0 && !ra.following && (settings.followNarration || ra.side() != 0) }
            }
            val down by remember(ra) { derivedStateOf { ra.arrowDown() } }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(ra.kind == RangeKind.NONE && map != null, enter = fadeIn(), exit = fadeOut()) {
                    Surface(Modifier.height(36.dp), RoundedCornerShape(18.dp), cs.surfaceContainerHighest, cs.onSurface) {
                        Box(Modifier.padding(horizontal = 16.dp), Alignment.Center) { Text("Not in the text", style = MaterialTheme.typography.labelLarge) }
                    }
                }
                AnimatedVisibility(showBack, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
                    Button({ ra.following = settings.followNarration; ra.scrollToNarration(animated = true) }, modifier = Modifier.height(44.dp), shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(start = 14.dp, end = 20.dp)) {
                        Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(20.dp).graphicsLayer { rotationZ = if (down) 0f else 180f })
                        Text("Back to narration", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    ra.menu?.let { t ->
        val m = map
        val at = m?.let { positionFor(t.char) }
        val np = player.state.value
        val ch = at?.let { p -> np.chapters.lastOrNull { it.startInBookMs <= p } }
        val where = if (at != null) "${ch?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${(ch?.index ?: 0) + 1}"}, at ${formatClock(at - (ch?.startInBookMs ?: 0))}" else null
        SentenceSheet(t.text, where, onDismiss = { ra.menu = null },
            onPlay = at?.let { p -> { player.seekInBook(p); if (!player.state.value.isPlaying) player.playPause(); if (settings.followNarration) ra.following = true } },
            onBookmark = if (at != null && ch != null) { {
                val now = System.currentTimeMillis()
                scope.launch {
                    // Same table as player.addBookmark, but at the sentence rather than wherever the narrator is now.
                    app.db.userData().insertBookmark(Bookmark(bookId = book.id, trackIndex = ch.trackIndex, positionMs = ch.startInTrackMs + at - ch.startInBookMs,
                        title = t.text, note = null, createdAt = now))
                    snackbar.showSnackbar("Bookmark added")
                }
            } } else null,
            onCopy = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Sentence", t.text)) },
            onNarratorHere = ra.syncDoc?.takeIf { m != null }?.let { sd -> {
                val knot = UserKnot(t.char, (ra.currentMs + text.offsetMs).toInt())
                scope.launch(Dispatchers.Default) { app.textStore.writeSync(book.id, sd.withUserKnot(knot), text.syncedAudioMs) }
                Unit
            } },
        )
    }
}

private fun Modifier.fadeEdges() = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
    val h = size.height.coerceAtLeast(1f)
    val top = (28.dp.toPx() / h).coerceAtMost(0.5f)
    val bottom = 1f - (44.dp.toPx() / h).coerceAtMost(0.5f)
    val brush = Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, bottom to Color.Black, 1f to Color.Transparent)
    onDrawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.DstIn) }
}

@Composable
private fun Illustration(modifier: Modifier) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Image, null, Modifier.size(16.dp), tint = c)
        Text("Illustration", style = MaterialTheme.typography.labelMedium, color = c)
    }
}

@Composable
private fun BlockText(
    i: Int, block: Block, ra: ReadAlong, styles: BlockStyles, locked: Boolean, padding: Modifier,
    onTap: (Offset) -> Unit, onLong: (Offset) -> Unit, onA11ySeek: () -> Unit, onA11yMenu: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val color = remember(ra, i, block.k) { ColorProducer { ra.blockColor(i, block.k) } }
    DisposableEffect(ra, i) { onDispose { ra.onDisposed(i) } }
    BasicText(
        ra.text(i),
        Modifier.fillMaxWidth().then(padding).graphicsLayer().drawActive(i, ra)
            .pointerInput(ra, i) { detectTapGestures(onLongPress = { long(it) }, onTap = { tap(it) }) }
            .semantics {
                if (!locked) {
                    onClick("Play from here") { onA11ySeek(); true }
                    onLongClick("Sentence options") { onA11yMenu(); true }
                }
            },
        style = styles.of(block.k),
        onTextLayout = { ra.onLayout(i, it) },
        color = color,
    )
}

/** Draws the active block's colours by clipping the laid-out text per range: no AnnotatedString is rebuilt when they change. */
private fun Modifier.drawActive(i: Int, ra: ReadAlong) = drawWithContent {
    val look = ra.look
    val menu = ra.menu
    if (menu != null && menu.block == i && look != null) drawBand(menu.rects, look.band)
    if (ra.activeBlock != i || look == null || look.style == HighlightStyle.OFF) { drawContent(); return@drawWithContent }
    val l = ra.activeLayout
    val g = ra.geometry()
    if (l == null || g == null) { drawContent(); return@drawWithContent }
    if (look.style == HighlightStyle.BAND) drawBand(g.band, look.band)
    clipPath(g.past) { drawText(l, look.narrated) }
    clipPath(g.upcoming) { drawText(l, look.upcoming) }
    clipPath(g.current) { clipPath(g.wordPath, ClipOp.Difference) { drawText(l, look.current) } }
    clipPath(g.wordPath) { drawText(l, look.word) }
    val rects = g.wordLines
    var k = 0
    while (k < rects.size) {
        drawRect(look.word, Offset(rects[k], rects[k + 4] + 4.dp.toPx()), Size(rects[k + 2] - rects[k], 2.dp.toPx()))
        k += 5
    }
}

private fun DrawScope.drawBand(rects: FloatArray, color: Color) {
    val pad = 4.dp.toPx(); val inset = 1.dp.toPx(); val r = CornerRadius(7.dp.toPx())
    var k = 0
    while (k < rects.size) {
        drawRoundRect(color, Offset(rects[k] - pad, rects[k + 1] + inset), Size(rects[k + 2] - rects[k] + 2 * pad, rects[k + 3] - rects[k + 1] - 2 * inset), r)
        k += 5
    }
}

private fun DrawScope.drawPlayhead(ra: ReadAlong) {
    val look = ra.look ?: return
    if (look.style != HighlightStyle.PLAYHEAD || ra.activeBlock < 0) return
    val x = look.barX.toPx(); val w = 4.dp.toPx(); val r = CornerRadius(2.dp.toPx())
    val side = ra.side()
    if (side != 0) {
        val stub = 22.dp.toPx(); val overdraw = 4.dp.toPx()
        drawRoundRect(look.playhead, Offset(x, if (side < 0) -overdraw else size.height - stub), Size(w, stub + overdraw), r)
        ra.drawnTop = Float.NaN
        return
    }
    val item = ra.itemInfo(ra.activeBlock) ?: return
    val g = ra.geometry() ?: return
    if (!g.hasSentence) { ra.drawnTop = Float.NaN; return }
    val pad = ra.list.layoutInfo.beforeContentPadding // item offsets start after the list's top padding
    val top = pad + item.offset + g.barTop + ra.deltaTop.value
    val bottom = pad + item.offset + g.barBottom + ra.deltaBottom.value
    ra.drawnTop = top; ra.drawnBottom = bottom
    drawRoundRect(look.playhead, Offset(x, top), Size(w, (bottom - top).coerceAtLeast(w)), r)
}
