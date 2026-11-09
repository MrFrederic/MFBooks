package app.mfbooks.text

import kotlinx.coroutines.flow.MutableStateFlow

/** Set a book id to open the full player on that book in text view; the player clears it once handled. */
object ReaderNav {
    val openText = MutableStateFlow<String?>(null)
}
