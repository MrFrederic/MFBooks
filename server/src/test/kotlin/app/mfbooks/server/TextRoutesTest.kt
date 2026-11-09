package app.mfbooks.server

import app.mfbooks.text.Block
import app.mfbooks.text.BookTextDoc
import app.mfbooks.text.SyncDoc
import app.mfbooks.text.SyncTrack
import app.mfbooks.text.TextJson
import app.mfbooks.text.TextSource
import app.mfbooks.text.gzip
import app.mfbooks.text.sha256Hex
import app.mfbooks.text.toGz
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Read-along files, with pre-built documents so only the source upload test needs the parser. */
class TextRoutesTest {
    private val dir = Files.createTempDirectory("mfbooks-text").toFile()
    private val limit = 64L * 1024
    private val app = App(File(dir, "data"), textLimit = limit)

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun doc(s: String) = BookTextDoc(lang = "en", source = TextSource("b.fb2", "fb2", "0"), blocks = listOf(Block("p", s, listOf(0))))
    private fun map(textSha: String, tier: String = "aligned") = SyncDoc(textSha = textSha, tier = tier, tracks = listOf(SyncTrack("01.mp3", 4000)), chars = listOf(0, 6), ms = listOf(0, 2000))

    private suspend fun ApplicationTestBuilder.signedIn(): HttpClient {
        application { module(app) }
        val web = createClient { install(HttpCookies) }
        web.post("/api/v1/setup") { contentType(ContentType.Application.Json); setBody("""{"username":"me","password":"correct horse","serverName":"Home","convert":false}""") }
        return web
    }

    private suspend fun HttpClient.newBook() = post("/api/v1/books") {
        contentType(ContentType.Application.Json)
        setBody("""{"data":{"title":"T","durationMs":4000,"tracks":[{"index":0,"fileName":"01.mp3","durationMs":4000,"sizeBytes":10}]}}""")
    }.obj().str("id")!!

