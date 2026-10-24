@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.rememberBookItems
import app.mfbooks.ui.theme.glowBackdrop
import kotlinx.coroutines.launch
import app.mfbooks.data.Book
import app.mfbooks.data.BookState
import app.mfbooks.data.Settings
import app.mfbooks.server.Cloud
import app.mfbooks.server.Server
import app.mfbooks.ui.components.BookItem
import app.mfbooks.ui.components.BookRow
import app.mfbooks.ui.components.BookTile
import app.mfbooks.ui.components.EmptyPlaceholder
import app.mfbooks.ui.components.GroupRow
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.SettingsGroup
import app.mfbooks.ui.server.SyncIndicator

private enum class Filter(val label: String) {
    ALL("All"), STARTED("Started"), FINISHED("Finished"), CLOUD("Cloud"), HIDDEN("Hidden");
    /** All = not finished; Hidden books show only under Hidden; Cloud = has a cloud copy. */
    fun matches(b: Book, inCloud: (Book) -> Boolean) = if (this == HIDDEN) b.hidden else !b.hidden && when (this) {
        ALL -> b.state != BookState.FINISHED
        CLOUD -> inCloud(b)
        else -> b.state.name == name
    }
}

private val sorts = listOf("TITLE" to "Title", "AUTHOR" to "Author", "RECENT" to "Recently played",
    "ADDED" to "Date added", "LENGTH" to "Length", "PATH" to "Path")

private fun sorted(l: List<BookItem>, key: String): List<BookItem> = when (key) {
    "AUTHOR" -> l.sortedWith(compareBy<BookItem, String>(String.CASE_INSENSITIVE_ORDER) { it.book.author.orEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.title })
    "RECENT" -> l.sortedByDescending { it.book.lastPlayedAt ?: 0L }
    "ADDED" -> l.sortedByDescending { it.book.addedAt }
    "LENGTH" -> l.sortedByDescending { it.book.durationMs }
    "PATH" -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.book.relativePath })
    else -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.book.title })
}

@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit, onSettings: () -> Unit, onServer: () -> Unit = onSettings) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val scope = rememberCoroutineScope()
    val items by rememberBookItems()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val scan by app.scanner.progress.collectAsStateWithLifecycle()
    val now by app.player.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var sheet by remember { mutableStateOf<Book?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderPath by rememberSaveable { mutableStateOf(listOf<String>()) }

    val link by Server.link.collectAsStateWithLifecycle()
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val remote by Cloud.remote.collectAsStateWithLifecycle()
    val inCloud = { b: Book -> b.id in remote || cloud[b.serverId] != null }
    val all = items.orEmpty()
    val shown = sorted(all.filter { filter.matches(it.book, inCloud) }, settings.librarySort)
    val view = settings.libraryView
    fun setPrefs(f: (Settings) -> Settings) { scope.launch { app.prefs.update(f) } }

    // Back first clears a filter, then climbs out of folders, then leaves the tab.
    BackHandler(filter != Filter.ALL) { filter = Filter.ALL; folderPath = emptyList() }
    BackHandler(filter == Filter.ALL && view == "FOLDERS" && folderPath.isNotEmpty()) { folderPath = folderPath.dropLast(1) }

    val pullState = rememberPullToRefreshState()
    val bottom = LocalChromePadding.current + 16.dp
    val playingId = if (now.isPlaying) now.book?.id else null
    Box(Modifier.fillMaxSize().glowBackdrop(0.6f)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.headlineSmallEmphasized) },
                actions = {
                    SyncIndicator(onServer)
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(20.dp)) {
                            sorts.forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    trailingIcon = { if (k == settings.librarySort) Icon(Icons.Rounded.Check, "Selected") },
                                    onClick = { sortMenu = false; setPrefs { it.copy(librarySort = k) } },
                                )
                            }
                        }
                    }
                    val next = when (view) { "GRID" -> "LIST"; "LIST" -> "FOLDERS"; else -> "GRID" }
                    IconButton({ setPrefs { it.copy(libraryView = next) } }) {
                        Icon(when (view) { "GRID" -> Icons.Rounded.GridView; "LIST" -> Icons.AutoMirrored.Rounded.ViewList; else -> Icons.Rounded.FolderOpen },
                            "View: ${view.lowercase()}. Switch to ${next.lowercase()}")
                    }
                    // Settings sits top-right on every top-level page. Full rescan lives in Settings › Library.
                    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            val anyHidden = all.any { it.book.hidden }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.filter { (it != Filter.HIDDEN || anyHidden || filter == Filter.HIDDEN) && (it != Filter.CLOUD || link != null || filter == Filter.CLOUD) }.forEach {
                    val count = all.count { b -> it.matches(b.book, inCloud) }
                    FilterChip(filter == it, { filter = it; folderPath = emptyList() }, { Text("${it.label}  $count") },
                        shape = CircleShape, modifier = Modifier.animateContentSize(),
                        leadingIcon = if (filter == it) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null)
                }
            }
            PullToRefreshBox(
                scan != null, { app.scanner.enqueue(full = false) }, Modifier.weight(1f), state = pullState,
                indicator = { PullToRefreshDefaults.LoadingIndicator(pullState, scan != null, Modifier.align(Alignment.TopCenter)) },
            ) {
                when {
                    items == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                    shown.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item { EmptyPlaceholder(Icons.Rounded.AutoStories, "Nothing here", if (filter == Filter.ALL) "Pull down to rescan your folders." else "No ${filter.label.lowercase()} books.", Modifier.padding(top = 32.dp)) }
                    }
                    view == "LIST" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = bottom)) {
                        items(shown, key = { it.book.id }) { BookRow(it, { onOpenBook(it.book.id) }, { sheet = it.book }, playing = it.book.id == playingId) }
                    }
                    view == "FOLDERS" -> FolderView(shown, folderPath, { folderPath = it }, onOpenBook, playingId, bottom) { sheet = it }
                    else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottom),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.book.id }) { BookTile(it, { onOpenBook(it.book.id) }, { sheet = it.book }, playing = it.book.id == playingId) }
                    }
                }
            }
        }
    }
    sheet?.let { BookActionsSheet(it) { sheet = null } }
}

