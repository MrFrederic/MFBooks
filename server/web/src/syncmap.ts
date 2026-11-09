// Text position <-> audio time: the runtime half of SyncMap in shared/text/Aligner.kt.

export type SyncKind = 'word' | 'approx' | 'none'
export interface SyncRange { fromChar: number; toChar: number; quality: number; kind?: SyncKind }
export interface SyncDocJson {
  textSha: string; tier: 'approximate' | 'aligned'
  tracks: { name: string; durationMs: number }[]
  chars?: number[]; ms?: number[]; ranges?: SyncRange[]
}

/** Largest k with a[k] <= x, or -1. */
function floor(a: Int32Array, x: number) {
  let lo = 0, hi = a.length - 1, r = -1
  while (lo <= hi) {
    const m = (lo + hi) >> 1
    if (a[m]! <= x) { r = m; lo = m + 1 } else hi = m - 1
  }
  return r
}

function lerp(from: Int32Array, to: Int32Array, x: number) {
  const k = floor(from, x)
  if (k < 0) return to[0]!
  if (k >= from.length - 1) return to[to.length - 1]!
  const span = from[k + 1]! - from[k]!
  return span > 0 ? to[k]! + Math.round(((to[k + 1]! - to[k]!) * (x - from[k]!)) / span) : to[k]!
}

// ponytail: user knots are already part of chars/ms (Approximate.build, Aligner.align and withUserKnot put them there).
export class SyncMap {
  private constructor(private chars: Int32Array, private ms: Int32Array, private ranges: SyncRange[], private fallback: SyncKind) {}

  /** Null when the map isn't for this audio: another track count, or a duration more than 1.5 s off. */
  static of(doc: SyncDocJson, tracks: { durationMs: number }[]): SyncMap | null {
    const n = doc.chars?.length ?? 0
    if (n < 2 || doc.ms?.length !== n || doc.tracks.length !== tracks.length) return null
    if (doc.tracks.some((t, i) => Math.abs(t.durationMs - tracks[i]!.durationMs) > 1500)) return null
    const chars = new Int32Array(n), ms = new Int32Array(n)
    let c = 0, m = 0
    for (let i = 0; i < n; i++) { chars[i] = c += doc.chars![i]!; ms[i] = m += doc.ms![i]! }

    // Rebase per track, so conversion error in one file doesn't push every later file off.
    let t = 0, docStart = 0, curStart = 0
    for (let i = 0; i < n; i++) {
      while (t + 1 < tracks.length && ms[i]! >= docStart + doc.tracks[t]!.durationMs) {
        docStart += doc.tracks[t]!.durationMs
        curStart += tracks[t]!.durationMs
        t++
      }
      ms[i] = curStart + Math.min(ms[i]! - docStart, tracks[t]!.durationMs)
    }
    const fallback: SyncKind = doc.tier === 'approximate' ? 'approx' : 'word'
    return new SyncMap(chars, ms, [...(doc.ranges ?? [])].sort((a, b) => a.fromChar - b.fromChar), fallback)
  }

  private kindAt(char: number): SyncKind {
    const r = this.ranges
    let lo = 0, hi = r.length - 1
    while (lo <= hi) {
      const m = (lo + hi) >> 1
      if (char < r[m]!.fromChar) hi = m - 1
      else if (char >= r[m]!.toChar) lo = m + 1
      else return r[m]!.kind ?? 'word'
    }
    return this.fallback
  }

  /** Text position for book time [ms]. Kind `none` means the narration isn't in the text here. */
  at(ms: number): { char: number; kind: SyncKind } {
    const char = lerp(this.ms, this.chars, ms)
    return { char, kind: this.kindAt(char) }
  }

  /** Book time at which text position [char] is read. */
  msAt(char: number) { return lerp(this.chars, this.ms, char) }
}