    @Test
    fun featureFlagAndAuth() = testApplication {
        val web = signedIn()
        assertEquals(listOf("text"), web.get("/api/v1/info").obj()["features"]!!.jsonArray.map { it.toString().trim('"') })
        val id = web.newBook()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/books/$id/text/content").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/books/$id/text/content") { setBody(doc("x").toGz()) }.status)
        assertEquals(HttpStatusCode.NotFound, web.get("/api/v1/books/${java.util.UUID.randomUUID()}/text/content").status)
        assertEquals(HttpStatusCode.NotFound, web.put("/api/v1/books/not-a-uuid/text/content") { setBody(doc("x").toGz()) }.status)
        assertEquals(HttpStatusCode.NotFound, web.get("/api/v1/books/$id/text/content").status)
        dir.deleteRecursively()
    }

    @Test
    fun contentSyncEtagAndRules() = testApplication {
        val web = signedIn()
        val id = web.newBook()
        val base = "/api/v1/books/$id/text"
        val gz = doc("Hello world.").toGz()
        val sha = sha256Hex(gz)

        // Sync needs its text first.
        assertEquals("no_text", web.put("$base/sync") { setBody(map(sha).toGz()) }.obj().str("reason"))
        val put = web.put("$base/content") { setBody(gz) }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals(sha, put.obj().str("textSha"))

        // Served as stored: gzip header, ETag, 304.
        val got = web.get("$base/content")
        assertEquals("gzip", got.headers[HttpHeaders.ContentEncoding])
        assertEquals("\"$sha\"", got.headers[HttpHeaders.ETag])
        assertContentEquals(gz, got.bodyAsBytes())
        assertEquals(HttpStatusCode.NotModified, web.get("$base/content") { header(HttpHeaders.IfNoneMatch, "\"$sha\"") }.status)
        assertEquals(HttpStatusCode.OK, web.get("$base/content") { header(HttpHeaders.IfNoneMatch, "\"other\"") }.status)

        // The first content stays; an identical one is fine; a replace has to say what it replaces.
        assertEquals(HttpStatusCode.OK, web.put("$base/content") { setBody(gz) }.status)
        val other = doc("Other words.").toGz()
        val refused = web.put("$base/content") { setBody(other) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("exists", refused.obj().str("reason"))
        assertContentEquals(gz, web.get("$base/content").bodyAsBytes())

        // Sync: must match the text, If-Match guards, no header is last writer wins, the record hears about it.
        val now = System.currentTimeMillis()
        web.post("/api/v1/sync") { contentType(ContentType.Application.Json); setBody("""{"since":0,"now":$now,"changes":[{"kind":"text","id":"$id","bookId":"$id","updatedAt":$now,
            "data":{"textSha":"$sha","syncSha":null,"lang":"en","words":2,"tier":"approximate","coverage":0,"offsetMs":0,"sourceName":"b.fb2"}}]}""") }
        val recordTime = web.get("/api/v1/books/$id").obj()["text"]!!.jsonObject.long("updatedAt")
        assertEquals("stale_text", web.put("$base/sync") { setBody(map("nope").toGz()) }.obj().str("reason"))
        val s1 = map(sha).toGz()
        val s1Sha = web.put("$base/sync") { setBody(s1) }.obj().str("syncSha")!!
        assertEquals(sha256Hex(s1), s1Sha)
        assertEquals("gzip", web.get("$base/sync").headers[HttpHeaders.ContentEncoding])
        val text = web.get("/api/v1/books/$id").obj()["text"]!!.jsonObject
        assertEquals(s1Sha, text.str("syncSha"))
        assertEquals("aligned", text.str("tier"))
        assertEquals(recordTime, text.long("updatedAt"), "a patch must not move the record's time")

        val s2 = map(sha, "approximate").toGz()
        val stale = web.put("$base/sync") { header(HttpHeaders.IfMatch, "\"wrong\""); setBody(s2) }
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals(s1Sha, stale.obj().str("syncSha"))
        assertEquals(HttpStatusCode.OK, web.put("$base/sync") { header(HttpHeaders.IfMatch, "\"$s1Sha\""); setBody(s2) }.status)
        assertEquals(HttpStatusCode.OK, web.put("$base/sync") { setBody(s1) }.status)

        // A replace from a client that knows the current version: the old map goes, the record follows.
        val replaced = web.put("$base/content") { header(HttpHeaders.IfMatch, "\"$sha\""); setBody(other) }
        assertEquals(HttpStatusCode.OK, replaced.status)
        assertContentEquals(other, web.get("$base/content").bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, web.get("$base/sync").status)
        val after = web.get("/api/v1/books/$id").obj()["text"]!!.jsonObject
        assertEquals(sha256Hex(other), after.str("textSha"))
        assertTrue(after["syncSha"] is JsonNull)

        // DELETE: files and record gone, and the same book can get new text.
        assertEquals(HttpStatusCode.OK, web.delete(base).status)
        assertEquals(HttpStatusCode.NotFound, web.get("$base/content").status)
        assertTrue(web.get("/api/v1/books/$id").obj()["text"] is JsonNull)
        val tomb = web.get("/api/v1/sync?since=0").obj()["changes"]!!.jsonArray.map { it.jsonObject }.first { it.str("kind") == "text" }
        assertEquals(true, tomb.bool("deleted"))
        assertEquals(HttpStatusCode.OK, web.put("$base/content") { setBody(gz) }.status)

        // A pushed tombstone removes the files too (a phone deleting offline).
        val later = System.currentTimeMillis() + 60_000
        web.post("/api/v1/sync") { contentType(ContentType.Application.Json); setBody("""{"since":0,"now":${System.currentTimeMillis()},"changes":[{"kind":"text","id":"$id","bookId":"$id","updatedAt":$later,"deleted":true}]}""") }
        assertEquals(HttpStatusCode.NotFound, web.get("$base/content").status)

        // Removing the book removes its text directory.
        web.put("$base/content") { setBody(gz) }
        assertTrue(File(app.books, "$id/text").isDirectory)
        web.delete("/api/v1/books/$id")
        assertFalse(File(app.books, "$id/text").exists())
        dir.deleteRecursively()
    }

    @Test
    fun limitsAndBadInput() = testApplication {
        val web = signedIn()
        val id = web.newBook()
        val base = "/api/v1/books/$id/text"

        // Over the cap: refused and nothing left behind.
        val big = java.util.Random(1).let { r -> ByteArray((limit + 1).toInt()).also(r::nextBytes) }
        val over = web.put("$base/content") { setBody(big) }
        assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
        assertEquals("too_large", over.obj().str("reason"))
        assertEquals(emptyList(), app.uploads.listFiles().orEmpty().map { it.name })

        // Small gzip, huge inside.
        val bomb = web.put("$base/content") { setBody(gzip(ByteArray((limit * 8).toInt()))) }
        assertEquals(HttpStatusCode.PayloadTooLarge, bomb.status)

        assertEquals("invalid", web.put("$base/content") { setBody("not gzip".toByteArray()) }.obj().str("reason"))
        assertEquals("invalid", web.put("$base/content") { setBody(gzip(TextJson.encodeToString(JsonObject.serializer(), JsonObject(emptyMap())).toByteArray())) }.obj().str("reason"))
        assertEquals(HttpStatusCode.NotFound, web.get("$base/content").status)

        // Source: only known formats, and the client's name is never a path.
        val unknown = web.put("$base/source?name=book.pdf") { setBody("x".toByteArray()) }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("unsupported", unknown.obj().str("reason"))
        assertEquals(HttpStatusCode.BadRequest, web.put("$base/source") { setBody("x".toByteArray()) }.status)
        dir.deleteRecursively()
    }

    /** Needs TextParser and Approximate: throws NotImplementedError (500) until the shared module lands. */
    @Test
    fun sourceUploadParses() = testApplication {
        val web = signedIn()
        val id = web.newBook()
        val base = "/api/v1/books/$id/text"
        val txt = "A first paragraph of the book.\n\nA second paragraph, a little longer than the first one was.\n".toByteArray()
        val r = web.put("$base/source?name=../../evil.txt") { setBody(txt) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(true, r.obj().bool("parsed"))
        assertTrue(File(app.books, "$id/text/source.txt").isFile)
        assertFalse(File(app.books, "evil.txt").exists())
        assertEquals(HttpStatusCode.OK, web.get("$base/content").status)
        assertEquals(HttpStatusCode.OK, web.get("$base/sync").status)
        val text = web.get("/api/v1/books/$id").obj()["text"]!!.jsonObject
        assertEquals(r.obj().str("textSha"), text.str("textSha"))
        assertEquals("approximate", text.str("tier"))
        assertEquals("evil.txt", text.str("sourceName"))

        // Content exists: the same file is kept, nothing re-parsed; a different one is refused.
        assertEquals(false, web.put("$base/source?name=b.txt") { setBody(txt) }.obj().bool("parsed"))
        assertEquals(HttpStatusCode.Conflict, web.put("$base/source?name=b.txt") { setBody("Something else entirely.".toByteArray()) }.status)
        dir.deleteRecursively()
    }
}
