@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mfbooks.convert.formatBytes
import app.mfbooks.server.num
import app.mfbooks.server.str
import app.mfbooks.ui.components.app
import app.mfbooks.ui.components.formatClock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.playback.Command
import app.mfbooks.playback.mediaVolume
import app.mfbooks.playback.setMediaVolume
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Slider
import app.mfbooks.server.CLOUD_ROOT
import app.mfbooks.server.Cloud
import app.mfbooks.server.Live
import app.mfbooks.server.LiveDevice
import app.mfbooks.server.Server
import app.mfbooks.server.Sync
import app.mfbooks.server.SyncState
import app.mfbooks.server.SyncStatus
import app.mfbooks.server.Transfer
import app.mfbooks.ui.components.LocalSnackbar
import app.mfbooks.ui.components.Panel

@Composable
fun SyncIcon(s: SyncStatus, modifier: Modifier = Modifier) {
    val (icon, tint) = when (s.state) {
        SyncState.SYNCED -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
        SyncState.SYNCING -> Icons.Rounded.CloudSync to MaterialTheme.colorScheme.primary
        SyncState.OFFLINE -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
        SyncState.FAILED -> Icons.Rounded.SyncProblem to MaterialTheme.colorScheme.error
        SyncState.OFF -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, syncLabel(s), modifier, tint = tint)
}

/** Library top bar (S1): sync status at a glance; nothing when no server is linked. */
@Composable
fun SyncIndicator(onClick: () -> Unit) {
    val link by Server.link.collectAsStateWithLifecycle()
    val s by Sync.status.collectAsStateWithLifecycle()
    if (link == null) return
    androidx.compose.material3.IconButton(onClick) { SyncIcon(s) }
}

enum class Where { PHONE, BOTH, CLOUD }

/** Phone, cloud or both (U5); null while no server is linked. */
@Composable
fun whereIs(book: Book): Where? {
    val link by Server.link.collectAsStateWithLifecycle()
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val remote by Cloud.remote.collectAsStateWithLifecycle()
    if (link == null) return null
    return when {
        book.id in remote || book.rootUri == CLOUD_ROOT -> Where.CLOUD
        cloud[book.serverId]?.complete == true -> Where.BOTH
        else -> Where.PHONE
    }
}

/** Small badge on covers: cloud copy, cloud only, or upload/conversion progress (U4, U5). */
@Composable
fun CloudBadge(book: Book, modifier: Modifier = Modifier) {
    val where = whereIs(book) ?: return
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val transfer by Transfer.current.collectAsStateWithLifecycle()
    val c = cloud[book.serverId]
    val progress = transfer?.takeIf { it.bookId == book.id }?.fraction ?: c?.takeIf { it.converting }?.progress
    val icon: ImageVector? = when {
        progress != null -> null
        where == Where.CLOUD -> Icons.Rounded.Cloud
        where == Where.BOTH -> Icons.Rounded.CloudDone
        else -> return
    }
    Box(modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), Alignment.Center) {
        if (icon != null) Icon(icon, if (where == Where.CLOUD) "Only in the cloud" else "On this phone and in the cloud", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        else CircularProgressIndicator({ progress ?: 0f }, Modifier.size(18.dp), strokeWidth = 2.5.dp)
    }
}

