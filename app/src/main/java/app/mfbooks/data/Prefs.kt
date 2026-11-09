package app.mfbooks.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AutoRewind { OFF, SMALL, MEDIUM, AUTO }
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }
enum class ColorSource { SYSTEM, COVER }
enum class EndOfBook { NOTHING, SHOW_LIBRARY, NEXT_BOOK, NEXT_BOOK_SAME_SETTINGS }
enum class ProgressFormat { TIME, TIME_PERCENT, TIME_LEFT, TIME_PERCENT_LEFT, FILES }
enum class ReaderFont { LITERATA, SERIF, SANS }
enum class ReaderDensity { COMPACT, NORMAL, RELAXED }
enum class HighlightStyle { PLAYHEAD, BAND, WORD, OFF }
enum class MatchWhen { CHARGING, CHARGING_OR_BATTERY, ANY }
enum class SpeechEnginePref { AUTO, ANDROID, VOSK }

/**
 * Next/previous mapping for external surfaces (§5.6). Values are command names from [app.mfbooks.playback.Commands]
 * with an optional seconds argument, e.g. "FWD:30", "REWIND:30", "NEXT_CHAPTER", "PREV_BOOK".
 */
@kotlinx.serialization.Serializable
data class Settings(
    val autoRewind: AutoRewind = AutoRewind.AUTO,
    val defaultSpeed: Float = 1.0f,
    val defaultSkipSilence: Boolean = false,
    val endOfBook: EndOfBook = EndOfBook.NOTHING,
    val pauseOnMessage: Boolean = true,
    val stopWhenOthersPlaying: Boolean = true,
    val resumeAfterCall: Boolean = true,
    val headsetConnectPlay: Boolean = false,
    val forceStopHours: Int = 2,           // 0 = off
    val rewindSmallSec: Int = 15,
    val rewindBigSec: Int = 60,
    val fwdSmallSec: Int = 30,
    val fwdBigSec: Int = 120,
    val nextAction: String = "FWD:30",
    val prevAction: String = "REWIND:30",
    val headsetPresses: List<String> = listOf("PLAY_PAUSE", "FWD_SMALL", "REWIND_SMALL", "ADD_BOOKMARK"),
    val sleepMinutes: Int = 10,            // last chosen duration; -1 = end of chapter
    val sleepFadeSec: Int = 60,
    val shakeForce: Int = 6,               // m/s², one of 1,3,6,12,18
    val trackMotion: String = "ALWAYS",    // ALWAYS / FADE / NEVER
    val chimeVolume: Int = 2,              // 0 off,1 quiet,2 medium,3 loud
    val disableSleepInCar: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.SYSTEM,
    val progressFormat: ProgressFormat = ProgressFormat.TIME_LEFT,
    val showChapters: Boolean = true,
    val useTagTitles: Boolean = true,
    val saveInBookFolders: Boolean = true,
    val showHiddenBooks: Boolean = false,
    val keepScreenOnCharging: Boolean = false,
    val statsEnabled: Boolean = true,
    val libraryView: String = "GRID",      // GRID / LIST / FOLDERS
    val librarySort: String = "TITLE",     // TITLE / AUTHOR / RECENT / ADDED / LENGTH / PATH
    val lockControls: Boolean = false,     // player "lock": only play/pause (and chapter list taps) stay active
    val readerFont: ReaderFont = ReaderFont.LITERATA,
    val readerSizeSp: Int = 19,            // 14..28
    val readerLineSpacing: ReaderDensity = ReaderDensity.NORMAL,
    val readerMargins: ReaderDensity = ReaderDensity.NORMAL,
    val readerJustify: Boolean = false,
    val highlightStyle: HighlightStyle = HighlightStyle.PLAYHEAD,
    val dimNarrated: Boolean = true,
    val followNarration: Boolean = true,
    val keepScreenReading: Boolean = true,
    val tapToSeek: Boolean = true,
    val matchWhen: MatchWhen = MatchWhen.CHARGING,
    val speechEngine: SpeechEnginePref = SpeechEnginePref.AUTO,
    val offerFolderText: Boolean = true,
    val saveTextInFolders: Boolean = true,
)

private val Context.dataStore by preferencesDataStore("settings")

