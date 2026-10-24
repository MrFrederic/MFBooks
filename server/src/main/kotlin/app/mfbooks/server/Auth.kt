package app.mfbooks.server

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val COOKIE = "mfb"
private const val WINDOW_MS = 15 * 60_000L

fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private val b64 = Base64.getUrlEncoder().withoutPadding()

/** Failed attempts per client and in total, over 15 minutes. Covers sign-in, reset and pairing. */
class Limiter(private val perClient: Int = 5, private val total: Int = 30) {
    private val fails = HashMap<String, ArrayDeque<Long>>()

    @Synchronized fun check(key: String) {
        val now = System.currentTimeMillis()
        fails.values.forEach { q -> while (q.isNotEmpty() && q.first() < now - WINDOW_MS) q.removeFirst() }
        if ((fails[key]?.size ?: 0) >= perClient || (fails[""]?.size ?: 0) >= total) fail(HttpStatusCode.TooManyRequests, "Too many attempts. Try again in 15 minutes.")
    }

    @Synchronized fun failed(key: String) = listOf(key, "").forEach { fails.getOrPut(it) { ArrayDeque() }.addLast(System.currentTimeMillis()) }
}

class Auth(private val app: App) {
    private val db = app.db
    val limiter = Limiter()
    private val pairing = ConcurrentHashMap<String, Long>() // code → expiry
    @Volatile private var reset: Pair<String, Long>? = null
    private val seen = ConcurrentHashMap<String, Long>()

    // Argon2id, OWASP's second option: 19 MiB would do, 64 MiB / 3 passes is still well under a second.
    fun hashPassword(pw: String): String {
        val salt = random(16)
        return "argon2id\$65536\$3\$1\$${b64.encodeToString(salt)}\$${b64.encodeToString(argon(pw, salt, 65536, 3, 1))}"
    }

    fun checkPassword(pw: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 6 || p[0] != "argon2id") return false
        val want = Base64.getUrlDecoder().decode(p[5])
        return MessageDigest.isEqual(want, argon(pw, Base64.getUrlDecoder().decode(p[4]), p[1].toInt(), p[2].toInt(), p[3].toInt()))
    }

    private fun argon(pw: String, salt: ByteArray, memKb: Int, iter: Int, par: Int) = ByteArray(32).also {
        Argon2BytesGenerator().apply {
            init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(memKb).withIterations(iter).withParallelism(par).build())
        }.generateBytes(pw.toCharArray(), it)
    }

    /** 256-bit random token; only its hash is stored. */
    fun newDevice(name: String, kind: String): Pair<Device, String> {
        val token = b64.encodeToString(random(32))
        val d = Device(UUID.randomUUID().toString(), name.take(80).ifBlank { kind }, kind)
        val now = System.currentTimeMillis()
        db.update("INSERT INTO devices(id, name, kind, token_hash, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)", d.id, d.name, d.kind, sha256(token.toByteArray()), now, now)
        return d to token
    }

    fun device(call: ApplicationCall): Device? {
        val token = call.request.cookies[COOKIE] ?: call.request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        val d = db.query("SELECT id, name, kind FROM devices WHERE token_hash = ?", sha256(token.toByteArray())) { Device(it.getString(1), it.getString(2), it.getString(3)) }.firstOrNull()
        val now = System.currentTimeMillis()
        if (d != null && now - (seen[d.id] ?: 0) > 60_000) { seen[d.id] = now; db.update("UPDATE devices SET last_seen = ? WHERE id = ?", now, d.id) }
        return d
    }

    fun setCookie(call: ApplicationCall, token: String?) = call.response.cookies.append(Cookie(
        COOKIE, token.orEmpty(), maxAge = if (token == null) 0 else 400 * 86_400, path = "/", httpOnly = true,
        secure = call.request.origin.scheme == "https", extensions = mapOf("SameSite" to "Strict"),
    ))

    fun clientKey(call: ApplicationCall) = call.request.origin.remoteHost

    fun pairingCode(): Pair<String, Long> {
        val code = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random(1)[0].toInt().and(0xFF) % 32] }.joinToString("")
        val exp = System.currentTimeMillis() + 10 * 60_000
        pairing.entries.removeIf { it.value < System.currentTimeMillis() }
        pairing[code] = exp
        return code to exp
    }

    fun usePairingCode(code: String) = pairing.remove(code.uppercase().trim())?.let { it > System.currentTimeMillis() } == true

    fun newResetCode(): String {
        val code = (1..10).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random(1)[0].toInt().and(0xFF) % 32] }.joinToString("")
        reset = code to System.currentTimeMillis() + WINDOW_MS
        return code
    }

    fun useResetCode(code: String): Boolean {
        val r = reset ?: return false
        val ok = r.second > System.currentTimeMillis() && MessageDigest.isEqual(r.first.toByteArray(), code.uppercase().trim().toByteArray())
        if (ok) reset = null
        return ok
    }
}

private fun password(body: JsonObject) = body.str("password").orEmpty().also {
    if (it.length < 8) fail(HttpStatusCode.BadRequest, "Use at least 8 characters")
}