/** Book page: where the book is, its cloud copy, the actions that go with it, and the offline-conflict prompt (S4, U4, U6, O1, O3). */
@Composable
fun BookCloudPanel(book: Book, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val where = whereIs(book) ?: return
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val cloud by Cloud.books.collectAsStateWithLifecycle()
    val transfer by Transfer.current.collectAsStateWithLifecycle()
    val queue by Transfer.queue.collectAsStateWithLifecycle()
    val c = cloud[book.serverId]
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFree by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(false) }
    val conflict by produceState<JsonObject?>(null, book.serverId, resolved) {
        value = if (resolved) null else book.serverId?.let { sid -> runCatching { Server.get("/books/$sid").jsonObject["conflict"] as? JsonObject }.getOrNull() }
    }
    val t = transfer?.takeIf { it.bookId == book.id }
    val queued = queue.any { it.endsWith(":${book.id}") }
    Panel(modifier) {
        Text("Cloud", style = MaterialTheme.typography.titleMedium)
        Text(when {
            t != null -> "${t.label} · ${(t.fraction * 100).toInt()} %"
            queued -> "Waiting to transfer (uploads run on Wi-Fi)"
            c?.converting == true -> "Converting to Opus on the server · ${(c.progress * 100).toInt()} %"
            c?.conversion == "failed" -> "Conversion failed: ${c.error}. The original stays on the server."
            where == Where.CLOUD -> "Only on your server · ${formatBytes(c?.bytes ?: 0)}. Streams while online."
            where == Where.BOTH -> "On this phone and in the cloud · ${formatBytes(c?.bytes ?: 0)}" + if (c?.verified == true) " · verified" else ""
            c != null -> "Uploading · ${c.ready} of ${c.files} files"
            else -> "Only on this phone"
        }, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (where == Where.PHONE && c == null && !queued) FilledTonalButton({ Transfer.uploadBooks(ctx, listOf(book.id)) }) { Text("Upload") }
            if (where == Where.CLOUD && !queued) FilledTonalButton({ Transfer.download(ctx, book.id) }) { Text("Download") }
            if (where == Where.BOTH && c?.verified == true) OutlinedButton({ confirmFree = true }) { Text("Free up space") }
            if (c != null) OutlinedButton({ confirmDelete = true }) { Text("Delete from cloud") }
        }
        conflict?.let { k ->
            val name = k.str("deviceName") ?: "Another device"
            val track = k.num("trackIndex")?.toInt() ?: 0
            val pos = k.num("positionInTrackMs")?.toLong() ?: 0
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp)) {
                Text("Two positions for this book", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("$name also moved it while offline: file ${track + 1} at ${formatClock(pos)}. Which one do you keep?",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lib = app(ctx).db.library()
                    // Writing the progress again queues a push based on the current version, which resolves it on the server.
                    TextButton({ scope.launch { lib.progress(book.id)?.let { lib.upsertProgress(it.copy(updatedAt = System.currentTimeMillis())) }; resolved = true; Sync.request(ctx) } }) { Text("Keep this phone's") }
                    TextButton({ scope.launch { lib.upsertProgress(BookProgress(book.id, track, pos, System.currentTimeMillis())); resolved = true; Sync.request(ctx) } }) { Text("Use $name's") }
                }
            }
        }
    }
    if (confirmFree) AlertDialog(
        onDismissRequest = { confirmFree = false },
        title = { Text("Remove the files from this phone?") },
        text = { Text("The book stays in the library and streams from your server. Download it again any time to keep it offline.") },
        confirmButton = { TextButton({
            confirmFree = false
            scope.launch { snackbar.showSnackbar(Transfer.offload(ctx, book) ?: "Removed from this phone; it streams from now on") }
        }) { Text("Remove files") } },
        dismissButton = { TextButton({ confirmFree = false }) { Text("Cancel") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the cloud copy?") },
        text = { Text(if (where == Where.CLOUD) "This book has no files on this phone, so it leaves the library. Progress and stats stay." else "The files on this phone stay. Progress and stats stay on the server.") },
        confirmButton = { TextButton({
            confirmDelete = false
            scope.launch {
                runCatching { Server.delete("/books/${book.serverId}/files") }.onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                    .onSuccess { Cloud.forget(book.serverId!!); Cloud.refreshSoon(ctx) }
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** The active device when it isn't this phone. */
@Composable
fun rememberRemoteActive(): LiveDevice? {
    val devices by Live.devices.collectAsStateWithLifecycle()
    return devices.firstOrNull { it.active && it.online && it.id != Live.me && it.state != null }
}

/** "Playing on Living room PC" under the player, like Spotify's green bar (W4). Tap opens the device picker. */
@Composable
fun PlayingOnStrip(modifier: Modifier = Modifier) {
    val d = rememberRemoteActive() ?: return
    var picker by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Row(modifier.clip(CircleShape).background(cs.primary).clickable { picker = true }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(kindIcon(d.kind), null, Modifier.size(16.dp), tint = cs.onPrimary)
        Text((if (d.state?.playing == true) "Playing on " else "Connected to ") + d.name, style = MaterialTheme.typography.labelLarge, color = cs.onPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (picker) ConnectSheet { picker = false }
}

fun kindIcon(kind: String) = if (kind == "web") Icons.Rounded.Computer else Icons.Rounded.Smartphone

/**
 * The device picker (W4), modelled on Spotify Connect: where it plays now on top, then every other online device. Picking one
 * moves the book there at the current position; picking "This phone" pulls it back.
 */
@Composable
fun ConnectSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = app(ctx)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val devices by Live.devices.collectAsStateWithLifecycle()
    val np by app.player.state.collectAsStateWithLifecycle()
    val me = Live.me
    val remote = devices.firstOrNull { it.active && it.online && it.id != me && it.state != null }
    val others = devices.filter { it.online && it.id != me && it.id != remote?.id }

    /** What plays now, as a track position: the remote device's, or this phone's. */
    fun source(): Triple<String, Int, Long>? {
        remote?.state?.let { s -> return Triple(s.bookId ?: return null, s.trackIndex, s.positionNow(Live.serverNow())) }
        val sid = np.book?.serverId ?: return null
        val ch = np.chapter ?: return null
        return Triple(sid, ch.trackIndex, ch.startInTrackMs + np.positionInChapterMs)
    }

    fun moveTo(target: LiveDevice) = scope.launch {
        val (sid, track, pos) = source() ?: return@launch snackbar.showSnackbar("Sync this book first").let {}
        val err = Live.command(target.id, "play", mapOf("bookId" to sid, "trackIndex" to track, "positionMs" to pos, "speed" to (remote?.state?.speed ?: np.speed)))
        if (err != null) snackbar.showSnackbar("${target.name}: $err") else { app.player.send(Command.PAUSE); onDismiss() }
    }

    fun pullHere() = scope.launch {
        val r = remote ?: return@launch
        val s = r.state ?: return@launch
        val book = s.bookId?.let { app.db.sync().bookByServerId(it) } ?: return@launch snackbar.showSnackbar("That book isn't on this phone").let {}
        Live.command(r.id, "pause")
        app.player.playAt(book.id, s.trackIndex, s.positionNow(Live.serverNow()))
        onDismiss()
    }

    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect", Modifier.padding(start = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            val cs = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(remote?.name ?: "This phone", style = MaterialTheme.typography.titleLargeEmphasized, color = cs.primary)
                        val what = remote?.state?.title ?: np.book?.title
                        what?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Icon(kindIcon(remote?.kind ?: "android"), null, Modifier.size(36.dp), tint = cs.primary)
                }
                // The volume of the device that plays, as in Spotify Connect: the other device's by command, or this phone's media volume.
                var drag by remember { mutableStateOf<Float?>(null) }
                val level = drag ?: if (remote != null) remote.state?.volume else mediaVolume(ctx)
                if (level != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (level > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, null, tint = cs.primary)
                    Slider(level, { v -> drag = v; if (remote == null) setMediaVolume(ctx, v) }, Modifier.padding(start = 12.dp).weight(1f),
                        onValueChangeFinished = {
                            val v = drag ?: return@Slider
                            if (remote != null) scope.launch { Live.command(remote.id, "volume", mapOf("volume" to v))?.let { snackbar.showSnackbar("${remote.name}: $it") }; drag = null }
                            else drag = null
                        })
                }
            }
            if (remote != null) DeviceRow("This phone", "android", null) { pullHere() }
            others.forEach { d -> DeviceRow(d.name, d.kind, d.state?.title?.let { if (d.state.playing) "Playing $it" else "Paused · $it" }) { moveTo(d) } }
            if (remote == null && others.isEmpty()) Text("No other device is online. Open the web player on your computer to see it here.",
                Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(name: String, kind: String, summary: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(kindIcon(kind), null, Modifier.size(28.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
