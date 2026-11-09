package app.mfbooks.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mfbooks.MFBooksApp
import app.mfbooks.data.SpeechEnginePref
import app.mfbooks.data.Settings
import app.mfbooks.text.Matching

/**
 * Suggests Vosk wherever matching can start. Hidden when it's already downloaded, the language has no Vosk model, or the
 * user chose the Android engine. [matchBook] restarts that book's matching once the download ends.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoskOffer(lang: String, modifier: Modifier = Modifier, matchBook: String? = null, matchNow: Boolean = true) {
    val ctx = LocalContext.current
    val settings by MFBooksApp.get(ctx).prefs.settings.collectAsState(Settings())
    val version by Matching.modelsVersion.collectAsState()
    val downloads by Matching.downloads.collectAsState()
    val failed by Matching.downloadFailed.collectAsState()
    val bytes = remember(version, lang) { Matching.voskDownloadBytes(ctx, lang) }
    if (bytes == 0L || settings.speechEngine == SpeechEnginePref.ANDROID) return
    val progress = downloads[lang]
    val cs = MaterialTheme.colorScheme
    val builtIn = remember { Matching.androidEngineAvailable(ctx) }
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(cs.surfaceContainerHighest).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (builtIn) "Get better matching with Vosk" else "Download Vosk to match this book", style = MaterialTheme.typography.titleSmall)
        Text(
            if (builtIn) "Vosk matches books faster and finds more of the text than Android's built-in speech recognition. It runs entirely on your phone."
            else "This phone has no speech recognition MFBooks can use. Vosk runs entirely on your phone.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        if (progress != null) {
            Text("Downloading, ${(progress * 100).toInt()}%", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
        } else {
            if (lang in failed) Text("Download failed", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = cs.error)
            FilledTonalButton({ Matching.downloadVosk(ctx, lang, matchBook, matchNow) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)) {
                Text(if (lang in failed) "Try again" else "Download, ${megabytes(bytes)}")
            }
        }
    }
}
