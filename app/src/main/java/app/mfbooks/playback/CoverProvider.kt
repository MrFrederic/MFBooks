package app.mfbooks.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.mfbooks.MFBooksApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/** Read-only: content://app.mfbooks.covers/<bookId> → cached JPEG ≤ 512 px (§6.4). */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException()
        val id = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: throw FileNotFoundException(uri.toString())
        val book = runBlocking { MFBooksApp.get(ctx).db.library().book(id) } ?: throw FileNotFoundException(id)
        val src = book.coverUri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.isFile } ?: throw FileNotFoundException(id)
        val dir = File(ctx.cacheDir, "covers512").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        if (!out.isFile || out.lastModified() < src.lastModified()) synchronized(this) { scale(src, out) }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun scale(src: File, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw FileNotFoundException(src.path)
        val k = MAX.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(out)
    }

    private companion object { const val MAX = 512 }
}
