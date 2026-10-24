// Upload manager (U2, U3): sequential, resumable chunked uploads that outlive the Upload page.
import { signal } from '@preact/signals'
import { api, refreshBooksSoon, type Upload } from './api'

const CHUNK = 8 * 1024 * 1024

export interface UploadItem {
  key: number; file: File; name: string; size: number; sent: number
  state: 'queued' | 'uploading' | 'retrying' | 'importing' | 'done' | 'failed'
  id?: string; bookId?: string; error?: string
}

export const uploads = signal<UploadItem[]>([])
let nextKey = 0, running = false

function update(key: number, p: Partial<UploadItem>) {
  uploads.value = uploads.value.map((u) => (u.key === key ? { ...u, ...p } : u))
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export function addFiles(files: Iterable<File>) {
  const add = [...files].map((file): UploadItem => ({ key: nextKey++, file, name: file.name, size: file.size, sent: 0, state: 'queued' }))
  uploads.value = [...uploads.value, ...add]
  run()
}
export function retry(key: number) {
  update(key, { state: 'queued', error: undefined })
  run()
}
export function clearFinished() {
  uploads.value = uploads.value.filter((u) => u.state !== 'done' && u.state !== 'failed')
}
export const busy = () => uploads.value.some((u) => ['queued', 'uploading', 'retrying', 'importing'].includes(u.state))

async function run() {
  if (running) return
  running = true
  try {
    for (let u; (u = uploads.value.find((x) => x.state === 'queued')); ) await uploadOne(u)
  } finally {
    running = false
  }
}

async function uploadOne(item: UploadItem) {
  const { key, file } = item
  let up: Upload | null = null
  let failures = 0
  update(key, { state: 'uploading' })
  while (true) {
    try {
      if (!up) up = item.id ? await api<Upload>(`/uploads/${item.id}`) : await api<Upload>('/uploads', { body: { name: file.name, size: file.size } })
      update(key, { id: up.id, sent: up.offset, state: up.state === 'receiving' ? 'uploading' : 'importing' })
      if (up.state === 'failed') throw Object.assign(new Error(up.error || 'Import failed'), { final: true })
      if (up.state !== 'receiving' || up.offset >= up.size) break
      const off: number = up.offset
      up = await api<Upload>(`/uploads/${up.id}`, {
        raw: file.slice(off, off + CHUNK),
        headers: { 'Upload-Offset': String(off), 'Content-Type': 'application/octet-stream' },
      })
      failures = 0
    } catch (e: any) {
      if (e.final || ++failures > 8) return update(key, { state: 'failed', error: e.message })
      update(key, { state: 'retrying', error: e.message })
      await sleep(Math.min(30_000, 1000 * 2 ** failures))
      // ponytail: re-read the server's offset after any error rather than guessing what arrived
      if (up?.id) up = await api<Upload>(`/uploads/${up.id}`).catch(() => up)
    }
  }
  // The server imports in the background; poll until it settles (the live `book` event refreshes the library too).
  update(key, { state: 'importing', sent: file.size })
  for (let i = 0; ; i++) {
    await sleep(Math.min(5000, 1000 + i * 500))
    const u = await api<Upload>(`/uploads/${up!.id}`).catch(() => null)
    if (!u || u.state === 'importing' || u.state === 'receiving') continue
    if (u.state === 'done') {
      update(key, { state: 'done', bookId: u.bookId })
      refreshBooksSoon()
    } else update(key, { state: 'failed', error: u.error || 'Import failed' })
    return
  }
}

addEventListener('beforeunload', (e) => { if (busy()) e.preventDefault() })
