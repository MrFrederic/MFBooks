@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mfbooks.ui.components.app
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import app.mfbooks.data.EndOfBook
import app.mfbooks.data.Settings
import app.mfbooks.server.Server
import app.mfbooks.ui.book.BookDetailScreen
import app.mfbooks.ui.components.FloatingNav
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.NavEntry
import app.mfbooks.ui.convert.ConvertScreen
import app.mfbooks.ui.home.HomeScreen
import app.mfbooks.ui.library.LibraryScreen
import app.mfbooks.ui.library.SearchScreen
import app.mfbooks.ui.player.FullPlayer
import app.mfbooks.ui.player.MiniPlayer
import app.mfbooks.ui.server.FreeUpScreen
import app.mfbooks.ui.settings.SettingsPageScreen
import app.mfbooks.ui.settings.SettingsScreen
import app.mfbooks.ui.stats.StatsScreen
import app.mfbooks.ui.theme.MFBooksTheme

private val tabs = listOf("home", "library", "stats")
private val navEntries = listOf(
    NavEntry("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    NavEntry("Library", Icons.Outlined.LibraryBooks, Icons.Rounded.LibraryBooks),
    NavEntry("Stats", Icons.Outlined.Insights, Icons.Rounded.Insights),
)

@Composable
fun MFBooksRoot() {
    val app = app(LocalContext.current)
    val settings by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val now by app.player.state.collectAsStateWithLifecycle()
    MFBooksTheme(settings.theme, settings.colorSource, now.book?.seedColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Shell(hasBook = now.book != null) }
    }
}

/** Every destination draws on an opaque background, so fades and the predictive-back preview never show the page below through it. */
private fun NavGraphBuilder.page(route: String, arguments: List<NamedNavArgument> = emptyList(), content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route, arguments) { entry -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) } }

private val chromeSpring = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

/**
 * Chrome slot that is [shown] (0..1) of its height: the content slides down out of the shrinking slot and fades, and
 * everything stacked above it (the mini player above the toolbar) moves down in the same frames.
 */
private fun Modifier.collapse(shown: Float) = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, (p.height * shown).roundToInt()) { p.placeWithLayer(0, 0) { alpha = shown } }
}

/**
 * Screens draw edge to edge; the floating chrome (snackbar, pill mini player, floating toolbar) sits on top at the
 * bottom and publishes its height through [LocalChromePadding] so lists can scroll clear of it. Chrome pieces appear
 * and vanish by growing/shrinking their own slot, so the mini player glides down with the toolbar instead of jumping.
 * ponytail: the floating toolbar is used on every width; no NavigationRail variant for tablets.
 */
@Composable
private fun Shell(hasBook: Boolean) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(0.dp) }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val tab = tabs.indexOf(route)
    val searching = route == "search"

    fun openBook(id: String) = nav.navigate("book/$id")
    fun openSettings() = nav.navigate("settings") { launchSingleTop = true }
    // Search is a page of its own on top of wherever it was opened, so back returns there.
    fun openSearch() = nav.navigate("search") { launchSingleTop = true }
    // Standard bottom-nav pattern: one copy of each tab, state kept per tab, back from any tab lands on Home.
    fun go(i: Int) = nav.navigate(tabs[i]) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }

    val ctx = LocalContext.current
    // A pairing QR code scanned with the camera opens the Server page with the code filled in.
    val pair by Server.pendingPair.collectAsStateWithLifecycle()
    LaunchedEffect(pair) { if (pair != null) nav.navigate("settings/server") { launchSingleTop = true } }
    LaunchedEffect(Unit) {
        // End of book with "Show library": collapse the player and land on the Library tab.
        app(ctx).player.bookEnded.collect {
            if (app(ctx).prefs.now().endOfBook == EndOfBook.SHOW_LIBRARY) { expanded = false; go(1) }
        }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalChromePadding provides chrome) {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize(),
                // Only the page on top fades; the one underneath stays opaque, so there is never a see-through mix of
                // two pages. Predictive back uses the same fade instead of the default scale-down.
                enterTransition = { fadeIn() }, exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { fadeOut() },
                predictivePopEnterTransition = { EnterTransition.None }, predictivePopExitTransition = { fadeOut() }) {
                page("home") { HomeScreen(::openBook, ::openSettings) }
                page("library") { LibraryScreen(::openBook, ::openSettings) { nav.navigate("settings/server") { launchSingleTop = true } } }
                page("search") { SearchScreen(::openBook) { nav.popBackStack() } }
                page("stats") { StatsScreen(::openSettings) }
                page("book/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                    BookDetailScreen(it.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
                }
                page("settings") { SettingsScreen({ nav.popBackStack() }, nav::navigate) }
                page("settings/{page}", listOf(navArgument("page") { type = NavType.StringType })) {
                    SettingsPageScreen(it.arguments?.getString("page"), { nav.popBackStack() }, nav::navigate)
                }
                page("convert") { ConvertScreen { nav.popBackStack() } }
                page("freeup") { FreeUpScreen { nav.popBackStack() } }
            }
            // Status-bar scrim so scrolled content doesn't fight the clock and icons.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), Color.Transparent))))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { chrome = with(density) { it.height.toDp() } }
                    .navigationBarsPadding().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnackbarHost(snackbar)
                val miniShown by animateFloatAsState(if (hasBook && !searching) 1f else 0f, chromeSpring, label = "mini")
                if (miniShown > 0f) Box(Modifier.collapse(miniShown)) {
                    MiniPlayer(onExpand = { expanded = true }, modifier = Modifier.padding(top = 10.dp).padding(horizontal = 12.dp))
                }
                val navShown by animateFloatAsState(if (tab >= 0) 1f else 0f, chromeSpring, label = "nav")
                // Keep the last tab while the toolbar is leaving, so the pill doesn't jump on its way out.
                var lastTab by remember { mutableIntStateOf(0) }
                if (tab >= 0) lastTab = tab
                if (navShown > 0f) Box(Modifier.collapse(navShown)) {
                    FloatingNav(navEntries, lastTab, { go(it) }, onSearch = ::openSearch, modifier = Modifier.padding(top = 10.dp))
                }
            }
            AnimatedVisibility(expanded && hasBook, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                // Composed after the NavHost, so this callback is registered last and wins over popping a page hidden
                // underneath. Predictive back drags the player down a little before it collapses.
                var progress by remember { mutableFloatStateOf(0f) }
                PredictiveBackHandler(expanded) { events ->
                    try {
                        events.collect { progress = it.progress }
                        expanded = false
                    } catch (e: CancellationException) {
                        progress = 0f
                        throw e
                    }
                }
                FullPlayer(
                    onCollapse = { expanded = false }, onOpenBook = { expanded = false; openBook(it) },
                    onSettings = { expanded = false; openSettings() },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationY = progress * size.height * 0.1f
                        val k = 1f - progress * 0.05f
                        scaleX = k; scaleY = k
                    },
                )
            }
        }
    }
}