/** Full-screen book search, its own destination so back returns to wherever it was opened. */
@Composable
fun SearchScreen(onOpenBook: (String) -> Unit, onClose: () -> Unit) {
    val app = app(LocalContext.current)
    val items by rememberBookItems()
    val now by app.player.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<Book?>(null) }
    val playingId = if (now.isPlaying) now.book?.id else null
    val results = if (query.isBlank()) emptyList() else items.orEmpty().filter { !it.book.hidden && listOfNotNull(it.book.title, it.book.author, it.book.narrator)
        .any { s -> s.contains(query, ignoreCase = true) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                modifier = Modifier.focusRequester(focus),
                query = query, onQueryChange = { query = it }, onSearch = {}, expanded = true,
                onExpandedChange = { if (!it) onClose() },
                placeholder = { Text("Search title, author, narrator") },
                leadingIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            )
        },
        expanded = true, onExpandedChange = { if (!it) onClose() },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
            if (query.isNotBlank() && results.isEmpty()) item { EmptyPlaceholder(Icons.Rounded.SearchOff, "No matches", "Nothing matches \"$query\".") }
            items(results, key = { it.book.id }) { BookRow(it, { onOpenBook(it.book.id) }, { sheet = it.book }, playing = it.book.id == playingId) }
        }
    }
    sheet?.let { BookActionsSheet(it) { sheet = null } }
}

@Composable
private fun FolderView(
    books: List<BookItem>, path: List<String>, onPath: (List<String>) -> Unit,
    onOpen: (String) -> Unit, playingId: String?, bottom: androidx.compose.ui.unit.Dp, onLong: (Book) -> Unit,
) {
    fun segs(i: BookItem) = i.book.relativePath.split('/').filter { it.isNotEmpty() }
    val under = books.filter { b -> segs(b).let { it.size > path.size && it.take(path.size) == path } }
    val folders = under.filter { segs(it).size > path.size + 1 }.groupBy { segs(it)[path.size] }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val leaves = under.filter { segs(it).size == path.size + 1 }
    val dirs = folders.entries.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom)) {
        if (path.isNotEmpty() || dirs.isNotEmpty()) item {
            val rows = (if (path.isNotEmpty()) listOf(GroupRow(path.joinToString(" / "), "Up one folder", Icons.AutoMirrored.Rounded.ArrowBack, { onPath(path.dropLast(1)) })) else emptyList()) +
                dirs.map { (name, list) -> GroupRow(name, if (list.size == 1) "1 book" else "${list.size} books", Icons.Rounded.Folder, { onPath(path + name) }) }
            SettingsGroup(null, rows, Modifier.padding(bottom = 8.dp))
        }
        items(leaves, key = { it.book.id }) { BookRow(it, { onOpen(it.book.id) }, { onLong(it.book) }, Modifier.padding(horizontal = 4.dp), playing = it.book.id == playingId) }
    }
}
