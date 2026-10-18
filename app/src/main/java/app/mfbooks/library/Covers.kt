package app.mfbooks.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Cover resolution of §4.3.1 / §4.4: bigger of folder image and embedded art (tie → folder), cached as JPEG ≤ 1024 px. */
object Covers {
    private class Img(val bytes: ByteArray, val w: Int, val h: Int)

    private fun img(bytes: ByteArray?): Img? {
        bytes ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) Img(bytes, o.outWidth, o.outHeight) else null
    }

    private fun decode(img: Img, maxDim: Int): Bitmap? {
        var ss = 1
        while (max(img.w, img.h) / (ss * 2) >= maxDim) ss *= 2
        val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return null
        val big = max(bmp.width, bmp.height)
        return if (big <= maxDim) bmp else Bitmap.createScaledBitmap(bmp, bmp.width * maxDim / big, bmp.height * maxDim / big, true)
    }

    fun file(context: Context, bookId: String) = File(File(context.filesDir, "covers"), "$bookId.jpg")

    /** [folderImages] ranked best-first; [fallback] (SABP statistics cover) only when nothing else exists. Returns (coverUri, seedColor); both null when there is no usable art. */
    fun resolve(context: Context, bookId: String, folderImages: List<DocEntry>, embedded: ByteArray?, fallback: DocEntry? = null): Pair<String?, Int?> {
        val emb = img(embedded)
        val folder = folderImages.asSequence().take(6).mapNotNull { img(context.readBytes(it.uri)) }.firstOrNull { min(it.w, it.h) >= 200 }
        val pick = (if (folder != null && (emb == null || folder.w.toLong() * folder.h >= emb.w.toLong() * emb.h)) folder else emb)
            ?: fallback?.let { img(context.readBytes(it.uri)) }
        val out = file(context, bookId).also { it.parentFile?.mkdirs() }
        if (pick == null) { out.delete(); return null to null }
        try {
            val isJpeg = pick.bytes.size > 2 && pick.bytes[0] == 0xFF.toByte() && pick.bytes[1] == 0xD8.toByte()
            if (isJpeg && max(pick.w, pick.h) <= 1024) out.writeBytes(pick.bytes)
            else decode(pick, 1024)?.let { b -> out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } } ?: return null to null
        } catch (e: Exception) { return null to null }
        return Uri.fromFile(out).toString() to seedColor(pick)
    }

    private fun seedColor(img: Img): Int? {
        val b = decode(img, 128) ?: return null
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var r = 0L; var g = 0L; var bl = 0L
        for (p in px) { r += p shr 16 and 255; g += p shr 8 and 255; bl += p and 255 }
        val n = px.size.coerceAtLeast(1)
        val avg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        return runCatching { Score.score(QuantizerCelebi.quantize(px, 128), 1, avg, true).first() }.getOrDefault(avg)
    }
}
