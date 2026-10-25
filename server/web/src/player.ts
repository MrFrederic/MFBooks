// Player engine (W2, W3): one <audio> through a Web Audio EQ, plus the remote-control facade (W5).
import { computed, effect, signal } from '@preact/signals'
import { type BookDetail, coverUrl, details, fetchDetail, fileUrl, me, patchBook, playable, toast, trackStarts, uuid } from './api'
import { appSettings, bookSettingsOf, flushOnUnload, hasQueued, onChanges, push, seqOf, serverNow } from './sync'
import { cmd, devices, hooks, liveId, type CmdAction, type LiveDevice, type PlayState, send } from './live'

const audio = new Audio()
audio.preload = 'metadata'
audio.preservesPitch = true

function stored<T>(k: string, def: T): T {
  try { return JSON.parse(localStorage.getItem(k) ?? 'null') ?? def } catch { return def }
}
function store(k: string, v: unknown) { try { localStorage.setItem(k, JSON.stringify(v)) } catch {} }

// ---- Local playback state ----

export const book = signal<BookDetail | null>(null)
export const track = signal(0)
/** Position in the current track, ms. */
export const pos = signal(0)
export const playing = signal(false)
export const buffering = signal(false)
export const speed = signal(1)
export const volume = signal<number>(stored('mfb.volume', 1))
export const eq = signal<number[]>([0, 0, 0, 0, 0])
export const boostMb = signal(0)
/** `at` = wall-clock end; `chapterEnd` = book position where "end of chapter" stops. */
export const sleep = signal<null | { at: number } | { chapterEnd: number }>(null)
export const tick = signal(Date.now())

export const skipBackSec = computed<number>(() => appSettings.value.rewindSmallSec ?? 15)
export const skipFwdSec = computed<number>(() => appSettings.value.fwdSmallSec ?? 30)

// ---- Chapters ----

export interface Ch { title: string; trackIndex: number; startInTrackMs: number; start: number; dur: number }
const chCache = new WeakMap<BookDetail, Ch[]>()
const startCache = new WeakMap<BookDetail, number[]>()
export function starts(d: BookDetail) {
  let s = startCache.get(d)
  if (!s) startCache.set(d, (s = trackStarts(d)))
  return s
}
/** The book's chapters, or one per track when it has none. */
export function chaptersOf(d: BookDetail): Ch[] {
  let c = chCache.get(d)
  if (c) return c
  const st = starts(d)
  c = d.chapters.length
    ? d.chapters.map((x) => ({ title: x.title, trackIndex: x.trackIndex, startInTrackMs: x.startInTrackMs, start: x.startInBookMs ?? (st[x.trackIndex] ?? 0) + x.startInTrackMs, dur: x.durationMs }))
    : d.tracks.map((t, i) => ({ title: t.title || t.fileName || `Track ${i + 1}`, trackIndex: i, startInTrackMs: 0, start: st[i]!, dur: t.durationMs }))
  chCache.set(d, c)
  return c
}
export function chapterAt(chs: Ch[], bookMs: number) {
  let i = 0
  while (i + 1 < chs.length && chs[i + 1]!.start <= bookMs) i++
  return i
}
function locate(d: BookDetail, bookMs: number) {
  const st = starts(d)
  let i = 0
  while (i + 1 < st.length && st[i + 1]! <= bookMs) i++
  return { trackIndex: i, positionMs: Math.max(0, bookMs - (st[i] ?? 0)) }
}
const bookMsOf = (d: BookDetail, trackIndex: number, ms: number) => (starts(d)[trackIndex] ?? 0) + ms

// ---- Unified view: this browser, or the remote device that is active (W5) ----

export const myId = () => me.value?.deviceId ?? liveId.value
export const remote = computed<LiveDevice | null>(() => devices.value.find((d) => d.active && d.online && d.id !== myId()) ?? null)

export function remotePos(s: PlayState) {
  return s.playing ? s.positionMs + (serverNow() - s.at) * s.speed : s.positionMs
}

