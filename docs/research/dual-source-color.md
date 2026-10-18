# Two-source colour scheme (cover + wallpaper)

**Feasible and cheap with existing deps (material-kolor 5.0.1).** An M3 scheme is built from five independent tonal palettes (primary, secondary, tertiary, neutral, neutral-variant); they don't need to share a source. Contrast comes from *tone*, not hue, so mixing sources can't break accessibility (cover primary T40 on system surface T98 ≈ 58 tones apart, >7:1).

## How
- `rememberDynamicColorScheme(seedColor, isDark, isAmoled, primary, secondary, tertiary, neutral, neutralVariant, error, style, contrastLevel, specVersion, platform, modifyColorScheme)` — any non-null palette colour pins that palette (its hue+chroma); the seed drives the rest.
- System palettes (API 31+): `android.R.color.system_accent1/2/3_*`, `system_neutral1/2_*` (500 ≈ tone 50). They follow the user's system style choice (API 34+). No public API for the wallpaper seed itself.

## Options
1. **A (recommended): surfaces from system, accents from cover.** Seed = cover (TonalSpot) → primary + tertiary; `secondary = accent2_500`, `neutral = neutral1_500`, `neutralVariant = neutral2_500`. Backgrounds/cards/nav match the phone; play button, sliders, progress carry the cover hue. Swapped variant (system primary, cover secondary/tertiary) is the same code — shows less cover.
2. **B: harmonize** the cover seed toward the system primary (`Color.harmonize(target)` in `com.materialkolor.ktx`, ≤15° hue shift). Subtle; good as clash control on top of A.
3. **C: blended seed** — rejected, produces a muddy third colour.

## Sketch (Theme.kt)
```kotlin
// ColorSource gains BLENDED
val blended = if (dynamic && colorSource == ColorSource.BLENDED && coverSeed != null)
    rememberDynamicColorScheme(
        seedColor = Color(coverSeed), isDark = dark, style = PaletteStyle.TonalSpot,
        secondary = Color(ctx.getColor(android.R.color.system_accent2_500)),
        neutral = Color(ctx.getColor(android.R.color.system_neutral1_500)),
        neutralVariant = Color(ctx.getColor(android.R.color.system_neutral2_500)),
    ) else null
```
Pitfall: pass the cover as `seedColor`, never raw cover pixels as `primary` (overrides keep their own chroma). Pre-API-31 → current cover scheme.

No Google app was confirmed to blend both sources (system media controls replace wallpaper colours with album-art ones).

Sources: material-kolor / material-color-utilities 5.0.1 jars (javap), [MaterialKolor README](https://github.com/jordond/MaterialKolor), [Android dynamic colors](https://developer.android.com/develop/ui/views/theming/dynamic-colors), [AOSP dynamic color](https://source.android.com/docs/core/display/dynamic-color).
