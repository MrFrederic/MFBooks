@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.server

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.data.Book
import app.mfbooks.server.Cloud
import app.mfbooks.server.Link
import app.mfbooks.server.Server
import app.mfbooks.server.Sync
import app.mfbooks.server.SyncState
import app.mfbooks.server.SyncStatus
import app.mfbooks.server.Transfer
import app.mfbooks.server.str
import app.mfbooks.server.num
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.Panel
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.rememberBookItems
import app.mfbooks.convert.formatBytes
import app.mfbooks.ui.settings.Item
import app.mfbooks.ui.settings.Section
import app.mfbooks.ui.settings.SectionCard
import app.mfbooks.ui.settings.SettingRow
import app.mfbooks.ui.settings.SettingsScaffold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private fun ago(t: Long) = if (System.currentTimeMillis() - t < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun syncLabel(s: SyncStatus): String = when (s.state) {
    SyncState.OFF -> s.error ?: "Not connected"
    SyncState.SYNCING -> "Syncing…"
    SyncState.SYNCED -> "Synced " + if (s.lastSync == 0L) "" else ago(s.lastSync)
    SyncState.OFFLINE -> "Offline" + if (s.lastSync > 0) " · last synced " + ago(s.lastSync) else ""
    SyncState.FAILED -> "Sync failed: ${s.error}"
}

/** Settings → Server (C1–C3): link this phone, see sync status, manage the cloud library and devices. */
@Composable
fun ServerScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val link by Server.link.collectAsStateWithLifecycle()
    // ponytail: a pairing link opened while already linked is dropped; disconnect first to link elsewhere.
    LaunchedEffect(link) { if (link != null) Server.pendingPair.value = null }
    SettingsScaffold("Server", onBack) {
        val l = link
        if (l == null) ConnectForm() else Connected(l, navigate)
    }
}

@Composable
private fun ConnectForm() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pair by Server.pendingPair.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var withCode by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pair) { pair?.let { (s, c) -> address = s; code = c; withCode = true; Server.pendingPair.value = null } }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Link MFBooks to your own MFBooks Server to back up progress and stats, keep books in the cloud and listen in a browser. " +
            "On the server's Devices page choose Link a phone and scan the QR code with your camera, or enter the address and code here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Server address") }, placeholder = { Text("books.example.com") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (address.trim().startsWith("http://") && !Regex("""http://(localhost|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)""").containsMatchIn(address.trim()))
            Text("Plain http sends your password and token unencrypted. Use https outside your home network.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(withCode, { withCode = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pairing code") }
            SegmentedButton(!withCode, { withCode = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Password") }
        }
        if (withCode) OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text("Pairing code") }, singleLine = true)
        else {
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button({
                busy = true; error = null
                scope.launch {
                    runCatching { if (withCode) Server.pair(ctx, address, code) else Server.signIn(ctx, address, user, password) }
                        .onFailure { error = it.message ?: "Couldn't connect" }
                    busy = false
                }
            }, enabled = !busy && address.isNotBlank() && (if (withCode) code.isNotBlank() else user.isNotBlank() && password.isNotBlank())) { Text("Connect") }
            if (busy) LoadingIndicator(Modifier.padding(start = 12.dp).size(32.dp))
        }
    }
}

@Composable
private fun Connected(l: Link, navigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val status by Sync.status.collectAsStateWithLifecycle()
    val queue by Transfer.queue.collectAsStateWithLifecycle()
    val current by Transfer.current.collectAsStateWithLifecycle()
    var uploadSheet by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<JsonObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val devices by produceState<List<JsonObject>?>(null, reload) { value = runCatching { Server.get("/devices").jsonArray.map { it.jsonObject } }.getOrNull() }
    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { Transfer.addFile(ctx, it); scope.launch { snackbar.showSnackbar("Uploads start on Wi-Fi") } }
    }

    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncIcon(status, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(l.name, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(l.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(syncLabel(status), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = if (status.state == SyncState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            TextButton({ Sync.request(ctx) }, enabled = status.state != SyncState.SYNCING) { Text("Sync now") }
        }
    }
    SectionCard(Section("Cloud library", listOfNotNull(
        Item("Upload books") { sh -> SettingRow(sh, "Upload books", "Pick books to keep in the cloud", Icons.Rounded.CloudUpload, onClick = { uploadSheet = true }) },
        Item("Add a file to the cloud") { sh -> SettingRow(sh, "Add a file to the cloud", "An audio file or a zip of a book folder", Icons.Rounded.UploadFile,
            onClick = { addFile.launch(arrayOf("audio/*", "application/zip")) }) },
        Item("Free up space") { sh -> SettingRow(sh, "Free up space", "Remove files of books that are safely in the cloud", Icons.Rounded.CleaningServices, onClick = { navigate("freeup") }) },
        if (queue.isEmpty()) null else Item("Transfers") { sh ->
            SettingRow(sh, current?.let { "${it.label} · ${(it.fraction * 100).toInt()} %" } ?: "${queue.size} waiting",
                if (current == null) "Uploads run on Wi-Fi" else null, Icons.Rounded.SwapVert,
                trailing = { if (current == null) TextButton({ Transfer.resume(ctx) }) { Text("Resume") } })
        },
    )))
    SectionCard(Section("Devices", devices.orEmpty().map { d ->
        val id = d.str("id").orEmpty()
        val me = d.str("current") == "true"
        Item(d.str("name").orEmpty()) { sh ->
            SettingRow(sh, d.str("name").orEmpty() + if (me) " (this phone)" else "",
                (if (d.str("online") == "true") "Online" else "Last seen " + DateUtils.getRelativeTimeSpanString(d.num("lastSeen")?.toLong() ?: 0, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)),
                if (d.str("kind") == "web") Icons.Rounded.Computer else Icons.Rounded.Smartphone,
                trailing = { if (!me) IconButton({ revoke = d }) { Icon(Icons.Rounded.Delete, "Revoke ${d.str("name")}") } })
        }
    }, footer = "Revoking a device signs it out. Its data stays on the server."))
    SectionCard(Section(null, listOf(Item("Disconnect") { sh ->
        SettingRow(sh, "Disconnect", "This phone keeps working on its own; the server keeps everything", Icons.Rounded.LinkOff, onClick = { confirmDisconnect = true })
    })))

    if (uploadSheet) UploadSheet { uploadSheet = false }
    revoke?.let { d ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("Revoke ${d.str("name")}?") },
            text = { Text("It is signed out and has to be linked again. Its listening data stays on the server.") },
            confirmButton = { TextButton({ revoke = null; scope.launch { runCatching { Server.delete("/devices/${d.str("id")}") }; reload++ } }) { Text("Revoke") } },
            dismissButton = { TextButton({ revoke = null }) { Text("Cancel") } },
        )
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect from ${l.name}?") },
        text = { Text("Sync stops and books that only stream from the server leave the library. Nothing is deleted on the server; connect again to pick up where you left off.") },
        confirmButton = { TextButton({ confirmDisconnect = false; scope.launch { Server.disconnect(ctx) } }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirmDisconnect = false }) { Text("Cancel") } },
    )
}

