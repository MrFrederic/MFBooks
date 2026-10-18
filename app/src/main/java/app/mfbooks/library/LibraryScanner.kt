package app.mfbooks.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.mfbooks.MFBooksApp
import app.mfbooks.data.RootFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** null = idle. */
data class ScanProgress(val done: Int, val total: Int, val current: String? = null)

/**
 * Owned by the scanner agent. Public API is the contract the UI/playback code against.
 */
class LibraryScanner(private val context: Context) {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    /** Root-level warnings, e.g. "Audiobooks: 3 audio files outside folders — move them into a folder". */
    val warnings: StateFlow<List<String>> = _warnings

    private val app get() = MFBooksApp.get(context)
    private val dao get() = app.db.library()

    /** Unique WorkManager job. */
    fun enqueue(full: Boolean) {
        val req = OneTimeWorkRequestBuilder<ScanWorker>().setInputData(workDataOf("full" to full)).build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("library-scan", if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Validates (not whole storage, not nested, unique name), takes persistable permission, stores RootFolder, scans. Returns error text or null. */
    suspend fun addRoot(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        val id = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return@withContext "Not a folder" }
        if (id.endsWith(":") || id.isEmpty()) return@withContext "Pick a folder, not the whole storage"
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        val name = context.displayName(docUri) ?: id.substringAfterLast('/').substringAfterLast(':')
        for (r in dao.rootsNow()) {
            val o = Uri.parse(r.uri)
            val oid = DocumentsContract.getTreeDocumentId(o)
            if (o.authority == treeUri.authority) {
                if (oid == id) return@withContext "This folder is already added"
                if (id.startsWith("$oid/")) return@withContext "Inside the already added folder \"${r.name}\""
                if (oid.startsWith("$id/")) return@withContext "Contains the already added folder \"${r.name}\""
            }
            if (r.name.equals(name, true)) return@withContext "A folder named \"$name\" is already added"
        }
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            try { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (e: SecurityException) { return@withContext "No permission for this folder" }
        }
        dao.upsertRoot(RootFolder(treeUri.toString(), name, System.currentTimeMillis()))
        enqueue(false)
        null
    }

    suspend fun removeRoot(treeUri: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(treeUri)
        for (flags in intArrayOf(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try { context.contentResolver.releasePersistableUriPermission(uri, flags); break } catch (e: SecurityException) { }
        }
        dao.booksInRoot(treeUri).forEach { dao.removeBook(it.id); Covers.file(context, it.id).delete() }
        dao.rootsNow().firstOrNull { it.uri == treeUri }?.let { dao.deleteRoot(it) }
    }

    /** Called by [ScanWorker]. Full = ignore signatures and sidecar caches. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(full: Boolean) = coroutineScope {
        val roots = dao.rootsNow()
        val tt = app.prefs.now().useTagTitles
        val saveSidecar = app.prefs.now().saveInBookFolders
        val cands = ArrayList<Candidate>(); val warns = ArrayList<String>(); val okRoots = HashSet<String>()
        val sabpStats = HashMap<String, List<DocEntry>>() // root uri → SABP statistics folder listing
        val t0 = System.currentTimeMillis()
        _progress.value = ScanProgress(0, 0)
        try {
            withContext(Dispatchers.IO) { for (r in roots) if (discover(r, cands, warns, sabpStats)) okRoots += r.uri }
            _warnings.value = warns

            // Drop books whose folders vanished (only for roots we could actually read).
            val ids = cands.mapTo(HashSet()) { it.id }
            val old = HashMap<String, app.mfbooks.data.Book>()
            for (r in roots) for (b in dao.booksInRoot(r.uri)) {
                if (b.id !in ids && r.uri in okRoots) { dao.removeBook(b.id); Covers.file(context, b.id).delete() } else old[b.id] = b
            }

            val tDiscovered = System.currentTimeMillis()
            val total = cands.size; val done = AtomicInteger()
            _progress.value = ScanProgress(0, total)
            val io = Dispatchers.IO.limitedParallelism(4)
            cands.map { c ->
                async(io) {
                    val fallback = sabpStats[c.root.uri]?.firstOrNull { it.name.equals("${c.name}.jpg", true) }
                    try { process(c, old[c.id], full, tt, saveSidecar, fallback) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.w("Scanner", "scan failed: ${c.relPath}", e) }
                    _progress.update { ScanProgress(done.incrementAndGet(), total, c.name) }
                }
            }.awaitAll()
            val t1 = System.currentTimeMillis()
            for ((rootUri, dir) in sabpStats) try {
                val books = dao.booksInRoot(rootUri).associate { it.relativePath to (it.id to it.title) }
                Sabp.importStatistics(context, app.db.userData(), dir, books)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("Scanner", "SABP statistics import failed", e) }
            Log.i("Scanner", "scan full=$full: ${cands.size} books, discover ${tDiscovered - t0} ms, analyze ${t1 - tDiscovered} ms, total ${System.currentTimeMillis() - t0} ms")
        } finally { _progress.value = null }
    }

    private suspend fun process(c: Candidate, old: app.mfbooks.data.Book?, full: Boolean, tt: Boolean, saveSidecar: Boolean, fallbackCover: DocEntry?) {
        val sidecar = c.entries.firstOrNull { it.name == Sidecar.NAME }
        if (old != null && !full && old.scanSignature == c.signature(tt)) {
            Sidecar.importIfNewer(context, dao, c.id, c.entries, null)
            Sabp.importBook(context, dao, app.db.userData(), c.id, c.entries)
            return
        }
        val sc = sidecar?.let { Sidecar.read(context, it) }
        val t = System.currentTimeMillis()
        val s = analyze(context, c, old, sc, useCache = !full, tt, fallbackCover)
        Log.d("Scanner", "analyzed ${c.relPath} in ${System.currentTimeMillis() - t} ms")
        dao.replaceBook(s.book, s.tracks, s.chapters)
        Sidecar.importIfNewer(context, dao, c.id, c.entries, sc)
        Sabp.importBook(context, dao, app.db.userData(), c.id, c.entries)
        if (saveSidecar) try { Sidecar.writeScanned(context, c, s, sc, dao) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* read-only tree */ }
    }

    /** Walks one root, one child query per folder. false = root unreadable (so its books must not be deleted). */
    private fun discover(root: RootFolder, out: MutableList<Candidate>, warns: MutableList<String>, sabpStats: MutableMap<String, List<DocEntry>>): Boolean {
        val tree = Uri.parse(root.uri)
        var loose = 0
        fun walk(docId: String, rel: List<String>): Boolean {
            val kids = context.children(tree, docId) ?: return false
            if (kids.any { it.name == ".nomedia" }) return true
            val files = kids.filter { !it.isDir }
            val audio = files.count { it.ext in AUDIO_EXTENSIONS }
            if (audio > 0) {
                if (rel.isEmpty()) loose += audio
                else out += Candidate(root, tree, docId, rel.joinToString("/"), rel.last(), rel.getOrNull(rel.size - 2), files)
            }
            for (d in kids) {
                if (!d.isDir || d.name.startsWith(".")) continue
                // SABP's statistics folder holds covers + statistics.xml, never a book.
                if (d.name == SABP_STATS_DIR) { if (rel.isEmpty()) context.children(tree, d.docId)?.let { sabpStats[root.uri] = it }; continue }
                walk(d.docId, rel + d.name)
            }
            return true
        }
        val ok = walk(DocumentsContract.getTreeDocumentId(tree), emptyList())
        if (loose > 0) warns += "${root.name}: $loose audio files outside folders — move them into a folder"
        return ok
    }
}
