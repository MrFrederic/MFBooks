package app.mfbooks.library

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector

/** UTF-8/UTF-16 (BOM) else juniversalchardet, fallback cp1251. */
fun decodeText(bytes: ByteArray): String {
    fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    if (b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    try {
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
    }
    val det = UniversalDetector(null)
    det.handleData(bytes, 0, bytes.size)
    det.dataEnd()
    val cs = det.detectedCharset?.let { runCatching { Charset.forName(it) }.getOrNull() }
    return String(bytes, cs ?: Charset.forName("windows-1251"))
}

private val NUM_SPLIT = Regex("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")

/** Case-insensitive natural order: "2.mp3" < "10.mp3". */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    val pa = a.split(NUM_SPLIT).filter { it.isNotEmpty() }
    val pb = b.split(NUM_SPLIT).filter { it.isNotEmpty() }
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]
        val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) BigInteger(x).compareTo(BigInteger(y)) else x.compareTo(y, ignoreCase = true)
        if (c != 0) return@Comparator c
    }
    pa.size.compareTo(pb.size)
}

// "EmbeddedCover.jpg" is what Smart AudioBook Player extracts from the audio file: a strong candidate.
private val COVER_NAMES = listOf("cover", "embeddedcover", "folder", "front", "poster")
private val SKIP_IMG = Regex("(^|[^a-z])(back|cd\\d*|disc\\d*|inlay|booklet)([^a-z0-9]|$)")

/**
 * Folder image candidates ranked best-first per §4.3.1 (cover/folder/front/poster → base name = audio/folder → others;
 * skip back/cd/disc/inlay/booklet). Size filtering (< 200 px) and embedded-vs-folder resolution are done by the scanner.
 */
fun rankCoverCandidates(imageNames: List<String>, audioBaseNames: List<String>, folderName: String): List<String> {
    val known = (audioBaseNames + folderName).map { it.trim().lowercase() }.toSet()
    fun rank(name: String): Int {
        val base = baseName(name).trim().lowercase()
        COVER_NAMES.indexOfFirst { Regex("(^|[^a-z])$it([^a-z]|$)").containsMatchIn(base) }.let { if (it >= 0) return it }
        return if (base in known) COVER_NAMES.size else COVER_NAMES.size + 1
    }
    return imageNames
        .filter { n -> extOf(n) in IMAGE_EXTENSIONS && !SKIP_IMG.containsMatchIn(baseName(n).lowercase()) }
        .sortedBy { rank(it) } // stable
}

internal fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
internal fun baseName(name: String) = if (name.contains('.')) name.substringBeforeLast('.') else name

/** "h:mm:ss.fff", "mm:ss.fff", "ss.fff" (also comma decimals) → ms. */
internal fun parseClock(s: String): Long? {
    val parts = s.trim().replace(',', '.').split(':')
    if (parts.size !in 1..3 || parts.any { it.isBlank() }) return null
    var total = 0.0
    for (p in parts) total = total * 60 + (p.trim().toDoubleOrNull() ?: return null)
    return Math.round(total * 1000)
}

internal fun be16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
internal fun be32(b: ByteArray, p: Int): Long =
    ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
internal fun be64(b: ByteArray, p: Int) = (be32(b, p) shl 32) or be32(b, p + 4)
internal fun le32(b: ByteArray, p: Int): Long =
    ((b[p + 3].toLong() and 0xFF) shl 24) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 1].toLong() and 0xFF) shl 8) or (b[p].toLong() and 0xFF)