export interface View {
  book: BookDetail | null; title: string | null; trackIndex: number; bookMs: number
  playing: boolean; speed: number; sleepAt: number | null; remote: LiveDevice | null
}
export const view = computed<View>(() => {
  const r = remote.value
  if (r) {
    tick.value // re-extrapolate the remote position as time passes
    const s = r.state
    const d = (s && details.value[s.bookId]) || null
    const p = s ? remotePos(s) : 0
    return {
      book: d, title: d?.title ?? s?.title ?? null, trackIndex: s?.trackIndex ?? 0,
      bookMs: d && s ? Math.min(bookMsOf(d, s.trackIndex, p), d.durationMs) : 0,
      playing: !!s?.playing, speed: s?.speed ?? 1, sleepAt: s?.sleepAt ?? null, remote: r,
    }
  }
  const d = book.value
  return {
    book: d, title: d?.title ?? null, trackIndex: track.value, bookMs: d ? bookMsOf(d, track.value, pos.value) : 0,
    playing: playing.value, speed: speed.value, sleepAt: sleepAtServer(), remote: null,
  }
})
export const nowPlayingId = computed(() => view.value.book?.id ?? view.value.remote?.state?.bookId ?? null)

// ---- Web Audio graph: source → 5 biquads → boost gain → out ----

let ctx: AudioContext | null = null
let filters: BiquadFilterNode[] = []
let boostNode: GainNode | null = null
const BANDS: [BiquadFilterType, number][] = [['lowshelf', 60], ['peaking', 230], ['peaking', 910], ['peaking', 4000], ['highshelf', 14000]]

const fxOn = () => eq.value.some((b) => b !== 0) || boostMb.value > 0
/** A click or key press is in progress (browsers only start audio contexts then). */
const inGesture = () => navigator.userActivation?.isActive ?? false

/**
 * The EQ and boost need Web Audio, and a browser starts an AudioContext only inside a click or key press. An element
 * routed into a context that never started plays silence while looking like it plays, so the graph is built only when an
 * effect is set and a gesture allows it; until then the <audio> element plays directly, unprocessed.
 */
function ensureGraph() {
  if (ctx) { if (ctx.state !== 'running' && inGesture()) ctx.resume().catch(() => {}); return }
  if (!fxOn() || !inGesture()) return
  const c = (ctx = new AudioContext())
  const src = c.createMediaElementSource(audio)
  filters = BANDS.map(([type, f]) => {
    const n = c.createBiquadFilter()
    n.type = type
    n.frequency.value = f
    n.Q.value = 1
    return n
  })
  boostNode = c.createGain()
  const chain: AudioNode[] = [src, ...filters, boostNode, c.destination]
  chain.reduce((a, b) => (a.connect(b), b))
  applyFx()
}
document.addEventListener('pointerdown', ensureGraph, { capture: true })
document.addEventListener('keydown', ensureGraph, { capture: true })

function applyFx() {
  ensureGraph()
  filters.forEach((f, i) => (f.gain.value = eq.value[i] ?? 0))
  if (boostNode) boostNode.gain.value = Math.pow(10, boostMb.value / 2000)
}

/** Playing but the context can't run (started without a click, or suspended by the system): say how to get sound. */
let warnedSilent = false
async function checkSound() {
  if (!ctx || ctx.state === 'running') return
  await ctx.resume().catch(() => {})
  if ((ctx.state as AudioContextState) !== 'running' && !audio.paused && !warnedSilent) { // resume() may have changed it
    warnedSilent = true
    toast('Click anywhere on the page to turn the sound on')
  }
}

// ---- Engine ----

let loadedKey = ''
let pendingSeek: number | null = null
let fade = 1

const curPos = () => pendingSeek ?? audio.currentTime * 1000

function setTrack(i: number, at: number) {
  const d = book.value!
  const key = `${d.id}/${i}`
  track.value = i
  pos.value = at
  if (key !== loadedKey) {
    const resume = playing.value
    loadedKey = key
    pendingSeek = at
    audio.src = fileUrl(d.id, i)
    audio.defaultPlaybackRate = audio.playbackRate = speed.value
    if (resume) audio.play().catch(() => {})
  } else if (pendingSeek != null) pendingSeek = at
  else audio.currentTime = at / 1000
}