fun Route.publicRoutes(app: App) {
    val auth = app.auth
    get("/info") { call.respond(j("name" to app.serverName, "version" to VERSION, "apiVersion" to 1, "minAppVersion" to MIN_APP_VERSION, "setupDone" to app.setupDone)) }
    get("/setup") { call.respond(j("setupDone" to app.setupDone)) }
    post("/setup") {
        val b = call.receive<JsonObject>()
        val user = b.str("username")?.trim().orEmpty().ifEmpty { fail(HttpStatusCode.BadRequest, "Choose a username") }
        val hash = auth.hashPassword(password(b))
        app.db.tx {
            if (app.setupDone) fail(HttpStatusCode.Conflict, "Already set up")
            app.db.set("username", user); app.db.set("password", hash)
            app.db.set("serverName", b.str("serverName")?.trim()?.ifEmpty { null } ?: "MFBooks")
            app.db.set("convert", (b.bool("convert") ?: true).toString())
        }
        log.info("Setup done for $user")
        val (d, token) = auth.newDevice(b.str("deviceName") ?: "Browser", "web")
        auth.setCookie(call, token)
        call.respond(j("deviceId" to d.id))
    }
    post("/auth/login") {
        val key = auth.clientKey(call)
        auth.limiter.check(key)
        val b = call.receive<JsonObject>()
        val ok = app.setupDone && b.str("username")?.trim() == app.db.get("username") && auth.checkPassword(b.str("password").orEmpty(), app.db.get("password")!!)
        if (!ok) { auth.limiter.failed(key); fail(HttpStatusCode.Unauthorized, "Wrong username or password") }
        val kind = if (b.str("kind") == "android") "android" else "web"
        val (d, token) = auth.newDevice(b.str("deviceName") ?: kind, kind)
        if (kind == "web") { auth.setCookie(call, token); call.respond(j("deviceId" to d.id)) } else call.respond(j("deviceId" to d.id, "token" to token))
    }
    post("/auth/reset/request") {
        val key = auth.clientKey(call)
        auth.limiter.check(key); auth.limiter.failed(key) // every request counts, so the log can't be flooded
        log.warn("Password reset code: ${auth.newResetCode()} (single use, valid 15 minutes)")
        call.respond(j())
    }
    post("/auth/reset") {
        val key = auth.clientKey(call)
        auth.limiter.check(key)
        val b = call.receive<JsonObject>()
        val pw = password(b)
        if (!auth.useResetCode(b.str("code").orEmpty())) { auth.limiter.failed(key); fail(HttpStatusCode.Unauthorized, "Wrong or expired code") }
        app.db.set("password", auth.hashPassword(pw))
        app.db.query("SELECT id FROM devices WHERE kind = 'web'") { it.getString(1) }.forEach { app.live.kick(it) }
        app.db.update("DELETE FROM devices WHERE kind = 'web'")
        log.warn("Password was reset; every browser is signed out")
        call.respond(j())
    }
    post("/devices/pair") {
        val key = auth.clientKey(call)
        auth.limiter.check(key)
        val b = call.receive<JsonObject>()
        if (!auth.usePairingCode(b.str("code").orEmpty())) { auth.limiter.failed(key); fail(HttpStatusCode.Unauthorized, "Wrong or expired code") }
        val (d, token) = auth.newDevice(b.str("deviceName") ?: "Phone", "android")
        call.respond(j("deviceId" to d.id, "token" to token))
    }
}

fun Route.accountRoutes(app: App) {
    get("/me") { val d = call.device; call.respond(j("username" to app.db.get("username"), "deviceId" to d.id, "deviceName" to d.name, "kind" to d.kind)) }
    post("/auth/logout") {
        app.db.update("DELETE FROM devices WHERE id = ?", call.device.id)
        app.live.kick(call.device.id)
        app.auth.setCookie(call, null)
        call.respond(j())
    }
    get("/devices") {
        val me = call.device.id
        call.respond(app.db.query("SELECT id, name, kind, last_seen FROM devices ORDER BY last_seen DESC") {
            val id = it.getString(1)
            j("id" to id, "name" to it.getString(2), "kind" to it.getString(3), "lastSeen" to it.getLong(4), "online" to app.live.isOnline(id), "current" to (id == me))
        })
    }
    delete("/devices/{id}") {
        val id = call.parameters["id"]!!
        app.db.update("DELETE FROM devices WHERE id = ?", id)
        app.live.kick(id)
        call.respond(j())
    }
    post("/devices/pairing") { val (code, exp) = app.auth.pairingCode(); call.respond(j("code" to code, "expiresAt" to exp)) }
    get("/settings") { call.respond(settings(app)) }
    put("/settings") {
        val b = call.receive<JsonObject>()
        b.str("serverName")?.trim()?.takeIf { it.isNotEmpty() }?.let { app.db.set("serverName", it) }
        b.bool("convert")?.let { app.db.set("convert", it.toString()); if (it) app.converter.resume() }
        b.long("storageWarnGb")?.let { app.db.set("storageWarnGb", it.coerceAtLeast(0).toString()) }
        call.respond(settings(app))
    }
    get("/export") { app.library.export(call) }
}

private fun settings(app: App): JsonObject {
    val used = app.db.long("SELECT COALESCE(SUM(received), 0) FROM files") + app.books.walk().filter { it.name.startsWith("cover.") }.sumOf { it.length() }
    val free = app.data.usableSpace
    val warn = app.db.get("storageWarnGb")?.toLong() ?: 10
    return j("serverName" to app.serverName, "convert" to app.convertOn, "storageWarnGb" to warn,
        "usedBytes" to used, "freeBytes" to free, "storageLow" to (free < warn * 1_000_000_000))
}
