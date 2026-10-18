package app.mfbooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.mfbooks.ui.theme.LocalGlow
import app.mfbooks.ui.theme.comfortable
import coil3.compose.AsyncImage

/**
 * Haze behind the book page header and the full player: a heavily blurred copy of the cover under a cover-tinted
 * wash that fades into the background at the bottom. Size it with [modifier].
 * ponytail: blur is a no-op below API 31; the tint carries it there.
 */
@Composable
fun CoverBackdrop(coverUri: String?, seedColor: Int?, modifier: Modifier = Modifier) {
    val glow = LocalGlow.current
    val tint = seedColor?.let { comfortable(Color(it), glow.dark) } ?: glow.color
    Box(modifier) {
        if (coverUri != null) AsyncImage(coverUri, null, Modifier.fillMaxSize().blur(60.dp).alpha(if (glow.dark) 0.45f else 0.35f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to tint.copy(alpha = 0.45f), 0.6f to tint.copy(alpha = 0.15f), 1f to MaterialTheme.colorScheme.background)))
    }
}