/** U3: several books, or all of them, in one action. */
@Composable
fun UploadSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val items by rememberBookItems()
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val remote by Cloud.remote.collectAsStateWithLifecycle()
    val candidates = items.orEmpty().map { it.book }.filter { it.serverId !in cloud && it.id !in remote && it.rootUri != app.mfbooks.server.CLOUD_ROOT }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Upload to the cloud", Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized)
            TextButton({ picked = if (picked.size == candidates.size) emptySet() else candidates.map { it.id }.toSet() }) {
                Text(if (picked.size == candidates.size && candidates.isNotEmpty()) "None" else "All")
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(candidates, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(b.id in picked, { on -> picked = if (on) picked + b.id else picked - b.id })
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(b.author).joinToString(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (candidates.isEmpty()) Text("Every book is already in the cloud.", Modifier.padding(24.dp))
        Button({ Transfer.uploadBooks(ctx, picked.toList()); onDismiss(); scope.launch { snackbar.showSnackbar("Uploading ${picked.size} books on Wi-Fi") } },
            Modifier.fillMaxWidth().padding(16.dp), enabled = picked.isNotEmpty()) { Text("Upload ${picked.size}") }
    }
}

/** O1, like Google Photos' "Free up space": every book safely in the cloud, with what removing its files frees. */
@Composable
fun FreeUpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val items by rememberBookItems()
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val remote by Cloud.remote.collectAsStateWithLifecycle()
    val books = items.orEmpty().map { it.book }.filter { b -> b.id !in remote && cloud[b.serverId]?.verified == true }
    val info by produceState<Map<String, Pair<Long, Boolean>>>(emptyMap(), books.map { it.id }) {
        val lib = app(ctx).db.library()
        value = books.associate { b -> b.id to (lib.tracks(b.id).sumOf { it.sizeBytes } to Transfer.canDelete(ctx, b)) }
    }
    var picked by remember(books.size) { mutableStateOf(books.map { it.id }.toSet()) }
    var confirm by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val total = picked.sumOf { info[it]?.first ?: 0 }
    SettingsScaffold("Free up space", onBack) {
        Text(if (books.isEmpty()) "No book is fully verified in the cloud yet. Upload books from Settings → Server first."
            else "These books are safely on your server. Removing their files from this phone frees ${formatBytes(total)}; they stay in the library and stream when you play them.",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        books.forEach { b ->
            val (size, writable) = info[b.id] ?: (0L to true)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(b.id in picked && writable, { on -> picked = if (on) picked + b.id else picked - b.id }, enabled = writable)
                Column(Modifier.weight(1f)) {
                    Text(b.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                    Text(if (writable) formatBytes(size) else "Read-only folder: delete ${b.relativePath} yourself", style = MaterialTheme.typography.bodySmall,
                        color = if (writable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (books.isNotEmpty()) Button({ confirm = true }, Modifier.fillMaxWidth().padding(16.dp), enabled = !running && picked.any { info[it]?.second == true }) {
            Text(if (running) "Removing…" else "Free up ${formatBytes(total)}")
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove files of ${picked.size} books?") },
        text = { Text("They will exist only on your server. You can still play them while online, or download them again to keep them offline.") },
        confirmButton = { TextButton({
            confirm = false; running = true
            scope.launch {
                val errors = books.filter { it.id in picked && info[it.id]?.second == true }.mapNotNull { b -> Transfer.offload(ctx, b) }
                running = false
                snackbar.showSnackbar(errors.firstOrNull() ?: "Freed ${formatBytes(total)}")
            }
        }) { Text("Remove") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}
