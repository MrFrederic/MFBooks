package app.mfbooks.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer

data class DocEntry(val uri: Uri, val docId: String, val name: String, val mime: String?, val size: Long, val mtime: Long) {
    val isDir get() = mime == Document.MIME_TYPE_DIR
    val ext get() = name.substringAfterLast('.', "").lowercase()
}

private val COLS = arrayOf(
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
)

/** One query per folder (no DocumentFile). null = provider failed (unmounted, permission lost). */
fun Context.children(tree: Uri, docId: String): List<DocEntry>? = try {
    contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), COLS, null, null, null)?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val id = c.getString(0)
                add(DocEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, c.getString(1) ?: "", c.getString(2), c.getLong(3), c.getLong(4)))
            }
        }
    }
} catch (e: Exception) { null }

/** Display name of a document (used for root names). */
fun Context.displayName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
} catch (e: Exception) { null }

fun Context.readBytes(uri: Uri): ByteArray? = try {
    contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (e: Exception) { null }

/** Positional reads over a SAF file descriptor. */
class SafSource(context: Context, uri: Uri) : ByteSource, Closeable {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open $uri")
    private val ch = FileInputStream(pfd.fileDescriptor).channel
    override val size = ch.size()

    override fun read(offset: Long, len: Int): ByteArray {
        val n = minOf(len.toLong(), size - offset).toInt().coerceAtLeast(0)
        val buf = ByteBuffer.allocate(n)
        var pos = offset
        while (buf.hasRemaining()) { val r = ch.read(buf, pos); if (r < 0) break; pos += r }
        return buf.array().copyOf(buf.position())
    }

    override fun close() = pfd.close() // closes the fd the channel wraps
}
