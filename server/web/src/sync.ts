// Sync (S1, S5): a persistent push queue plus incremental pulls against /sync.
import { signal } from '@preact/signals'
import { api, ApiError } from './api'

export interface Change {
  kind: 'book' | 'progress' | 'book_settings' | 'bookmark' | 'history' | 'session' | 'app_settings'
  id: string; bookId?: string; updatedAt: number; base?: number; deleted?: boolean; data: Record<string, any>
  seq?: number; deviceId?: string; conflict?: unknown
}
interface SyncResponse { seq: number; now: number; changes: Change[] }

const UNION = new Set(['history', 'session'])
const KEEP_DATA = new Set(['app_settings', 'book_settings']) // the only records this client reads from the mirror

function load<T>(k: string, def: T): T {
  try { return JSON.parse(localStorage.getItem(k) ?? 'null') ?? def } catch { return def }
}
function save() {
  try {
    localStorage.setItem('mfb.since', JSON.stringify(since))
    localStorage.setItem('mfb.queue', JSON.stringify(queue))
    localStorage.setItem('mfb.recs', JSON.stringify(recs))
  } catch { /* storage full or disabled: keep going in memory */ }
}

let since = load('mfb.since', 0)
const queue: Change[] = load('mfb.queue', [])
/** seq (for `base`) of every record seen, plus merged data for the kinds we read. */
const recs: Record<string, { seq: number; data?: Record<string, any> }> = load('mfb.recs', {})

export type SyncStatus = 'synced' | 'syncing' | 'offline' | 'failed'
export const status = signal<SyncStatus>('syncing')
export const lastSync = signal<number | null>(null)
export const appSettings = signal<Record<string, any>>(recs['app_settings:app']?.data ?? {})

// Server clock offset, from sync responses and live frames.
let offset = 0
export const serverNow = () => Date.now() + offset
export function setServerTime(now: number, sentAt = Date.now()) { offset = now - (sentAt + Date.now()) / 2 }

export const seqOf = (kind: string, id: string) => recs[`${kind}:${id}`]?.seq
export const bookSettingsOf = (id: string) => recs[`book_settings:${id}`]?.data
export const hasQueued = (kind: string, id: string) => queue.find((c) => c.kind === kind && c.id === id)

const listeners: ((changes: Change[]) => void)[] = []
export const onChanges = (f: (changes: Change[]) => void) => listeners.push(f)

let busy = false, again = false, sending = 0, retryTimer = 0, backoff = 1000

/** Queue a change and push it soon. Consecutive changes to one record collapse into one. */
export function push(c: Omit<Change, 'updatedAt'> & { updatedAt?: number }) {
  const ch: Change = { ...c, updatedAt: c.updatedAt ?? Date.now() }
  if (!UNION.has(ch.kind) && ch.base === undefined) ch.base = seqOf(ch.kind, ch.id)
  const i = UNION.has(ch.kind) ? -1 : queue.findIndex((q, j) => j >= sending && q.kind === ch.kind && q.id === ch.id)
  if (i >= 0) queue[i] = { ...ch, data: { ...queue[i]!.data, ...ch.data } }
  else queue.push(ch)
  if (KEEP_DATA.has(ch.kind)) {
    const r = (recs[`${ch.kind}:${ch.id}`] ??= { seq: 0 })
    r.data = { ...r.data, ...ch.data }
    if (ch.kind === 'app_settings') appSettings.value = r.data
  }
  save()
  sync()
}

/** Push the queue (if any) and pull everything newer than `since`. Safe to call any time. */
export async function sync(): Promise<void> {
  if (busy) { again = true; return }
  busy = true
  clearTimeout(retryTimer)
  const slow = setTimeout(() => (status.value = 'syncing'), 400)
  try {
    do {
      again = false
      sending = queue.length
      const t0 = Date.now()
      const r = sending
        ? await api<SyncResponse>('/sync', { body: { since, now: t0, changes: queue.slice(0, sending) } })
        : await api<SyncResponse>(`/sync?since=${since}`)
      queue.splice(0, sending)
      sending = 0
      setServerTime(r.now, t0)
      apply(r)
    } while (again || queue.length)
    status.value = 'synced'
    lastSync.value = Date.now()
    backoff = 1000
  } catch (e) {
    sending = 0
    const st = e instanceof ApiError ? e.status : 0
    status.value = st === 0 || !navigator.onLine ? 'offline' : 'failed'
    if (st !== 401) {
      retryTimer = setTimeout(sync, backoff)
      backoff = Math.min(backoff * 2, 60_000)
    }
  } finally {
    clearTimeout(slow)
    busy = false
  }
}

function apply(r: SyncResponse) {
  if (r.seq < since) { since = 0; again = true; return } // server database was replaced: pull from scratch
  since = r.seq
  for (const c of r.changes) {
    if (UNION.has(c.kind)) continue
    const k = `${c.kind}:${c.id}`
    const prev = recs[k]
    recs[k] = { seq: c.seq ?? prev?.seq ?? 0, data: KEEP_DATA.has(c.kind) ? (c.deleted ? {} : { ...prev?.data, ...c.data }) : undefined }
    if (c.kind === 'app_settings' && c.id === 'app') appSettings.value = recs[k].data!
  }
  save()
  if (r.changes.length) listeners.forEach((f) => f(r.changes))
}

/** Last-chance push when the tab goes away. The queue stays in storage, so a lost request is retried next boot. */
export function flushOnUnload() {
  save()
  if (!queue.length) return
  fetch('/api/v1/sync', {
    method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ since, now: Date.now(), changes: queue }),
  }).catch(() => {})
}

addEventListener('online', () => sync())