audio.addEventListener('loadedmetadata', () => {
  if (pendingSeek != null) audio.currentTime = pendingSeek / 1000
  pendingSeek = null
  audio.playbackRate = speed.value
})
audio.addEventListener('play', () => {
  playing.value = true
  beginStretch()
  markStarted()
  sendState()
})
audio.addEventListener('pause', () => {
  if (audio.ended && book.value && track.value + 1 < book.value.tracks.length) return // moving to the next track
  playing.value = false
  endStretch()
  pushProgress()
  sendState()
})
audio.addEventListener('ended', () => {
  const d = book.value
  if (d && track.value + 1 < d.tracks.length) {
    setTrack(track.value + 1, 0) // still `playing`, so this starts the next file
  }
})
audio.addEventListener('waiting', () => (buffering.value = true))
audio.addEventListener('playing', () => (buffering.value = false))
audio.addEventListener('ratechange', () => sendState())
audio.addEventListener('error', () => {
  if (!audio.src) return
  buffering.value = false
  playing.value = false
  toast("Couldn't play this file")
})

export async function play() {
  if (!book.value) return
  ensureGraph()
  playing.value = true
  try {
    await audio.play()
    await checkSound()
  } catch (e: any) {
    playing.value = !audio.paused
    if (e?.name === 'NotAllowedError') throw new Error('The browser blocked playback: click the page once, then retry')
    if (e?.name !== 'AbortError') throw e
  }
}
export function pause() {
  playing.value = false
  audio.pause()
}

export function seekTrack(i: number, ms: number) {
  if (!book.value) return
  setTrack(i, Math.max(0, ms))
  pushProgress()
  sendState()
}
export function seekBook(ms: number) {
  const d = book.value
  if (!d) return
  const { trackIndex, positionMs } = locate(d, Math.min(Math.max(0, ms), d.durationMs - 500))
  seekTrack(trackIndex, positionMs)
}
const localBookMs = () => (book.value ? bookMsOf(book.value, track.value, curPos()) : 0)

export function setSpeed(v: number, save = true) {
  v = Math.round(Math.min(3, Math.max(0.5, v)) * 20) / 20
  speed.value = v
  audio.defaultPlaybackRate = audio.playbackRate = v
  if (save && book.value) push({ kind: 'book_settings', id: book.value.id, data: { speed: v } })
}
/** minutes: null = off, -1 = end of chapter. */
export function setSleep(minutes: number | null) {
  if (minutes == null) sleep.value = null
  else if (minutes === -1) {
    const d = book.value
    if (!d) return
    const chs = chaptersOf(d), c = chs[chapterAt(chs, localBookMs())]
    sleep.value = c ? { chapterEnd: c.start + c.dur } : null
  } else sleep.value = { at: Date.now() + minutes * 60_000 }
  fade = 1
  applyVolume()
  sendState()
}
function sleepRemaining(): number | null {
  const s = sleep.value
  if (!s) return null
  return 'at' in s ? s.at - Date.now() : (s.chapterEnd - localBookMs()) / speed.value
}
function sleepAtServer() {
  sleep.value
  const r = sleepRemaining()
  return r == null ? null : serverNow() + r
}
export function setEq(bands: number[], save: boolean) {
  eq.value = bands
  applyFx()
  if (save && book.value) push({ kind: 'book_settings', id: book.value.id, data: { eqBandsDb: bands.join(',') } })
}
export function setBoost(mb: number, save: boolean) {
  boostMb.value = mb
  applyFx()
  if (save && book.value) push({ kind: 'book_settings', id: book.value.id, data: { boostMb: mb } })
}
function applyVolume() { audio.volume = Math.max(0, Math.min(1, volume.value * fade)) }
let volumeReport = 0
effect(() => {
  applyVolume()
  store('mfb.volume', volume.value)
  clearTimeout(volumeReport)
  volumeReport = setTimeout(() => { if (book.value) sendState() }, 300) // so a remote's Connect slider follows
})

function applyBookSettings(d: BookDetail) {
  const s = { ...d.settings, ...bookSettingsOf(d.id) }
  setSpeed(s.speed ?? 1, false)
  const bands = String(s.eqBandsDb ?? '').split(',').map(Number)
  eq.value = [0, 1, 2, 3, 4].map((i) => (Number.isFinite(bands[i]) ? bands[i]! : 0))
  boostMb.value = s.boostMb ?? 0
  applyFx()
}

