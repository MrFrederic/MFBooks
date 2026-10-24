import { signal } from '@preact/signals'

// ---- Types (mirror server/API.md) ----

export type BookState = 'NEW' | 'STARTED' | 'FINISHED'
export type Conversion = 'none' | 'queued' | 'converting' | 'done' | 'failed'

export interface Progress { trackIndex: number; positionInTrackMs: number; updatedAt: number; seq?: number }
export interface Cloud {
  files: number; ready: number; bytes: number; verified: boolean
  conversion: Conversion; progress: number; error?: string
}
export interface BookSummary {
  id: string; title: string; author: string | null; narrator: string | null
  series: string | null; seriesIndex: number | string | null; year: number | null; genre: string | null
  durationMs: number; seedColor: number | null; state: BookState
  addedAt: number; lastPlayedAt: number | null; hidden: boolean; updatedAt: number; cover: boolean
  cloud: Cloud | null; progress: Progress | null
}
export interface Track {
  index: number; fileName: string; title: string | null; durationMs: number; sizeBytes: number; sha256: string
  cloud: null | { state: 'receiving' | 'ready'; format: string; bytes: number; received: number }
}
export interface Chapter { index: number; title: string; trackIndex: number; startInTrackMs: number; durationMs: number; startInBookMs: number }
export interface BookSettings {
  speed?: number | null; savedToggleSpeed?: number | null; pitch?: number | null; boostMb?: number | null
  eqBandsDb?: string | null; balance?: number | null; mono?: boolean | null; skipSilence?: boolean | null
  skipStartMs?: number | null; skipEndMs?: number | null; repeatMode?: string | null
}
export interface Conflict { deviceName: string; trackIndex: number; positionInTrackMs: number; updatedAt: number }
export interface BookDetail extends BookSummary {
  description: string | null; publisher: string | null
  tracks: Track[]; chapters: Chapter[]; settings: BookSettings | null
  bookmarks: unknown[]; conflict: Conflict | null
}
export interface Info { name: string; version: string; apiVersion: number; minAppVersion: number | string; setupDone: boolean }
export interface Me { username: string; deviceId: string; deviceName: string; kind: string }
export interface Device { id: string; name: string; kind: string; lastSeen: number; online: boolean; current: boolean }
export interface ServerSettings { serverName: string; convert: boolean; storageWarnGb: number; usedBytes: number; freeBytes: number; storageLow: boolean }
export interface Upload { id: string; name: string; size: number; offset: number; state: 'receiving' | 'importing' | 'done' | 'failed'; bookId?: string; error?: string }

// ---- Fetch ----

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

/** Set to true when any call returns 401; the app shows the sign-in page. */
export const signedOut = signal(false)

type Opts = { method?: string; body?: unknown; raw?: BodyInit; headers?: Record<string, string>; keepalive?: boolean }

export async function api<T = any>(path: string, o: Opts = {}): Promise<T> {
  const headers: Record<string, string> = { ...o.headers }
  let body = o.raw
  if (o.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(o.body) }
  let res: Response
  try {
    res = await fetch('/api/v1' + path, { method: o.method ?? (body ? 'POST' : 'GET'), headers, body, keepalive: o.keepalive })
  } catch {
    throw new ApiError(0, 'Server unreachable')
  }
  if (res.status === 401 && !path.startsWith('/auth/')) signedOut.value = true
  if (!res.ok) {
    const msg = await res.json().then((j) => j.error, () => null)
    throw new ApiError(res.status, msg || `${res.status} ${res.statusText}`)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : {}) as T
}

export const coverUrl = (b: Pick<BookSummary, 'id' | 'updatedAt'>) => `/api/v1/books/${b.id}/cover?v=${b.updatedAt}`
export const fileUrl = (id: string, i: number) => `/api/v1/books/${id}/files/${i}`

// ---- Library state ----

export const books = signal<BookSummary[]>([])
export const details = signal<Record<string, BookDetail>>({})
export const me = signal<Me | null>(null)

/** Only books with a cloud copy: the rest live on a phone and can't play here. */
export async function refreshBooks() {
  books.value = (await api<BookSummary[]>('/books')).filter((b) => b.cloud)
}

let refreshTimer = 0
/** Coalesce bursts of changes into one GET /books. */
export function refreshBooksSoon() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => refreshBooks().catch(() => {}), 400)
}

const inflight = new Map<string, Promise<BookDetail>>()
export function fetchDetail(id: string): Promise<BookDetail> {
  let p = inflight.get(id)
  if (!p) {
    p = api<BookDetail>(`/books/${id}`).then((d) => {
      details.value = { ...details.value, [id]: d }
      // the detail is a superset of the summary: keep the list fresh too
      books.value = books.value.map((b) => (b.id === id ? { ...b, ...d } : b))
      return d
    }, (e) => {
      if (e instanceof ApiError && e.status === 404) forgetBook(id)
      throw e
    }).finally(() => inflight.delete(id))
    inflight.set(id, p)
  }
  return p
}
/** Cached detail, fetching it in the background when missing. */
export function ensureDetail(id: string) {
  if (!details.value[id]) fetchDetail(id).catch(() => {})
}

export function forgetBook(id: string) {
  books.value = books.value.filter((b) => b.id !== id)
  const { [id]: _, ...rest } = details.value
  details.value = rest
}

/** Local optimistic patch of a book in the list and the detail cache. */
export function patchBook(id: string, p: Partial<BookDetail>) {
  books.value = books.value.map((b) => (b.id === id ? { ...b, ...p } : b))
  const d = details.value[id]
  if (d) details.value = { ...details.value, [id]: { ...d, ...p } }
}

// ---- Small shared helpers ----

export const uuid = () => crypto.randomUUID()

export function fmtTime(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`
}
export function fmtDuration(ms: number) {
  const m = Math.round(ms / 60000), h = Math.floor(m / 60)
  return h ? `${h} h ${m % 60} min` : `${m} min`
}
export function fmtBytes(n: number) {
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(i && n < 10 ? 1 : 0)} ${u[i]}`
}
export function fmtAgo(t: number | null) {
  if (!t) return 'never'
  const s = (Date.now() - t) / 1000
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(t).toLocaleDateString()
}

/** Start of each track in the book, from track durations. */
export function trackStarts(d: Pick<BookDetail, 'tracks'>) {
  const out: number[] = []
  let acc = 0
  for (const t of d.tracks) { out.push(acc); acc += t.durationMs }
  return out
}

/** Position in the book for a summary; exact when the detail is cached. */
export function bookPosition(b: BookSummary): number {
  const p = b.progress
  if (!p) return 0
  const d = details.value[b.id]
  if (d?.tracks.length) return (trackStarts(d)[p.trackIndex] ?? 0) + p.positionInTrackMs
  // ponytail: no track durations in BookSummary, so assume equal-length tracks until the detail is loaded
  const n = b.cloud?.files || 1
  return p.trackIndex * (b.durationMs / n) + p.positionInTrackMs
}

export const playable = (b: BookSummary | undefined | null) => !!b?.cloud && b.cloud.ready === b.cloud.files

export function initials(title: string) {
  return title.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('')
}

// ---- Toasts ----

export const toasts = signal<{ id: number; text: string }[]>([])
let toastId = 0
export function toast(text: string) {
  const id = ++toastId
  toasts.value = [...toasts.value, { id, text }]
  setTimeout(() => (toasts.value = toasts.value.filter((t) => t.id !== id)), 4500)
}
