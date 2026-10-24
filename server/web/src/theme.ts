// Dynamic colour from the book cover, written into CSS custom properties.
import { Hct, MaterialDynamicColors as C, SchemeNeutral, SchemeTonalSpot, hexFromArgb, sourceColorFromImageBytes, type DynamicColor } from '@material/material-color-utilities'

const FALLBACK = 0xff6d7a8a // muted slate
const ROLES: Record<string, DynamicColor> = {
  primary: C.primary, 'on-primary': C.onPrimary, 'primary-container': C.primaryContainer, 'on-primary-container': C.onPrimaryContainer,
  secondary: C.secondary, 'on-secondary': C.onSecondary, 'secondary-container': C.secondaryContainer, 'on-secondary-container': C.onSecondaryContainer,
  tertiary: C.tertiary, 'tertiary-container': C.tertiaryContainer, 'on-tertiary-container': C.onTertiaryContainer,
  error: C.error, 'on-error': C.onError, 'error-container': C.errorContainer, 'on-error-container': C.onErrorContainer,
  surface: C.surface, 'surface-dim': C.surfaceDim, 'surface-bright': C.surfaceBright,
  'surface-lowest': C.surfaceContainerLowest, 'surface-low': C.surfaceContainerLow, 'surface-container': C.surfaceContainer,
  'surface-high': C.surfaceContainerHigh, 'surface-highest': C.surfaceContainerHighest,
  'on-surface': C.onSurface, 'on-surface-variant': C.onSurfaceVariant, outline: C.outline, 'outline-variant': C.outlineVariant,
  'inverse-surface': C.inverseSurface, 'inverse-on-surface': C.inverseOnSurface, 'inverse-primary': C.inversePrimary, scrim: C.scrim,
}

const light = matchMedia('(prefers-color-scheme: light)')
let seed: number | null = null

function apply() {
  const dark = !light.matches
  const hct = Hct.fromInt(seed ?? FALLBACK)
  const s = seed == null ? new SchemeNeutral(hct, dark, 0, '2025') : new SchemeTonalSpot(hct, dark, 0, '2025')
  const st = document.documentElement.style
  for (const [k, c] of Object.entries(ROLES)) st.setProperty(`--${k}`, hexFromArgb(c.getArgb(s)))
  st.colorScheme = dark ? 'dark' : 'light'
}
light.addEventListener('change', apply)
apply()

export function setSeed(argb: number | null) {
  const v = argb == null ? null : argb >>> 0 // Android colour ints are signed
  if (v === seed) return
  seed = v
  apply()
}

const coverSeeds = new Map<string, Promise<number | null>>()
/** Seed colour quantized from a (same-origin) cover, cached per URL. */
export function seedFromCover(url: string): Promise<number | null> {
  let p = coverSeeds.get(url)
  if (!p) {
    p = new Promise((resolve) => {
      const img = new Image()
      img.onload = () => {
        try {
          const n = 96, cv = document.createElement('canvas')
          cv.width = cv.height = n
          const g = cv.getContext('2d', { willReadFrequently: true })!
          g.drawImage(img, 0, 0, n, n)
          resolve(sourceColorFromImageBytes(g.getImageData(0, 0, n, n).data))
        } catch { resolve(null) }
      }
      img.onerror = () => resolve(null)
      img.src = url
    })
    coverSeeds.set(url, p)
  }
  return p
}