function resumePoint(d: BookDetail) {
  const p = (hasQueued('progress', d.id)?.data as BookDetail['progress']) ?? d.progress
  if (!p) return { trackIndex: 0, positionMs: 0 }
  const r = { trackIndex: Math.min(p.trackIndex, d.tracks.length - 1), positionMs: p.positionInTrackMs }
  if (d.state === 'FINISHED' && bookMsOf(d, r.trackIndex, r.positionMs) >= d.durationMs - 30_000) return { trackIndex: 0, positionMs: 0 }
  return r
}

/** Load a book into this browser (from its saved position unless `at` is given) and optionally play. */
export async function loadBook(id: string, at?: { trackIndex: number; positionMs: number }, opts: { play?: boolean; speed?: number } = {}) {
  const d = await fetchDetail(id).catch((e) => details.value[id] ?? Promise.reject(e))
  if (!playable(d)) throw new Error(d.cloud ? 'This book is still uploading' : "This book isn't on the server")
  if (!d.tracks.length) throw new Error('This book has no audio files')
  const cur = book.value
  if (cur && cur.id !== d.id) {
    audio.pause()
    playing.value = false
    endStretch()
    pushProgress()
  }
  if (cur?.id !== d.id) {
    loadedKey = ''
    sleep.value = null
  }
  book.value = d
  store('mfb.lastBook', d.id)
  applyBookSettings(d)
  if (opts.speed) setSpeed(opts.speed, false)
  const p = at ?? resumePoint(d)
  setTrack(p.trackIndex, p.positionMs)
  if (opts.play) await play()
  else sendState()
}

/** Keep the loaded book fresh when its detail is refetched. */
effect(() => {
  const d = book.value && details.value[book.value.id]
  if (d && d !== book.value) book.value = d
})

export function unloadBook() {
  pause()
  endStretch()
  audio.removeAttribute('src')
  audio.load()
  loadedKey = ''
  book.value = null
  store('mfb.lastBook', null)
}

/** Restore the last book (paused) after a reload. */
export async function restoreLastBook() {
  const id = stored<string | null>('mfb.lastBook', null)
  if (id && !book.value) await loadBook(id).catch(() => store('mfb.lastBook', null))
}

// ---- Sync side effects: progress, book state, sessions ----

/** `base` for a progress push: the highest seq seen, from sync or from the book's `progress.seq`. */
export function progressBase(id: string) {
  return Math.max(seqOf('progress', id) ?? 0, details.value[id]?.progress?.seq ?? 0) || undefined
}

let lastPushed = '', lastPushAt = 0
function pushProgress() {
  const d = book.value
  if (!d) return
  const data = { trackIndex: track.value, positionInTrackMs: Math.round(curPos()) }
  const key = `${d.id}:${data.trackIndex}:${data.positionInTrackMs}`
  lastPushAt = Date.now()
  if (key === lastPushed) return
  lastPushed = key
  push({ kind: 'progress', id: d.id, base: progressBase(d.id), data })
  patchBook(d.id, { progress: { ...data, updatedAt: Date.now(), seq: details.value[d.id]?.progress?.seq } })
}

function markStarted() {
  const d = book.value
  if (!d) return
  const data: Record<string, unknown> = { lastPlayedAt: Date.now() }
  if (d.state !== 'STARTED' && localBookMs() < d.durationMs - 30_000) data.state = 'STARTED'
  push({ kind: 'book', id: d.id, data })
  patchBook(d.id, data)
}

let stretch: { bookId: string; title: string; startedAt: number } | null = null
function beginStretch() {
  if (!stretch && book.value) stretch = { bookId: book.value.id, title: book.value.title, startedAt: Date.now() }
}
function endStretch() {
  if (!stretch) return
  const listenedMs = Date.now() - stretch.startedAt
  if (listenedMs >= 10_000) {
    push({ kind: 'session', id: uuid(), bookId: stretch.bookId, data: { bookTitle: stretch.title, startedAt: stretch.startedAt, listenedMs } })
  }
  stretch = null
}

addEventListener('pagehide', () => {
  if (playing.value) pushProgress()
  endStretch()
  flushOnUnload()
})

