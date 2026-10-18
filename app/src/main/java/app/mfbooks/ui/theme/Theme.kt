package app.mfbooks.ui.theme

import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import app.mfbooks.data.ColorSource
import app.mfbooks.data.ThemeMode

private val BrandSeed = Color(0xFF6B4EFF)

/** Tabular figures so counting clocks don't jitter. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")
fun TextStyle.tabular() = merge(TabularFigures)

@Composable
private fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Animates every role over ~400 ms so switching books cross-fades the app colours. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable fun a(c: Color): Color = animateColorAsState(c, tween(400), label = "scheme").value
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer),
        onPrimaryContainer = a(onPrimaryContainer), inversePrimary = a(inversePrimary),
        secondary = a(secondary), onSecondary = a(onSecondary), secondaryContainer = a(secondaryContainer),
        onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary), onTertiary = a(onTertiary), tertiaryContainer = a(tertiaryContainer),
        onTertiaryContainer = a(onTertiaryContainer),
        background = a(background), onBackground = a(onBackground),
        surface = a(surface), onSurface = a(onSurface), surfaceVariant = a(surfaceVariant),
        onSurfaceVariant = a(onSurfaceVariant), surfaceTint = a(surfaceTint),
        inverseSurface = a(inverseSurface), inverseOnSurface = a(inverseOnSurface),
        outline = a(outline), outlineVariant = a(outlineVariant),
        surfaceBright = a(surfaceBright), surfaceDim = a(surfaceDim),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerHighest = a(surfaceContainerHighest), surfaceContainerLow = a(surfaceContainerLow),
        surfaceContainerLowest = a(surfaceContainerLowest),
    )
}

/**
 * @param coverSeed ARGB seed of the currently playing book, or null.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MFBooksTheme(
    themeMode: ThemeMode,
    colorSource: ColorSource,
    coverSeed: Int?,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val system = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = BrandSeed)
        else -> lightColorScheme(primary = BrandSeed)
    }
    // ponytail: brand scheme without dynamic colour is seeded via kolor too, for a full tonal palette.
    val brand = rememberDynamicColorScheme(BrandSeed, dark, style = PaletteStyle.TonalSpot)
    val base = if (dynamic) system else brand
    val seed = if (colorSource == ColorSource.COVER) coverSeed else null
    val cover = rememberDynamicColorScheme(seed?.let { Color(it) } ?: BrandSeed, dark, style = PaletteStyle.TonalSpot)
    var scheme = (if (seed != null) cover else base).animated()
    if (themeMode == ThemeMode.BLACK) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
    }
    val glow = animateColorAsState(comfortable(seed?.let { Color(it) } ?: scheme.primary, dark), tween(400), label = "glow").value
    CompositionLocalProvider(LocalGlow provides Glow(glow, dark)) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = if (reducedMotion()) MotionScheme.standard() else MotionScheme.expressive(),
            content = content,
        )
    }
}

/** The tint used for backdrops: the cover colour (or the scheme primary) clamped to a comfortable brightness. */
data class Glow(val color: Color, val dark: Boolean)
val LocalGlow = staticCompositionLocalOf { Glow(BrandSeed, true) }

/** Clamp saturation/brightness so a near-white or near-black cover still yields a usable, calm tint. */
fun comfortable(c: Color, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.8f)
    hsv[2] = if (dark) hsv[2].coerceIn(0.3f, 0.6f) else hsv[2].coerceIn(0.55f, 0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Soft cover-coloured wash fading into the background from the top, as behind Home / Stats / Book. */
fun Modifier.glowBackdrop(strength: Float = 1f): Modifier = composed {
    val g = LocalGlow.current
    val a = (if (g.dark) 0.42f else 0.30f) * strength
    background(Brush.verticalGradient(0f to g.color.copy(alpha = a), 0.35f to g.color.copy(alpha = a * 0.35f), 0.7f to Color.Transparent))
}
