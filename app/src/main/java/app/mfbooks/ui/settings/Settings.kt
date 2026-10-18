@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.data.Settings
import app.mfbooks.ui.components.LocalChromePadding
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.rememberAddFolder
import app.mfbooks.ui.theme.glowBackdrop
import kotlinx.coroutines.launch

/** Builds the [Env], hosts the dialog / sheet a setting opens, and draws [content] with it. */
@Composable
private fun SettingsHost(navigate: (String) -> Unit, content: @Composable (Env) -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsStateWithLifecycle(Settings())
    val roots by app.db.library().roots().collectAsStateWithLifecycle(emptyList())
    val addFolder = rememberAddFolder()
    val snackbar = LocalSnackbar.current
    val clip = LocalClipboardManager.current
    var dialog by remember { mutableStateOf<(@Composable (() -> Unit) -> Unit)?>(null) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val env = Env(
        s = s, roots = roots,
        set = { f -> scope.launch { app.prefs.update(f) } },
        navigate = navigate,
        snack = { m -> scope.launch { snackbar.showSnackbar(m) } },
        copy = { a -> clip.setText(AnnotatedString(a)); scope.launch { snackbar.showSnackbar("Copied $a") } },
        rescan = { app.scanner.enqueue(full = true); scope.launch { snackbar.showSnackbar("Rescan started") } },
        removeRoot = { r -> scope.launch { app.scanner.removeRoot(r.uri) } },
        addFolder = addFolder, version = version,
        dialog = { content -> dialog = content },
    )
    content(env)
    dialog?.invoke { dialog = null }
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(Modifier.fillMaxSize().glowBackdrop(0.5f).nestedScroll(scroll.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize()) {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                scrollBehavior = scroll,
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalChromePadding.current + 16.dp)) { content() }
        }
    }
}

/** Root: a search box and the category list. Searching shows matching settings (live) grouped by page. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) = SettingsHost(onNavigate) { env ->
    var query by rememberSaveable { mutableStateOf("") }
    SettingsScaffold("Settings", onBack) {
        TextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search settings") }, singleLine = true, shape = CircleShape,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        val q = query.trim()
        if (q.isEmpty()) {
            SettingsPage.groups.forEach { group ->
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    group.forEachIndexed { i, p ->
                        SettingRow(ListItemDefaults.segmentedShapes(i, group.size), p.title, env.summary(p), p.icon, p.tone, onClick = { env.navigate("settings/${p.route}") })
                    }
                }
            }
        } else {
            var hits = 0
            SettingsPage.entries.forEach { p ->
                val items = env.sections(p).filter { it.searchable }.flatMap { it.items }
                    .filter { it.title.contains(q, ignoreCase = true) || it.keywords.contains(q, ignoreCase = true) }
                if (items.isNotEmpty()) { hits += items.size; SectionCard(Section(p.title, items)) }
            }
            if (hits == 0) Text("No settings match \"$q\"", Modifier.padding(32.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsPageScreen(route: String?, onBack: () -> Unit, onNavigate: (String) -> Unit) = SettingsHost(onNavigate) { env ->
    val page = SettingsPage.of(route)
    SettingsScaffold(page.title, onBack) { env.sections(page).forEach { SectionCard(it) } }
}
