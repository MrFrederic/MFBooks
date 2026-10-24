package app.mfbooks.convert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mfbooks.data.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Conversion state in its own DataStore file. ponytail: Prefs' `convertSavedBytes` key lives behind a private
 * DataStore delegate, and a second delegate on "settings" would crash ("multiple DataStores active"), so the
 * running total lives here under the same key name.
 */
private val Context.convertStore by preferencesDataStore("convert")

class ConvertStore(private val context: Context) {
    private object K {
        val savedBytes = longPreferencesKey("convertSavedBytes")
        val convertedFiles = longPreferencesKey("convertedFiles")
        val battery = intPreferencesKey("batteryThreshold")
        val queue = stringSetPreferencesKey("queueBookIds")
    }

    private val data get() = context.convertStore.data
    val savedBytes: Flow<Long> = data.map { it[K.savedBytes] ?: 0L }
    /** Total files ever converted (not reset with the saved total): Settings can lock "Save progress in book folders" while > 0. */
    val convertedFiles: Flow<Long> = data.map { it[K.convertedFiles] ?: 0L }
    val batteryThreshold: Flow<Int> = data.map { it[K.battery] ?: 80 }
    val queue: Flow<Set<String>> = data.map { it[K.queue] ?: emptySet() }

    suspend fun addSaved(bytes: Long) = context.convertStore.edit {
        it[K.savedBytes] = (it[K.savedBytes] ?: 0L) + bytes
        it[K.convertedFiles] = (it[K.convertedFiles] ?: 0L) + 1
    }
    suspend fun resetSaved() = context.convertStore.edit { it[K.savedBytes] = 0L }
    suspend fun setBatteryThreshold(v: Int) = context.convertStore.edit { it[K.battery] = v }
    suspend fun setQueue(ids: Set<String>) = context.convertStore.edit { it[K.queue] = ids }
    suspend fun queueNow() = queue.first()
    suspend fun thresholdNow() = batteryThreshold.first()
}

fun Eligibility.isEligible(t: Track) = isEligible(t.fileName, t.sizeBytes, t.durationMs)

fun Eligibility.estimatedSaving(t: Track) = (t.sizeBytes - estimatedOutBytes(t.durationMs)).coerceAtLeast(0)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f kB".format(b / 1024.0)
    else -> "$b B"
}
