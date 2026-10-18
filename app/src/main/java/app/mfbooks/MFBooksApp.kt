package app.mfbooks

import android.app.Application
import androidx.room.Room
import app.mfbooks.data.AppDatabase
import app.mfbooks.data.Prefs
import app.mfbooks.library.LibraryScanner
import app.mfbooks.playback.PlayerConnection

/** Manual singletons (no DI). Access with `MFBooksApp.get(context)` or `context.applicationContext as MFBooksApp`. */
class MFBooksApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "mfbooks.db").fallbackToDestructiveMigration(true).build() }
    val prefs by lazy { Prefs(this) }
    val scanner by lazy { LibraryScanner(this) }
    val player by lazy { PlayerConnection(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        scanner.enqueue(full = false)
    }

    companion object {
        lateinit var instance: MFBooksApp private set
        fun get(context: android.content.Context) = context.applicationContext as MFBooksApp
    }
}
