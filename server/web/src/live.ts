// Live socket (W4, W5, S3): device presence, playback state, commands between devices.
import { signal } from '@preact/signals'
import { books, ensureDetail, fetchDetail, patchBook, refreshBooksSoon, signedOut, uuid } from './api'
import { setServerTime, sync } from './sync'

export interface PlayState {
  bookId: string; title?: string; trackIndex: number; positionMs: number; speed: number
  playing: boolean; sleepAt?: number | null; at: number
}
export interface LiveDevice { id: string; name: string; kind: string; online: boolean; active: boolean; state?: PlayState }
export type CmdAction = 'play' | 'pause' | 'seek' | 'skip' | 'speed' | 'sleep'
export interface CmdMsg { t: 'cmd'; id: string; from: string; action: CmdAction; [k: string]: any }

export const devices = signal<LiveDevice[]>([])
export const connected = signal(false)
export const liveId = signal<string | null>(null)

/** Filled in by the player, which owns the playback state. */
export const hooks = {
  heartbeat: (): object => ({ t: 'ping' }),
  onOpen: () => {},
  onCmd: async (_m: CmdMsg): Promise<void> => { throw new Error('Not ready') },
  onPause: (_m: { by: string; state: PlayState }) => {},
}

let ws: WebSocket | null = null
let backoff = 1000, lastSent = 0
const pending = new Map<string, { resolve: () => void; reject: (e: Error) => void; timer: number }>()

export function connect() {
  if (ws) return
  ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/v1/live`)
  ws.onopen = () => {
    connected.value = true
    backoff = 1000
    hooks.onOpen()
    sync() // we may have missed `changed` frames while away
  }
  ws.onclose = () => {
    connected.value = false
    ws = null
    for (const [id, p] of pending) { clearTimeout(p.timer); p.reject(new Error('Disconnected')); pending.delete(id) }
    if (signedOut.value) return
    setTimeout(connect, backoff)
    backoff = Math.min(backoff * 2, 30_000)
  }
  ws.onmessage = (e) => {
    try { handle(JSON.parse(e.data)) } catch (err) { console.warn('live frame', err) }
  }
}

export function send(msg: object): boolean {
  if (ws?.readyState !== WebSocket.OPEN) return false
  ws.send(JSON.stringify(msg))
  lastSent = Date.now()
  return true
}

/** Send a command to another device; resolves on a positive ack, rejects on a negative one or after 5 s. */
export function cmd(target: string, action: CmdAction, args: object = {}): Promise<void> {
  const id = uuid()
  return new Promise((resolve, reject) => {
    if (!send({ t: 'cmd', id, target, action, ...args })) return reject(new Error('Not connected to the server'))
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('No answer from the device')) }, 5000)
    pending.set(id, { resolve, reject, timer })
  })
}

function handle(m: any) {
  switch (m.t) {
    case 'hello':
      liveId.value = m.deviceId
      setServerTime(m.now)
      break
    case 'devices':
      setServerTime(m.now)
      devices.value = m.devices
      for (const d of m.devices as LiveDevice[]) if (d.state?.bookId) ensureDetail(d.state.bookId)
      break
    case 'cmd':
      hooks.onCmd(m).then(
        () => send({ t: 'ack', id: m.id, ok: true }),
        (e: Error) => send({ t: 'ack', id: m.id, ok: false, error: e.message || 'Failed' }),
      )
      break
    case 'ack': {
      const p = pending.get(m.id)
      if (!p) break
      pending.delete(m.id)
      clearTimeout(p.timer)
      m.ok ? p.resolve() : p.reject(new Error(m.error === 'offline' ? 'The device is offline' : m.error || 'Failed'))
      break
    }
    case 'pause':
      hooks.onPause(m)
      break
    case 'changed':
      sync()
      break
    case 'book': {
      const b = books.value.find((x) => x.id === m.id)
      if (!b) refreshBooksSoon()
      // conversion ticks patch the card in place; any other change (done, failed, upload) refetches
      else if (m.conversion === 'converting' && b.cloud) patchBook(b.id, { cloud: { ...b.cloud, conversion: m.conversion, progress: m.progress ?? b.cloud.progress } })
      else fetchDetail(m.id).catch(() => {})
      break
    }
  }
}

setInterval(() => {
  if (Date.now() - lastSent >= 5000) send(hooks.heartbeat())
}, 1000)