onChanges((changes) => {
  const mine = myId()
  for (const c of changes) {
    if (c.deviceId === mine) continue
    const d = book.value
    if (c.kind === 'book' && c.deleted && d?.id === c.id) unloadBook()
    if (c.kind === 'progress' && d?.id === c.id && !playing.value && !c.deleted && c.data.trackIndex != null) {
      setTrack(c.data.trackIndex, c.data.positionInTrackMs ?? 0) // another device moved on while we were paused
    }
    if (c.kind === 'book_settings' && d?.id === c.id) applyBookSettings(d)
  }
})

// ---- Live: state reports and commands from other devices ----

function stateMsg() {
  const d = book.value
  if (!d) return { t: 'ping' }
  return {
    t: 'state', bookId: d.id, title: d.title, trackIndex: track.value, positionMs: Math.round(curPos()),
    speed: speed.value, playing: playing.value, sleepAt: sleepAtServer(), volume: volume.value,
  }
}
function sendState() { send(stateMsg()) }
hooks.heartbeat = () => (playing.value ? stateMsg() : { t: 'ping' })
hooks.onOpen = () => { if (book.value) sendState() }

hooks.onCmd = async (m) => {
  switch (m.action) {
    case 'play':
      if (m.bookId) {
        const at = m.trackIndex != null ? { trackIndex: m.trackIndex, positionMs: m.positionMs ?? 0 } : undefined
        await loadBook(m.bookId, at, { play: true, speed: m.speed })
      } else {
        if (!book.value) throw new Error('Nothing loaded')
        await play()
      }
      return
    case 'pause': return pause()
    case 'seek': return seekTrack(m.trackIndex, m.positionMs)
    case 'skip': return seekBook(localBookMs() + m.ms)
    case 'speed': return setSpeed(m.speed)
    case 'sleep': return setSleep(m.minutes)
    case 'volume': volume.value = Math.max(0, Math.min(1, +m.volume || 0)); return
    default: throw new Error(`Unknown action ${m.action}`)
  }
}

hooks.onPause = ({ state }) => {
  if (!book.value) return
  pause()
  if (state?.bookId === book.value.id) seekTrack(state.trackIndex, state.positionMs)
}

// ---- Controls used by the UI: act locally, or send commands in remote mode ----

function rc(action: CmdAction, args: object = {}) {
  const r = remote.value!
  cmd(r.id, action, args).catch((e: Error) => toast(`${r.name}: ${e.message}`))
}
const run = (p: Promise<unknown>) => p.catch((e: Error) => toast(e.message))

export const ctl = {
  toggle() {
    const v = view.value
    if (v.remote) return rc(v.playing ? 'pause' : 'play')
    if (!book.value) return
    playing.value ? pause() : run(play())
  },
  seekBook(ms: number) {
    const v = view.value
    if (!v.remote) return seekBook(ms)
    if (!v.book) return
    rc('seek', locate(v.book, Math.max(0, Math.min(ms, v.book.durationMs - 500))))
  },
  skip(ms: number) {
    if (view.value.remote) return rc('skip', { ms })
    seekBook(localBookMs() + ms)
  },
  chapter(delta: -1 | 1) {
    const v = view.value
    if (!v.book) return
    const chs = chaptersOf(v.book)
    const i = chapterAt(chs, v.bookMs)
    const c = chs[i]
    if (!c) return
    if (delta < 0 && v.bookMs - c.start > 3000) return ctl.seekBook(c.start)
    const t = chs[i + delta]
    if (t) ctl.seekBook(t.start)
  },
  speed(v: number) {
    if (view.value.remote) return rc('speed', { speed: Math.round(v * 20) / 20 })
    setSpeed(v)
  },
  sleep(minutes: number | null) {
    if (view.value.remote) return rc('sleep', { minutes })
    setSleep(minutes)
  },
  /** Play a book here, from its position or from a chapter. */
  playHere(id: string, at?: { trackIndex: number; positionMs: number }) {
    if (book.value?.id === id && !at) return run(play())
    return run(loadBook(id, at, { play: true }))
  },
}