class Prefs(private val context: Context) {
    private object K {
        val autoRewind = stringPreferencesKey("autoRewind")
        val defaultSpeed = floatPreferencesKey("defaultSpeed")
        val defaultSkipSilence = booleanPreferencesKey("defaultSkipSilence")
        val endOfBook = stringPreferencesKey("endOfBook")
        val pauseOnMessage = booleanPreferencesKey("pauseOnMessage")
        val stopWhenOthersPlaying = booleanPreferencesKey("stopWhenOthersPlaying")
        val resumeAfterCall = booleanPreferencesKey("resumeAfterCall")
        val headsetConnectPlay = booleanPreferencesKey("headsetConnectPlay")
        val forceStopHours = intPreferencesKey("forceStopHours")
        val rewindSmallSec = intPreferencesKey("rewindSmallSec")
        val rewindBigSec = intPreferencesKey("rewindBigSec")
        val fwdSmallSec = intPreferencesKey("fwdSmallSec")
        val fwdBigSec = intPreferencesKey("fwdBigSec")
        val nextAction = stringPreferencesKey("nextAction")
        val prevAction = stringPreferencesKey("prevAction")
        val headsetPresses = stringPreferencesKey("headsetPresses")
        val sleepMinutes = intPreferencesKey("sleepMinutes")
        val sleepFadeSec = intPreferencesKey("sleepFadeSec")
        val shakeForce = intPreferencesKey("shakeForce")
        val trackMotion = stringPreferencesKey("trackMotion")
        val chimeVolume = intPreferencesKey("chimeVolume")
        val disableSleepInCar = booleanPreferencesKey("disableSleepInCar")
        val theme = stringPreferencesKey("theme")
        val colorSource = stringPreferencesKey("colorSource")
        val progressFormat = stringPreferencesKey("progressFormat")
        val showChapters = booleanPreferencesKey("showChapters")
        val useTagTitles = booleanPreferencesKey("useTagTitles")
        val saveInBookFolders = booleanPreferencesKey("saveInBookFolders")
        val showHiddenBooks = booleanPreferencesKey("showHiddenBooks")
        val keepScreenOnCharging = booleanPreferencesKey("keepScreenOnCharging")
        val statsEnabled = booleanPreferencesKey("statsEnabled")
        val libraryView = stringPreferencesKey("libraryView")
        val librarySort = stringPreferencesKey("librarySort")
        val lockControls = booleanPreferencesKey("lockControls")
        val readerFont = stringPreferencesKey("readerFont")
        val readerSizeSp = intPreferencesKey("readerSizeSp")
        val readerLineSpacing = stringPreferencesKey("readerLineSpacing")
        val readerMargins = stringPreferencesKey("readerMargins")
        val readerJustify = booleanPreferencesKey("readerJustify")
        val highlightStyle = stringPreferencesKey("highlightStyle")
        val dimNarrated = booleanPreferencesKey("dimNarrated")
        val followNarration = booleanPreferencesKey("followNarration")
        val keepScreenReading = booleanPreferencesKey("keepScreenReading")
        val tapToSeek = booleanPreferencesKey("tapToSeek")
        val matchWhen = stringPreferencesKey("matchWhen")
        val speechEngine = stringPreferencesKey("speechEngine")
        val offerFolderText = booleanPreferencesKey("offerFolderText")
        val saveTextInFolders = booleanPreferencesKey("saveTextInFolders")
        val convertSavedBytes = longPreferencesKey("convertSavedBytes")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }
    suspend fun now(): Settings = settings.first()

    /** `prefs.update { it.copy(theme = ThemeMode.DARK) }` */
    suspend fun update(f: (Settings) -> Settings) = update(true, f)

