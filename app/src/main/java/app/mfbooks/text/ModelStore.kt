package app.mfbooks.text

import android.content.Context
import app.mfbooks.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Vosk models in filesDir/vosk: fetched on demand, checked against a pinned hash, moved into place only when complete. */
object ModelStore {
    class Spec(val lang: String, val name: String, val engine: String, val sizeBytes: Long, val sha256: String) {
        val url get() = "https://alphacephei.com/vosk/models/$name.zip"
    }

    val specs = listOf(
        Spec("ru", "vosk-model-small-ru-0.22", "vosk-small-ru-0.22", 45L shl 20, "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"),
        Spec("en", "vosk-model-small-en-us-0.15", "vosk-small-en-us-0.15", 40L shl 20, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    )
    private val lock = Mutex()

    fun spec(lang: String) = specs.firstOrNull { it.lang == lang }
    private fun root(context: Context) = File(context.filesDir, "vosk")
    fun dir(context: Context, s: Spec) = File(root(context), s.name)
    /** The directory only appears through the final rename, so its existence means it's complete. */
    fun installed(context: Context, s: Spec) = dir(context, s).isDirectory

    fun delete(context: Context, lang: String) { spec(lang)?.let { dir(context, it).deleteRecursively() } }

    /** The model's directory, downloading it first if needed. Throws IOException. ponytail: not resumable, a retry starts over (45 MB). */
    suspend fun ensure(context: Context, s: Spec, progress: (Float) -> Unit = {}): File = lock.withLock {
        val target = dir(context, s)
        if (target.isDirectory) return@withLock target
        withContext(Dispatchers.IO) {
            val root = root(context).apply { mkdirs() }
            val zip = File(root, "${s.name}.zip.part")
            val tmp = File(root, "${s.name}.tmp")
            tmp.deleteRecursively()
            try {
                download(s, zip, progress)
                zip.inputStream().use { unzipTo(it, tmp) }
                val extracted = File(tmp, s.name)
                if (!extracted.isDirectory) throw IOException("${s.name}: unexpected archive layout")
                if (!extracted.renameTo(target)) throw IOException("${s.name}: can't move the model into place")
                target
            } finally { zip.delete(); tmp.deleteRecursively() }
        }
    }

    private suspend fun download(s: Spec, to: File, progress: (Float) -> Unit) {
        Server.http.newCall(Request.Builder().url(s.url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("${s.name}: HTTP ${res.code}")
            val body = res.body
            val total = body.contentLength().takeIf { it > 0 } ?: s.sizeBytes
            val sha = MessageDigest.getInstance("SHA-256")
            to.outputStream().use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sha.update(buf, 0, n); done += n
                        progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (hex != s.sha256) throw IOException("${s.name}: checksum mismatch")
        }
    }

    /** Extracts into [dir]; an entry that would land outside it aborts the whole extraction. */
    fun unzipTo(input: InputStream, dir: File) {
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(dir, e.name)
                if (!(out.canonicalPath + File.separator).startsWith(root)) throw IOException("bad path in archive: ${e.name}")
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }
}
