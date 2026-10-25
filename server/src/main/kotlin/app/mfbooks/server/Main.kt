package app.mfbooks.server

import app.mfbooks.sync.SyncJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

const val VERSION = "0.2.1"
const val MIN_APP_VERSION = "0.2.0"

val log = LoggerFactory.getLogger("mfbooks")!!

/** Everything the routes share. Config comes only from the environment (see .env.example). */
class App(val data: File) {
    val db = Db(File(data.apply { mkdirs() }, "mfbooks.db").path)
    val books = File(data, "books").apply { mkdirs() }
    val uploads = File(data, "uploads").apply { mkdirs() }
    val secret: ByteArray = db.get("secret")?.let(Base64.getDecoder()::decode) ?: random(32).also { db.set("secret", Base64.getEncoder().encodeToString(it)) }
    val records = Records(db)
    val auth = Auth(this)
    val live = Live(this)
    val converter = Converter(this)
    val library = Library(this)

    val setupDone get() = db.get("password") != null
    val convertOn get() = db.get("convert") != "false"
    val serverName get() = db.get("serverName") ?: "MFBooks"
}

fun main() {
    val app = App(File(System.getenv("MFBOOKS_DATA") ?: "data"))
    val web = System.getenv("MFBOOKS_WEB")?.let(::File)
    val port = System.getenv("MFBOOKS_PORT")?.toInt() ?: 8080
    log.info("MFBooks Server $VERSION on port $port, data in ${app.data.absolutePath}")
    if (!app.setupDone) log.warn("Not set up yet: open the server in a browser to create the owner account")
    embeddedServer(CIO, port) { module(app, web) }.start(wait = true)
}

class ApiError(val status: HttpStatusCode, override val message: String) : Exception(message)

fun fail(status: HttpStatusCode, message: String): Nothing = throw ApiError(status, message)

data class Device(val id: String, val name: String, val kind: String)

private val DeviceKey = AttributeKey<Device>("device")
val ApplicationCall.device get() = attributes[DeviceKey]

fun Application.module(app: App, web: File? = null) {
    install(XForwardedHeaders)
    install(ContentNegotiation) { json(SyncJson) }
    install(WebSockets) { pingPeriod = 20.seconds; maxFrameSize = 1L shl 20 }
    install(PartialContent)
    install(AutoHeadResponse)
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, j("error" to e.message)) }
        exception<Throwable> { call, e ->
            // The listener seeked, skipped or closed the tab mid-stream: nothing to answer, nothing to log.
            if (e.clientGone()) return@exception
            log.error("${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')}", e)
            call.respond(HttpStatusCode.InternalServerError, j("error" to "Server error"))
        }
    }
    app.converter.start(this)
    app.live.start(this)
    val requireDevice = createRouteScopedPlugin("RequireDevice") {
        onCall { call -> call.attributes.put(DeviceKey, app.auth.device(call) ?: fail(HttpStatusCode.Unauthorized, "Sign in first")) }
    }
    routing {
        route("/api/v1") {
            publicRoutes(app)
            mediaRoutes(app) // signed links or a device
            // route("") would be /api/v1 itself; a transparent child scopes the plugin to these routes only.
            createChild(object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
            }).apply {
                install(requireDevice)
                accountRoutes(app)
                libraryRoutes(app)
                syncRoutes(app)
                liveRoutes(app)
            }
        }
        if (web != null) staticFiles("/", web) { default("index.html") }
    }
}

/** A write to a connection the client already closed (Ogg seeking alone cancels a request per bisection step). */
fun Throwable.clientGone() = generateSequence(this) { it.cause }.any {
    it is java.io.IOException && (it.javaClass.simpleName.startsWith("Closed") || it.message in setOf("Broken pipe", "Connection reset by peer"))
}

fun random(n: Int) = ByteArray(n).also(SecureRandom()::nextBytes)

/** Builds a JSON object from plain Kotlin values (strings, numbers, booleans, null, lists, maps, JSON elements). */
fun j(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    is Iterable<*> -> JsonArray(v.map(::json))
    else -> error("not JSON: ${v::class}")
}
