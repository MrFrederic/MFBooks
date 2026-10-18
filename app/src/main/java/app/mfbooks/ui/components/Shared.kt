package app.mfbooks.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

/** Returns a launcher for the SAF tree picker that registers the chosen folder with the scanner. */
@Composable
fun rememberAddFolder(): () -> Unit {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch { app(ctx).scanner.addRoot(uri)?.let { snackbar.showSnackbar(it) } }
    }
    return { picker.launch(null) }
}
