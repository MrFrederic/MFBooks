@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mfbooks.data.EndOfBook
import app.mfbooks.data.Settings
import app.mfbooks.ui.book.BookDetailScreen
import app.mfbooks.ui.components.FloatingNav
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.NavEntry
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.chromeHidden
import app.mfbooks.ui.home.HomeScreen
import app.mfbooks.ui.library.LibraryScreen
import app.mfbooks.ui.player.FullPlayer
import app.mfbooks.ui.player.MiniPlayer
import app.mfbooks.ui.convert.ConvertScreen
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

/**
 * Screens draw edge to edge; the floating chrome (snackbar, pill mini player, floating toolbar) sits on top at the
 * bottom and publishes its height through [LocalChromePadding] so lists can scroll clear of it.
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
    val tab = tabs.indexOfFirst { route.startsWith(it) }
    BackHandler(expanded && hasBook) { expanded = false }

    fun openBook(id: String) = nav.navigate("book/$id")
    fun openSettings() = nav.navigate("settings")
    fun go(i: Int, search: Boolean = false) = nav.navigate(if (search) "library?search=true" else tabs[i]) {
        popUpTo("home") { saveState = !search }
        launchSingleTop = true; restoreState = !search
    }

    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        // End of book with "Show library": collapse the player and land on the Library tab.
        app(ctx).player.bookEnded.collect {
            if (app(ctx).prefs.now().endOfBook == EndOfBook.SHOW_LIBRARY) { expanded = false; go(1) }
        }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalChromePadding provides chrome) {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                composable("home") { HomeScreen(::openBook, { go(1, search = true) }, ::openSettings) }
                composable(
                    "library?search={search}",
                    arguments = listOf(navArgument("search") { type = NavType.BoolType; defaultValue = false }),
                ) { LibraryScreen(it.arguments?.getBoolean("search") == true, ::openBook, ::openSettings) }
                composable("stats") { StatsScreen(::openSettings) }
                composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    BookDetailScreen(it.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
                }
                composable("settings") { SettingsScreen({ nav.popBackStack() }, nav::navigate) }
                composable("settings/{page}", arguments = listOf(navArgument("page") { type = NavType.StringType })) {
                    SettingsPageScreen(it.arguments?.getString("page"), { nav.popBackStack() }, nav::navigate)
                }
                composable("convert") { ConvertScreen { nav.popBackStack() } }
            }
            // Status-bar scrim so scrolled content doesn't fight the clock and icons.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), Color.Transparent))))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { chrome = with(density) { it.height.toDp() } }
                    .navigationBarsPadding().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnackbarHost(snackbar)
                AnimatedVisibility(hasBook && !chromeHidden.value, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(onExpand = { expanded = true }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                AnimatedVisibility(tab >= 0 && !chromeHidden.value, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    FloatingNav(navEntries, tab, { go(it) }, onSearch = { go(1, search = true) })
                }
            }
            AnimatedVisibility(expanded && hasBook, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                FullPlayer(onCollapse = { expanded = false }, onOpenBook = { expanded = false; openBook(it) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
