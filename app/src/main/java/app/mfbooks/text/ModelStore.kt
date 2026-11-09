package app.mfbooks.text

import android.content.Context
import android.os.Build
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

/** Vosk in filesDir/vosk, native libraries and models alike: fetched only when asked, checked against a pinned hash, moved into place only when complete. */
object ModelStore {
    class Spec(val lang: String, val name: String, val engine: String, val sizeBytes: Long, val sha256: String) {
        val url get() = "https://alphacephei.com/vosk/models/$name.zip"
    }

    val specs = listOf(
        Spec("ru", "vosk-model-small-ru-0.22", "vosk-small-ru-0.22", 45L shl 20, "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"),
        Spec("en", "vosk-model-small-en-us-0.15", "vosk-small-en-us-0.15", 40L shl 20, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    )
    private val lock = Mutex()

    private class Archive(val name: String, val url: String, val sha256: String)
    /** The libraries come from the same Maven Central artifacts the app compiles against, so the versions must move together with app/build.gradle.kts. */
    private val runtime = listOf(
        Archive("vosk-android-0.3.75.aar", "https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar", "ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad"),
        Archive("jna-5.17.0.aar", "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.aar", "4dbeffffa665d97ad5aa7eee297531d3c841a86716ab7f774fd6956422b3cf38"),
    )
    private const val RUNTIME_BYTES = 14L shl 20

    fun spec(lang: String) = specs.firstOrNull { it.lang == lang }
    private fun root(context: Context) = File(context.filesDir, "vosk")
    fun dir(context: Context, s: Spec) = File(root(context), s.name)
    private fun runtimeDir(context: Context) = File(root(context), "runtime-0.3.75")
    /** Directories only appear through the final rename, so their existence means they're complete. */
    fun installed(context: Context, s: Spec) = runtimeDir(context).isDirectory && dir(context, s).isDirectory

    /** What fetching Vosk for [s] would download now. */
    fun downloadBytes(context: Context, s: Spec) =
        (if (runtimeDir(context).isDirectory) 0 else RUNTIME_BYTES) + (if (dir(context, s).isDirectory) 0 else s.sizeBytes)

    /** Tells JNA where the downloaded libraries are. Before anything touches a Vosk class. */
    fun prepareRuntime(context: Context) {
        val path = runtimeDir(context).path
        System.setProperty("jna.boot.library.path", path)
        System.setProperty("jna.library.path", path)
    }

    /** The libraries go with the last model. */
    fun delete(context: Context, lang: String) {
        spec(lang)?.let { dir(context, it).deleteRecursively() }
        if (specs.none { dir(context, it).isDirectory }) runtimeDir(context).deleteRecursively()
    }

    /** Downloads whatever is missing of Vosk for [s]. Throws IOException. ponytail: not resumable, a retry starts over (about 60 MB at most). */
    suspend fun ensure(context: Context, s: Spec, progress: (Float) -> Unit = {}): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val total = downloadBytes(context, s).coerceAtLeast(1).toFloat()
            if (!runtimeDir(context).isDirectory) {
                ensureRuntime(context) { progress(it * RUNTIME_BYTES / total) }
            }
            val before = (total - s.sizeBytes).coerceAtLeast(0f)
            ensureModel(context, s) { progress((before + it * s.sizeBytes) / total) }
        }
    }

    private suspend fun ensureRuntime(context: Context, progress: (Float) -> Unit) {
        val root = root(context).apply { mkdirs() }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: throw IOException("Unknown processor type")
        val tmp = File(root, "runtime.tmp")
        tmp.deleteRecursively()
        try {
            runtime.forEachIndexed { i, a ->
                val aar = File(root, "${a.name}.part")
                try {
                    download(a.name, a.url, a.sha256, RUNTIME_BYTES) { progress((i + it) / runtime.size) }
                        .let { body -> body(aar) }
                    aar.inputStream().use { extractNatives(it, abi, tmp) }
                } finally { aar.delete() }
            }
            if (tmp.listFiles()?.map { it.name }?.containsAll(listOf("libvosk.so", "libjnidispatch.so")) != true) throw IOException("Vosk isn't available for $abi")
            tmp.listFiles()?.forEach { it.setReadOnly() } // Android 14+ refuses writable code
            if (!tmp.renameTo(runtimeDir(context))) throw IOException("Can't move the speech engine into place")
        } finally { tmp.deleteRecursively() }
    }

    private suspend fun ensureModel(context: Context, s: Spec, progress: (Float) -> Unit): File {
        val target = dir(context, s)
        if (target.isDirectory) return target
        return run {
            val root = root(context).apply { mkdirs() }
            val zip = File(root, "${s.name}.zip.part")
            val tmp = File(root, "${s.name}.tmp")
            tmp.deleteRecursively()
            try {
                download(s.name, s.url, s.sha256, s.sizeBytes, progress).let { body -> body(zip) }
                zip.inputStream().use { unzipTo(it, tmp) }
                val extracted = File(tmp, s.name)
                if (!extracted.isDirectory) throw IOException("${s.name}: unexpected archive layout")
                if (!extracted.renameTo(target)) throw IOException("${s.name}: can't move the model into place")
                target
            } finally { zip.delete(); tmp.deleteRecursively() }
        }
    }

    private fun download(name: String, url: String, expectedSha: String, size: Long, progress: (Float) -> Unit): suspend (File) -> Unit = { to ->
        Server.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("$name: HTTP ${res.code}")
            val body = res.body
            val total = body.contentLength().takeIf { it > 0 } ?: size
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
            if (hex != expectedSha) throw IOException("$name: checksum mismatch")
        }
    }

    /** Copies the libraries for [abi] out of an AAR, flat into [dir]. Taking only the file name means no entry can land outside it. */
    fun extractNatives(input: InputStream, abi: String, dir: File) {
        dir.mkdirs()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("jni/$abi/") || !e.name.endsWith(".so")) continue
                File(dir, e.name.substringAfterLast('/')).outputStream().use { z.copyTo(it) }
            }
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