    /** [sync] = false for values that came from the server, so they don't echo back. */
    suspend fun update(sync: Boolean, f: (Settings) -> Settings) {
        var changed = false
        context.dataStore.edit { p -> val old = p.toSettings(); val new = f(old); changed = new != old; p.write(new) }
        if (changed && sync) app.mfbooks.server.Sync.settingsChanged(context)
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(k: Preferences.Key<String>, d: E): E =
        this[k]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: d

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            autoRewind = enum(K.autoRewind, d.autoRewind),
            defaultSpeed = this[K.defaultSpeed] ?: d.defaultSpeed,
            defaultSkipSilence = this[K.defaultSkipSilence] ?: d.defaultSkipSilence,
            endOfBook = enum(K.endOfBook, d.endOfBook),
            pauseOnMessage = this[K.pauseOnMessage] ?: d.pauseOnMessage,
            stopWhenOthersPlaying = this[K.stopWhenOthersPlaying] ?: d.stopWhenOthersPlaying,
            resumeAfterCall = this[K.resumeAfterCall] ?: d.resumeAfterCall,
            headsetConnectPlay = this[K.headsetConnectPlay] ?: d.headsetConnectPlay,
            forceStopHours = this[K.forceStopHours] ?: d.forceStopHours,
            rewindSmallSec = this[K.rewindSmallSec] ?: d.rewindSmallSec,
            rewindBigSec = this[K.rewindBigSec] ?: d.rewindBigSec,
            fwdSmallSec = this[K.fwdSmallSec] ?: d.fwdSmallSec,
            fwdBigSec = this[K.fwdBigSec] ?: d.fwdBigSec,
            nextAction = this[K.nextAction] ?: d.nextAction,
            prevAction = this[K.prevAction] ?: d.prevAction,
            headsetPresses = this[K.headsetPresses]?.split(',') ?: d.headsetPresses,
            sleepMinutes = this[K.sleepMinutes] ?: d.sleepMinutes,
            sleepFadeSec = this[K.sleepFadeSec] ?: d.sleepFadeSec,
            shakeForce = this[K.shakeForce] ?: d.shakeForce,
            trackMotion = this[K.trackMotion] ?: d.trackMotion,
            chimeVolume = this[K.chimeVolume] ?: d.chimeVolume,
            disableSleepInCar = this[K.disableSleepInCar] ?: d.disableSleepInCar,
            theme = enum(K.theme, d.theme),
            colorSource = enum(K.colorSource, d.colorSource),
            progressFormat = enum(K.progressFormat, d.progressFormat),
            showChapters = this[K.showChapters] ?: d.showChapters,
            useTagTitles = this[K.useTagTitles] ?: d.useTagTitles,
            saveInBookFolders = this[K.saveInBookFolders] ?: d.saveInBookFolders,
            showHiddenBooks = this[K.showHiddenBooks] ?: d.showHiddenBooks,
            keepScreenOnCharging = this[K.keepScreenOnCharging] ?: d.keepScreenOnCharging,
            statsEnabled = this[K.statsEnabled] ?: d.statsEnabled,
            libraryView = this[K.libraryView] ?: d.libraryView,
            librarySort = this[K.librarySort] ?: d.librarySort,
            lockControls = this[K.lockControls] ?: d.lockControls,
            readerFont = enum(K.readerFont, d.readerFont),
            readerSizeSp = this[K.readerSizeSp] ?: d.readerSizeSp,
            readerLineSpacing = enum(K.readerLineSpacing, d.readerLineSpacing),
            readerMargins = enum(K.readerMargins, d.readerMargins),
            readerJustify = this[K.readerJustify] ?: d.readerJustify,
            highlightStyle = enum(K.highlightStyle, d.highlightStyle),
            dimNarrated = this[K.dimNarrated] ?: d.dimNarrated,
            followNarration = this[K.followNarration] ?: d.followNarration,
            keepScreenReading = this[K.keepScreenReading] ?: d.keepScreenReading,
            tapToSeek = this[K.tapToSeek] ?: d.tapToSeek,
            matchWhen = enum(K.matchWhen, d.matchWhen),
            speechEngine = enum(K.speechEngine, d.speechEngine),
            offerFolderText = this[K.offerFolderText] ?: d.offerFolderText,
            saveTextInFolders = this[K.saveTextInFolders] ?: d.saveTextInFolders,
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[K.autoRewind] = s.autoRewind.name
        this[K.defaultSpeed] = s.defaultSpeed
        this[K.defaultSkipSilence] = s.defaultSkipSilence
        this[K.endOfBook] = s.endOfBook.name
        this[K.pauseOnMessage] = s.pauseOnMessage
        this[K.stopWhenOthersPlaying] = s.stopWhenOthersPlaying
        this[K.resumeAfterCall] = s.resumeAfterCall
        this[K.headsetConnectPlay] = s.headsetConnectPlay
        this[K.forceStopHours] = s.forceStopHours
        this[K.rewindSmallSec] = s.rewindSmallSec
        this[K.rewindBigSec] = s.rewindBigSec
        this[K.fwdSmallSec] = s.fwdSmallSec
        this[K.fwdBigSec] = s.fwdBigSec
        this[K.nextAction] = s.nextAction
        this[K.prevAction] = s.prevAction
        this[K.headsetPresses] = s.headsetPresses.joinToString(",")
        this[K.sleepMinutes] = s.sleepMinutes
        this[K.sleepFadeSec] = s.sleepFadeSec
        this[K.shakeForce] = s.shakeForce
        this[K.trackMotion] = s.trackMotion
        this[K.chimeVolume] = s.chimeVolume
        this[K.disableSleepInCar] = s.disableSleepInCar
        this[K.theme] = s.theme.name
        this[K.colorSource] = s.colorSource.name
        this[K.progressFormat] = s.progressFormat.name
        this[K.showChapters] = s.showChapters
        this[K.useTagTitles] = s.useTagTitles
        this[K.saveInBookFolders] = s.saveInBookFolders
        this[K.showHiddenBooks] = s.showHiddenBooks
        this[K.keepScreenOnCharging] = s.keepScreenOnCharging
        this[K.statsEnabled] = s.statsEnabled
        this[K.libraryView] = s.libraryView
        this[K.librarySort] = s.librarySort
        this[K.lockControls] = s.lockControls
        this[K.readerFont] = s.readerFont.name
        this[K.readerSizeSp] = s.readerSizeSp
        this[K.readerLineSpacing] = s.readerLineSpacing.name
        this[K.readerMargins] = s.readerMargins.name
        this[K.readerJustify] = s.readerJustify
        this[K.highlightStyle] = s.highlightStyle.name
        this[K.dimNarrated] = s.dimNarrated
        this[K.followNarration] = s.followNarration
        this[K.keepScreenReading] = s.keepScreenReading
        this[K.tapToSeek] = s.tapToSeek
        this[K.matchWhen] = s.matchWhen.name
        this[K.speechEngine] = s.speechEngine.name
        this[K.offerFolderText] = s.offerFolderText
        this[K.saveTextInFolders] = s.saveTextInFolders
    }
}
