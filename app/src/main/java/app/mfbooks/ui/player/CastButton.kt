package app.mfbooks.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import app.mfbooks.playback.CastSupport

/**
 * Cast route picker (Media3's Compose MediaRouteButton: chooser/controller dialogs included). Renders nothing when
 * Cast can't work on this device (no Google Play services). Picking a device hands playback to PlaybackService's
 * CastPlayer, which moves the book to the receiver at the current position.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val available = remember { CastSupport.init(context) }
    if (available) MediaRouteButton(modifier)
}
