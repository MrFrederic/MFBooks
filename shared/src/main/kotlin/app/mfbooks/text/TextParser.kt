package app.mfbooks.text

object TextParser {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_UNZIPPED_BYTES = 256L * 1024 * 1024

    /** [fileName] picks the format: `.fb2`, `.fb2.zip`, `.epub`, `.txt`. Throws [TextParseException]. */
    fun parse(fileName: String, bytes: ByteArray): BookTextDoc = TODO("shared agent")

    /** BCP 47 base language from the alphabet: "ru" or "en". */
    fun detectLanguage(text: String): String = TODO("shared agent")
}
