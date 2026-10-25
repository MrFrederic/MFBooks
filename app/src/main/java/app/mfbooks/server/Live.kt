package app.mfbooks.server

import android.util.Log
import app.mfbooks.sync.SyncJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a device reports it is playing; [at] is server time, [bookId] the server book id. */
data class LiveState(val bookId: String?, val title: String?, val trackIndex: Int, val positionMs: Long, val speed: Float, val playing: Boolean, val at: Long,
                     /** 0..1 media volume; null from a device that doesn't report it. */ val volume: Float? = null) {
    /** Position in the track now, extrapolated while playing. */
    fun positionNow(serverNow: Long) = if (playing) positionMs + ((serverNow - at) * speed).toLong() else positionMs
}

data class LiveDevice(val id: String, val name: String, val kind: String, val online: Boolean, val active: Boolean, val state: LiveState?)

/**
 * The live connection (W4, W5, S3), held only while playing, while the app is on screen or while this phone
 * remote-controls another device (risk 18). Reconnects with backoff.
 */
object Live {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _devices = MutableStateFlow<List<LiveDevice>>(emptyList())
    val devices: StateFlow<List<LiveDevice>> = _devices
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>() // "" = ok, else the error
    private var ws: WebSocket? = null
    private var open = false
    private var retry: Job? = null
    private var backoff = 1_000L
    private var lastSent = 0L
    private var lastState: JsonObject? = null
    private var offset = 0L // server clock − ours

    private var foreground = false
    private var playing = false
    private var remote = false

    /** Runs a command another device sent us; returns an error message or null. Set by PlaybackService. */
    var onCommand: (suspend (JsonObject) -> String?)? = null
    /** Another device started playing. */
    var onPause: ((JsonObject) -> Unit)? = null

    val me get() = Server.link.value?.deviceId
    fun serverNow() = System.currentTimeMillis() + offset

    fun setForeground(on: Boolean) { foreground = on; update() }
    fun setPlaying(on: Boolean) { if (playing != on) { playing = on; update() } }
    fun setRemote(on: Boolean) { remote = on; update() }

    fun update() {
        val want = Server.connected && (foreground || playing || remote)
        if (want && ws == null) connect()
        if (!want && ws != null) { retry?.cancel(); ws?.close(1000, null); ws = null; open = false; _devices.value = emptyList() }
    }

    private fun connect() {
        val l = Server.link.value ?: return
        val url = l.url.replaceFirst("http", "ws") + "/api/v1/live"
        ws = Server.http.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${l.token}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch { open = true; backoff = 1_000; lastState?.let { send(it) } } }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { runCatching { onFrame(SyncJson.parseToJsonElement(text).jsonObject) }.onFailure { Log.w("Live", "bad frame", it) } } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scope.launch { lost(webSocket) }.let {}
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scope.launch { lost(webSocket) }.let {}
        })
        scope.launch { heartbeat() }
    }

    private fun lost(socket: WebSocket) {
        if (socket !== ws) return
        ws = null; open = false; _devices.value = emptyList()
        pending.values.forEach { it.complete("offline") }; pending.clear()
        retry = scope.launch { delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000); update() }
    }

    private suspend fun heartbeat() {
        val socket = ws
        while (scope.isActive && ws === socket && socket != null) {
            delay(5_000)
            if (open && System.currentTimeMillis() - lastSent >= 4_500) send(JsonObject(mapOf("t" to JsonPrimitive("ping"))))
        }
    }

    private fun send(m: JsonObject) { if (open && ws?.send(m.toString()) == true) lastSent = System.currentTimeMillis() }

    /** Now playing on this phone; resent on change and every 5 s while playing (by the caller). */
    fun state(bookId: String, title: String, trackIndex: Int, positionMs: Long, speed: Float, playing: Boolean, volume: Float) {
        val m = JsonObject(mapOf("t" to JsonPrimitive("state"), "bookId" to JsonPrimitive(bookId), "title" to JsonPrimitive(title),
            "trackIndex" to JsonPrimitive(trackIndex), "positionMs" to JsonPrimitive(positionMs), "speed" to JsonPrimitive(speed), "playing" to JsonPrimitive(playing),
            "volume" to JsonPrimitive(volume)))
        lastState = m
        send(m)
    }

    /** Sends a command to [target] and waits for its ack (5 s). Returns an error message, or null when it worked. */
    suspend fun command(target: String, action: String, args: Map<String, Any?> = emptyMap()): String? {
        if (!open) return "Not connected to the server"
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<String>()
        pending[id] = d
        send(JsonObject(mapOf("t" to JsonPrimitive("cmd"), "id" to JsonPrimitive(id), "target" to JsonPrimitive(target), "action" to JsonPrimitive(action)) +
            args.mapValues { (_, v) -> json(v) }))
        val r = withTimeoutOrNull(5_000) { d.await() } ?: "The device didn't answer"
        pending.remove(id)
        return r.ifEmpty { null }
    }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull; is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString())
    }

    private suspend fun onFrame(m: JsonObject) {
        when (m.str("t")) {
            "hello" -> m.num("now")?.let { offset = it.toLong() - System.currentTimeMillis() }
            "devices" -> {
                m.num("now")?.let { offset = it.toLong() - System.currentTimeMillis() }
                _devices.value = m["devices"]!!.jsonArray.map { it.jsonObject }.map { d ->
                    LiveDevice(d.str("id")!!, d.str("name").orEmpty(), d.str("kind").orEmpty(), d.flag("online"), d.flag("active"),
                        (d["state"] as? JsonObject)?.let { s ->
                            LiveState(s.str("bookId"), s.str("title"), s.num("trackIndex")?.toInt() ?: 0, s.num("positionMs")?.toLong() ?: 0,
                                s.num("speed")?.toFloat() ?: 1f, s.flag("playing"), s.num("at")?.toLong() ?: serverNow(), s.num("volume")?.toFloat())
                        })
                }
            }
            "cmd" -> {
                val id = m.str("id") ?: return
                val err = onCommand?.invoke(m) ?: if (onCommand == null) "Player not ready" else null
                send(JsonObject(mapOf("t" to JsonPrimitive("ack"), "id" to JsonPrimitive(id), "ok" to JsonPrimitive(err == null), "error" to json(err))))
            }
            "ack" -> m.str("id")?.let { pending.remove(it) }?.complete(if (m.flag("ok")) "" else m.str("error") ?: "Failed")
            "pause" -> onPause?.invoke(m)
            "changed" -> Sync.request()
            "book" -> {
                val id = m.str("id") ?: return
                val conv = m.str("conversion")
                Cloud.onConversion(id, conv, m.num("progress"))
                if (conv != "converting") Sync.request()
            }
        }
    }

    private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } == true
}