/** Connect: move playback to `target` (W5). */
export async function moveTo(target: LiveDevice | 'here') {
  const r = remote.value
  if (target === 'here') {
    if (!r) return
    const s = r.state
    cmd(r.id, 'pause').catch((e: Error) => toast(`${r.name}: ${e.message}`))
    if (s?.bookId) await run(loadBook(s.bookId, { trackIndex: s.trackIndex, positionMs: Math.round(remotePos(s)) }, { play: true, speed: s.speed }))
    return
  }
  if (target.id === r?.id) return
  const s = r?.state
  const payload = s
    ? { bookId: s.bookId, trackIndex: s.trackIndex, positionMs: Math.round(remotePos(s)), speed: s.speed }
    : book.value
      ? { bookId: book.value.id, trackIndex: track.value, positionMs: Math.round(curPos()), speed: speed.value }
      : null
  if (!payload) return toast('Nothing to play')
  try {
    await cmd(target.id, 'play', payload)
    if (!r) pause()
  } catch (e: any) {
    toast(`${target.name}: ${e.message}`)
  }
}

// ---- Engine loop: position, finish mark, sleep fade, periodic progress ----

setInterval(() => {
  tick.value = Date.now()
  const d = book.value
  if (!d) return
  if (pendingSeek == null && !audio.seeking) pos.value = audio.currentTime * 1000
  if (!playing.value) return
  const bms = localBookMs()
  if (d.state !== 'FINISHED' && bms >= d.durationMs - 30_000) {
    push({ kind: 'book', id: d.id, data: { state: 'FINISHED' } })
    patchBook(d.id, { state: 'FINISHED' })
  }
  const left = sleepRemaining()
  if (left != null) {
    fade = Math.max(0, Math.min(1, left / 10_000))
    applyVolume()
    if (left <= 0) {
      pause()
      sleep.value = null
      fade = 1
      setTimeout(applyVolume, 300)
    }
  }
  if (Date.now() - lastPushAt >= 5000) pushProgress()
  updatePositionState()
}, 250)

// ---- Media Session (W3) ----

const localChs = computed(() => (book.value ? chaptersOf(book.value) : []))
const localCh = computed(() => chapterAt(localChs.value, book.value ? bookMsOf(book.value, track.value, pos.value) : 0))

const ms = 'mediaSession' in navigator ? navigator.mediaSession : null
const meta = computed(() => {
  const d = book.value
  const c = localChs.value[localCh.value]
  return d ? JSON.stringify({ title: c?.title ?? d.title, artist: d.author ?? '', album: d.title, artwork: d.cover ? [{ src: coverUrl(d) }] : [] }) : ''
})
effect(() => { if (ms) ms.metadata = meta.value ? new MediaMetadata(JSON.parse(meta.value)) : null })
effect(() => { if (ms) ms.playbackState = book.value ? (playing.value ? 'playing' : 'paused') : 'none' })

function updatePositionState() {
  const c = localChs.value[localCh.value]
  if (!ms || !c || c.dur <= 0) return
  try {
    ms.setPositionState({ duration: c.dur / 1000, position: Math.min(c.dur, Math.max(0, localBookMs() - c.start)) / 1000, playbackRate: speed.value })
  } catch {}
}

if (ms) {
  const h: [MediaSessionAction, MediaSessionActionHandler][] = [
    ['play', () => run(play())],
    ['pause', () => pause()],
    ['seekbackward', (e) => seekBook(localBookMs() - (e.seekOffset ? e.seekOffset * 1000 : skipBackSec.value * 1000))],
    ['seekforward', (e) => seekBook(localBookMs() + (e.seekOffset ? e.seekOffset * 1000 : skipFwdSec.value * 1000))],
    ['previoustrack', () => { const c = localChs.value; const i = localCh.value; seekBook(localBookMs() - (c[i]?.start ?? 0) > 3000 ? c[i]!.start : (c[i - 1]?.start ?? 0)) }],
    ['nexttrack', () => { const c = localChs.value[localCh.value + 1]; if (c) seekBook(c.start) }],
    ['seekto', (e) => { const c = localChs.value[localCh.value]; if (c && e.seekTime != null) seekBook(c.start + e.seekTime * 1000) }],
  ]
  for (const [a, f] of h) try { ms.setActionHandler(a, f) } catch {}
}
