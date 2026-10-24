package app.mfbooks.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.mfbooks.MFBooksApp
import app.mfbooks.data.SyncSeen
import app.mfbooks.sync.SyncJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An HTTP error from the server, with its `{"error": …}` message. */
class ServerError(val code: Int, message: String) : IOException(message)

/** Where the app is linked: address, device token and sync cursor. Off until a server is connected (Settings → Server). */
data class Link(val url: String, val token: String, val deviceId: String, val name: String)

fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

/**
 * MFBooks Server connection (server/API.md). ponytail: the token lives in app-private SharedPreferences, like the rest
 * of the app's data; Android Keystore wrapping if the threat model ever includes a rooted phone.
 */
object Server {
    const val API_VERSION = 1
    private lateinit var prefs: SharedPreferences
    private val _link = MutableStateFlow<Link?>(null)
    val link: StateFlow<Link?> = _link
    /** A `mfbooks://pair` link opened from the web player's QR code, waiting for the Server page to use it. */
    val pendingPair = MutableStateFlow<Pair<String, String>?>(null)

    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
        val url = prefs.getString("url", null); val token = prefs.getString("token", null)
        if (url != null && token != null) _link.value = Link(url, token, prefs.getString("deviceId", "")!!, prefs.getString("name", "MFBooks")!!)
    }

    val connected get() = _link.value != null
    var lastSeq: Long
        get() = prefs.getLong("seq", 0)
        set(v) = prefs.edit().putLong("seq", v).apply()
    var lastSyncAt: Long
        get() = prefs.getLong("syncAt", 0)
        set(v) = prefs.edit().putLong("syncAt", v).apply()

    fun fileUrl(serverId: String, index: Int) = "${_link.value?.url}/api/v1/books/$serverId/files/$index"
    fun isServerUrl(uri: String) = _link.value?.let { uri.startsWith(it.url + "/") } == true
    fun authHeader() = _link.value?.let { "Bearer ${it.token}" }

    fun normalize(address: String): String {
        val a = address.trim().trimEnd('/')
        return if (a.startsWith("http://") || a.startsWith("https://")) a else "https://$a"
    }

    private val deviceName get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".let { if (Build.MODEL.startsWith(Build.MANUFACTURER, true)) Build.MODEL else it }

    /** Checks the server and links this phone with a pairing code from the web UI. */
    suspend fun pair(context: Context, address: String, code: String) =
        link(context, normalize(address), call(normalize(address), "POST", "/devices/pair", SyncJson.parseToJsonElement("""{"code":${JsonPrimitive(code)},"deviceName":${JsonPrimitive(deviceName)}}""")))

    suspend fun signIn(context: Context, address: String, user: String, password: String) = link(context, normalize(address), call(normalize(address), "POST", "/auth/login",
        JsonObject(mapOf("username" to JsonPrimitive(user), "password" to JsonPrimitive(password), "kind" to JsonPrimitive("android"), "deviceName" to JsonPrimitive(deviceName)))))

    private suspend fun link(context: Context, url: String, r: JsonElement) {
        val o = r.jsonObject
        val token = o.str("token") ?: throw IOException("No token from server")
        val info = call(url, "GET", "/info", null, token).jsonObject
        val l = Link(url, token, o.str("deviceId").orEmpty(), info.str("name") ?: "MFBooks")
        prefs.edit().putString("url", l.url).putString("token", l.token).putString("deviceId", l.deviceId).putString("name", l.name)
            .putLong("seq", 0).putLong("syncAt", 0).apply()
        val db = MFBooksApp.get(context).db.sync()
        db.clearSeen(); db.clearPending(); db.setSeen(SyncSeen("enabled", 0))
        _link.value = l
        Sync.connected(context)
    }

    /** The app keeps working locally; the server keeps everything it has. */
    suspend fun disconnect(context: Context) {
        runCatching { post("/auth/logout") }
        prefs.edit().clear().apply()
        val db = MFBooksApp.get(context).db.sync()
        db.clearSeen(); db.clearPending()
        _link.value = null
        Sync.disconnected(context)
    }

    suspend fun checkVersion(appVersion: String) {
        val info = get("/info").jsonObject
        if ((info["apiVersion"]?.jsonPrimitive?.int ?: 0) != API_VERSION) throw IOException("This server speaks a different API version")
        val min = info.str("minAppVersion").orEmpty()
        if (compareVersions(appVersion, min) < 0) throw IOException("The server needs MFBooks $min or newer")
    }

    suspend fun get(path: String) = call("GET", path)
    suspend fun post(path: String, body: JsonElement? = null) = call("POST", path, body)
    suspend fun delete(path: String) = call("DELETE", path)
    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
        val l = _link.value ?: throw IOException("Not connected")
        return call(l.url, method, path, body, l.token)
    }

    /** Raw chunk upload with `Upload-Offset`; returns the server's JSON (409 carries the offset to resume from). */
    suspend fun chunk(path: String, offset: Long, bytes: ByteArray, len: Int, type: String = "application/octet-stream"): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        val l = _link.value ?: throw IOException("Not connected")
        val req = Request.Builder().url(l.url + "/api/v1" + path).header("Authorization", "Bearer ${l.token}").header("Upload-Offset", offset.toString())
            .post(bytes.toRequestBody(type.toMediaType(), 0, len)).build()
        http.newCall(req).execute().use { r -> r.code to (r.body.string().takeIf { it.isNotBlank() }?.let { SyncJson.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())) }
    }

    /** Opens a download stream (caller closes it). */
    suspend fun download(path: String): okhttp3.Response = withContext(Dispatchers.IO) {
        val l = _link.value ?: throw IOException("Not connected")
        val r = http.newCall(Request.Builder().url(l.url + "/api/v1" + path).header("Authorization", "Bearer ${l.token}").build()).execute()
        if (!r.isSuccessful) { r.close(); throw ServerError(r.code, "HTTP ${r.code}") }
        r
    }

    private suspend fun call(url: String, method: String, path: String, body: JsonElement?, token: String? = null): JsonElement = withContext(Dispatchers.IO) {
        val rb: RequestBody? = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> "".toRequestBody(JSON)
            else -> null
        }
        val req = Request.Builder().url("$url/api/v1$path").method(method, rb).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            val json = text.takeIf { it.isNotBlank() }?.let { runCatching { SyncJson.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
            if (!r.isSuccessful) {
                if (r.code == 401 && token == _link.value?.token && path != "/auth/logout") onRevoked()
                throw ServerError(r.code, (json as? JsonObject)?.str("error") ?: "HTTP ${r.code}")
            }
            json
        }
    }

    /** The device was revoked from the web UI: stop syncing, keep local data. */
    private fun onRevoked() {
        _link.value?.let { prefs.edit().clear().apply(); _link.value = null; Sync.revoked() }
    }
}

fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
    return 0
}
