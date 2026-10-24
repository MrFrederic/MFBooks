package app.mfbooks

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mfbooks.ui.MFBooksRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { MFBooksRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); app.mfbooks.server.Live.setForeground(true); app.mfbooks.server.Sync.request(this) }
    override fun onStop() { app.mfbooks.server.Live.setForeground(false); super.onStop() }

    /** Phone voice: "play <title> on MFBooks"; `mfbooks://pair?server=…&code=…` from the web player's QR code (C1). */
    private fun handleIntent(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "mfbooks" && it.host == "pair" }?.let { u ->
            val server = u.getQueryParameter("server"); val code = u.getQueryParameter("code")
            if (server != null && code != null) app.mfbooks.server.Server.pendingPair.value = server to code
        }
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            MFBooksApp.get(this).player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
    }
}
