package app.mfbooks.server

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole flow against a real data folder and real ffmpeg: setup, web upload + import + conversion, streaming, sync, app upload, live. */
class ServerTest {
    private val dir = Files.createTempDirectory("mfbooks").toFile()
    private fun gen(name: String, vararg args: String) = File(dir, name).also {
        assertEquals(0, ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + args + it.path).start().waitFor())
    }
    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun endToEnd() = testApplication {
        val app = App(File(dir, "data"))
        application { module(app) }
        val web = createClient { install(HttpCookies); install(WebSockets) }

        // Setup, then everything needs auth; a wrong password is refused.
        assertEquals(false, web.get("/api/v1/info").obj().bool("setupDone"))
        web.post("/api/v1/setup") { contentType(ContentType.Application.Json); setBody("""{"username":"me","password":"correct horse","serverName":"Home","convert":true}""") }
            .also { assertEquals(HttpStatusCode.OK, it.status) }
        assertEquals(HttpStatusCode.Conflict, web.post("/api/v1/setup") { contentType(ContentType.Application.Json); setBody("""{"username":"x","password":"12345678"}""") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/books").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"me","password":"nope nope"}""") }.status)
        val phoneToken = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"me","password":"correct horse","kind":"android","deviceName":"Pixel"}""") }
            .obj().str("token")!!

        // Web upload of a zip: two 128 kb/s MP3s, a cover and an .nfo, in a wrapping folder.
        val a = gen("01.mp3", "-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-c:a", "libmp3lame", "-b:a", "128k")
        val b = gen("02.mp3", "-f", "lavfi", "-i", "sine=frequency=660:duration=3", "-c:a", "libmp3lame", "-b:a", "128k")
        val cover = gen("cover.jpg", "-f", "lavfi", "-i", "color=red:s=64x64", "-frames:v", "1")
        val zip = File(dir, "book.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in listOf(a, b, cover)) { z.putNextEntry(ZipEntry("Some Author - Test Book/${f.name}")); z.write(f.readBytes()) }
            z.putNextEntry(ZipEntry("Some Author - Test Book/info.nfo")); z.write("Title: Test Book\nAuthor: Some Author\n".toByteArray())
        }
        val bytes = zip.readBytes()
        val up = web.post("/api/v1/uploads") { contentType(ContentType.Application.Json); setBody("""{"name":"book.zip","size":${bytes.size}}""") }.obj()
        val upId = up.str("id")!!
        val half = bytes.size / 2
        web.post("/api/v1/uploads/$upId") { header("Upload-Offset", 0); setBody(bytes.copyOfRange(0, half)) }
        // A retried chunk at a stale offset is refused with the offset to resume from.
        assertEquals(HttpStatusCode.Conflict, web.post("/api/v1/uploads/$upId") { header("Upload-Offset", 0); setBody(bytes) }.status)
        web.post("/api/v1/uploads/$upId") { header("Upload-Offset", half); setBody(bytes.copyOfRange(half, bytes.size)) }
        val bookId = withTimeout(30_000) {
            while (true) { val u = web.get("/api/v1/uploads/$upId").obj(); if (u.str("state") == "done") return@withTimeout u.str("bookId")!!; assertTrue(u.str("state") != "failed", u.toString()); delay(200) }
            @Suppress("UNREACHABLE_CODE") ""
        }
        // Conversion to Opus, verified per track; the originals' hashes stay the reference.
        val book = withTimeout(60_000) {
            while (true) { val d = web.get("/api/v1/books/$bookId").obj(); val c = d["cloud"]!!.jsonObject; if (c.str("conversion") == "done") return@withTimeout d; assertTrue(c.str("conversion") != "failed", c.toString()); delay(300) }
            @Suppress("UNREACHABLE_CODE") JsonObject(emptyMap())
        }
        assertEquals("Test Book", book.str("title"))
        assertEquals("Some Author", book.str("author"))
        assertEquals(true, book.bool("cover"))
        assertEquals(true, book["cloud"]!!.jsonObject.bool("verified"))
        val tracks = book["tracks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("opus", "opus"), tracks.map { it["cloud"]!!.jsonObject.str("format") })
        assertEquals(2, book["chapters"]!!.jsonArray.size)

        // Streaming with Range.
        val part = web.get("/api/v1/books/$bookId/files/0") { header(HttpHeaders.Range, "bytes=0-99") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals(100, part.bodyAsBytes().size)
        assertEquals("OggS", String(part.bodyAsBytes(), 0, 4))
        // Signed links work without auth, until tampered with.
        val link = client.post("/api/v1/books/$bookId/links") { header("Authorization", "Bearer $phoneToken") }.obj()["files"]!!.jsonArray[1].toString().trim('"')
        val path = link.substringAfter("localhost").substringAfter("80")
        assertEquals(HttpStatusCode.OK, client.get(path).status, path)
        assertEquals(HttpStatusCode.Unauthorized, client.get(path.replace("sig=", "sig=0")).status)

        // Sync: the phone pushes progress; a stale offline write from the browser becomes a conflict, not a silent overwrite.
        suspend fun sync(auth: Boolean, body: String) = (if (auth) client.post("/api/v1/sync") { header("Authorization", "Bearer $phoneToken"); contentType(ContentType.Application.Json); setBody(body) }
            else web.post("/api/v1/sync") { contentType(ContentType.Application.Json); setBody(body) }).obj()
        val now = System.currentTimeMillis()
        val r1 = sync(true, """{"since":0,"now":$now,"changes":[{"kind":"progress","id":"$bookId","updatedAt":$now,"data":{"trackIndex":1,"positionInTrackMs":2000}},
            {"kind":"history","id":"h1","bookId":"$bookId","updatedAt":$now,"data":{"action":"START","trackIndex":1,"positionMs":0,"at":$now}}]}""")
        assertTrue(r1["changes"]!!.jsonArray.any { it.jsonObject.str("kind") == "progress" })
        sync(false, """{"since":0,"now":${now + 1000},"changes":[{"kind":"progress","id":"$bookId","updatedAt":${now + 1000},"base":0,"data":{"trackIndex":0,"positionInTrackMs":100}},
            {"kind":"history","id":"h1","bookId":"$bookId","updatedAt":${now + 1000},"data":{"action":"changed","trackIndex":0,"positionMs":0,"at":$now}},
            {"kind":"book","id":"$bookId","updatedAt":${now + 1000},"data":{"state":"FINISHED"}}]}""")
        val d2 = web.get("/api/v1/books/$bookId").obj()
        assertEquals("Pixel", d2["conflict"]!!.jsonObject.str("deviceName"))
        assertEquals("FINISHED", d2.str("state"))
        assertEquals("Test Book", d2.str("title")) // partial push merged, not replaced
        val history = web.get("/api/v1/sync?since=0").obj()["changes"]!!.jsonArray.map { it.jsonObject }.first { it.str("kind") == "history" }
        assertEquals("START", history["data"]!!.jsonObject.str("action")) // union: an event never changes

        // App upload: manifest, then chunks; a wrong checksum is refused and the offset reset.
        val sha = sha256File(a)
        val reg = client.post("/api/v1/books") { header("Authorization", "Bearer $phoneToken"); contentType(ContentType.Application.Json)
            setBody("""{"data":{"title":"Phone Book","durationMs":4000,"tracks":[{"index":0,"fileName":"01.mp3","durationMs":4000,"sizeBytes":${a.length()},"sha256":"$sha"},
                {"index":1,"fileName":"02.mp3","durationMs":3000,"sizeBytes":${b.length()},"sha256":"${"0".repeat(64)}"}]}}""") }.obj()
        val pid = reg.str("id")!!
        val ok = client.post("/api/v1/books/$pid/files/0") { header("Authorization", "Bearer $phoneToken"); header("Upload-Offset", 0); setBody(a.readBytes()) }
        assertEquals(a.length(), ok.obj().long("offset"))
        val bad = client.post("/api/v1/books/$pid/files/1") { header("Authorization", "Bearer $phoneToken"); header("Upload-Offset", 0); setBody(b.readBytes()) }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertEquals(0L, client.get("/api/v1/books/$pid/files/1/offset") { header("Authorization", "Bearer $phoneToken") }.obj().long("offset"))

        // Live: when the phone starts playing, the playing browser is told to pause; commands are relayed and acked.
        val phoneWs = createClient { install(WebSockets) }
        web.webSocket("/api/v1/live") {
            val webWs = this
            val webId = (incoming.receive() as Frame.Text).readText().let { Json.parseToJsonElement(it).jsonObject.str("deviceId")!! }
            send(Frame.Text("""{"t":"state","bookId":"$bookId","trackIndex":0,"positionMs":0,"speed":1,"playing":true}"""))
            phoneWs.webSocket("/api/v1/live", request = { header("Authorization", "Bearer $phoneToken") }) {
                send(Frame.Text("""{"t":"state","bookId":"$bookId","trackIndex":1,"positionMs":500,"speed":1,"playing":true}"""))
                send(Frame.Text("""{"t":"cmd","id":"c1","target":"$webId","action":"seek","trackIndex":1,"positionMs":0}"""))
                var cmdSeen = false; var paused = false
                withTimeout(5_000) {
                    for (f in webWs.incoming) {
                        val m = Json.parseToJsonElement((f as Frame.Text).readText()).jsonObject
                        if (m.str("t") == "cmd") { cmdSeen = true; webWs.send(Frame.Text("""{"t":"ack","id":"${m.str("id")}","ok":true}""")) }
                        if (m.str("t") == "pause") { paused = true; assertEquals(500L, (m["state"] as JsonObject).long("positionMs")) }
                        if (cmdSeen && paused) break
                    }
                }
                withTimeout(5_000) {
                    for (f in incoming) { val m = Json.parseToJsonElement((f as Frame.Text).readText()).jsonObject; if (m.str("t") == "ack") { assertEquals(true, m.bool("ok")); break } }
                }
            }
        }

        // Deleting the cloud copy keeps the record; the export holds the database and records.
        web.delete("/api/v1/books/$bookId/files")
        assertEquals(null, web.get("/api/v1/books/$bookId").obj()["cloud"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/export") { header("Authorization", "Bearer $phoneToken") }.status)
        assertTrue(web.get("/api/v1/books").bodyAsText().contains("Phone Book"))
        dir.deleteRecursively()
    }
}
