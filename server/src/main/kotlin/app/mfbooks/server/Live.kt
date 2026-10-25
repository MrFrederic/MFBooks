package app.mfbooks.server

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val STALE_MS = 15_000L

/**
 * Now playing and remote commands, like Spotify Connect. The server only relays: devices report their state,
 * commands go to the target and its ack comes back. Whoever starts playing becomes active and the others are paused.
 */
class Live(private val app: App) {
    private class Peer(val id: String) {
        val sessions = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
        @Volatile var state: JsonObject? = null
        @Volatile var lastFrame = System.currentTimeMillis()
    }

    private val peers = ConcurrentHashMap<String, Peer>()
    private val pending = ConcurrentHashMap<String, Pair<String, Long>>() // cmd id → (sender, sent at)
    @Volatile private var active: String? = null
    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            while (isActive) {
                delay(5_000)
                val now = System.currentTimeMillis()
                // A device that went silent (switched off, lost Wi-Fi) is gone now, not when its dead TCP connection times
                // out minutes later: otherwise it stays "active" and every other device keeps sending it commands.
                val gone = peers.values.filter { it.sessions.isNotEmpty() && now - it.lastFrame > STALE_MS }
                for (p in gone) {
                    val sessions = p.sessions.toList()
                    p.sessions.clear(); p.state = null
                    if (active == p.id) active = null
                    sessions.forEach { s -> launch { runCatching { withTimeout(5_000) { s.close(CloseReason(CloseReason.Codes.GOING_AWAY, "No heartbeat")) } }; s.cancel() } }
                }
                if (gone.isNotEmpty()) broadcastDevices()
                pending.entries.removeIf { now - it.value.second > 60_000 }
            }
        }
    }

    fun isOnline(id: String) = peers[id]?.let { it.sessions.isNotEmpty() && System.currentTimeMillis() - it.lastFrame < STALE_MS } == true

    suspend fun serve(device: Device, session: DefaultWebSocketServerSession) {
        val p = peers.getOrPut(device.id) { Peer(device.id) }
        p.sessions += session
        p.lastFrame = System.currentTimeMillis()
        session.send(Frame.Text(j("t" to "hello", "deviceId" to device.id, "now" to System.currentTimeMillis()).toString()))
        broadcastDevices()
        try {
            for (f in session.incoming) if (f is Frame.Text) {
                val m = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: continue
                onFrame(p, m)
            }
        } catch (e: java.io.IOException) {
            // Ping timeout or a dropped connection (sleeping laptop, background tab): a normal way for a device to leave.
        } finally {
            p.sessions -= session
            if (p.sessions.isEmpty()) { p.state = null; if (active == p.id) active = null }
            broadcastDevices()
        }
    }

    private suspend fun onFrame(p: Peer, m: JsonObject) {
        p.lastFrame = System.currentTimeMillis()
        when (m.str("t")) {
            "state" -> {
                val wasPlaying = p.state?.bool("playing") == true
                val st = JsonObject(m - "t" + ("at" to JsonPrimitive(System.currentTimeMillis())))
                p.state = st
                if (st.bool("playing") == true && (active != p.id || !wasPlaying)) {
                    active = p.id
                    val pause = j("t" to "pause", "by" to p.id, "state" to st)
                    peers.values.filter { it !== p && it.state?.bool("playing") == true }.forEach { it.send(pause) }
                }
                broadcastDevices()
            }
            "cmd" -> {
                val id = m.str("id") ?: return
                val target = m.str("target")?.let { peers[it] }
                if (target == null || !isOnline(target.id)) return p.send(j("t" to "ack", "id" to id, "ok" to false, "error" to "offline"))
                pending[id] = p.id to System.currentTimeMillis()
                target.send(JsonObject(m - "target" + ("from" to JsonPrimitive(p.id))))
            }
            "ack" -> m.str("id")?.let { pending.remove(it) }?.let { peers[it.first]?.send(m) }
        }
    }

    private suspend fun Peer.send(m: JsonObject) {
        val text = m.toString()
        sessions.forEach { runCatching { it.send(Frame.Text(text)) } }
    }

    private fun broadcast(m: JsonObject) { if (::scope.isInitialized) scope.launch { peers.values.forEach { it.send(m) } } }

    private suspend fun broadcastDevices() {
        val now = System.currentTimeMillis()
        val list = app.db.query("SELECT id, name, kind FROM devices ORDER BY created_at") { Triple(it.getString(1), it.getString(2), it.getString(3)) }.map { (id, name, kind) ->
            val online = isOnline(id)
            j("id" to id, "name" to name, "kind" to kind, "online" to online, "active" to (online && id == active), "state" to peers[id]?.state?.takeIf { online })
        }
        val m = j("t" to "devices", "now" to now, "devices" to list)
        peers.values.forEach { it.send(m) }
    }

    fun changed(seq: Long) = broadcast(j("t" to "changed", "seq" to seq))

    fun book(id: String, conversion: String? = null, progress: Double? = null) = broadcast(j("t" to "book", "id" to id, "conversion" to conversion, "progress" to progress))

    /** A revoked device loses its live connection at once. */
    fun kick(id: String) {
        peers.remove(id)?.sessions?.forEach { s -> scope.launch { runCatching { s.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Signed out")) } } }
        if (active == id) active = null
    }
}

fun Route.liveRoutes(app: App) {
    webSocket("/live") { app.live.serve(call.device, this) }
}
