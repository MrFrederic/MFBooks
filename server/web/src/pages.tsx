// Upload, Devices and Settings pages.
import { useSignalEffect } from '@preact/signals'
import { useEffect, useRef, useState } from 'preact/hooks'
import { renderSVG } from 'uqr'
import { api, type Device, fmtAgo, fmtBytes, type ServerSettings, toast } from './api'
import { Icon } from './icons'
import { devices as liveDevices } from './live'
import { serverNow } from './sync'
import { confirm, Switch } from './ui'
import { addFiles, clearFinished, retry, uploads } from './uploads'

// ---- Upload (U2, U3) ----

const STATE_LABEL = { queued: 'Waiting', uploading: 'Uploading', retrying: 'Connection lost, retrying', importing: 'Importing', done: 'Added', failed: 'Failed' }

export function UploadPage() {
  const [over, setOver] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const list = uploads.value
  return (
    <div class="page narrow">
      <h1 class="page-title">Add books</h1>
      <div
        class={`dropzone ${over ? 'over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setOver(true) }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); if (e.dataTransfer?.files.length) addFiles(e.dataTransfer.files) }}
        onClick={() => input.current?.click()}
      >
        <Icon name="upload" size={40} />
        <div class="title-m">Drop audio files or a .zip of a book folder</div>
        <div class="body-m muted">Each file becomes one book. Big uploads resume if the connection drops.</div>
        <button class="btn filled" type="button">Choose files</button>
        <input ref={input} type="file" multiple hidden accept="audio/*,.m4b,.m4a,.mp3,.ogg,.opus,.flac,.zip"
          onChange={(e) => { const f = e.currentTarget.files; if (f?.length) addFiles(f); e.currentTarget.value = '' }} />
      </div>
      {list.length > 0 && (
        <section class="panel-card">
          <div class="row-between">
            <h3 class="title-m">Uploads</h3>
            {list.some((u) => u.state === 'done' || u.state === 'failed') && <button class="btn text" onClick={clearFinished}>Clear finished</button>}
          </div>
          <ul class="upload-list">
            {list.map((u) => {
              const pct = u.size ? u.sent / u.size : 0
              return (
                <li key={u.key} class={`upload ${u.state}`}>
                  <div class="row-between">
                    <span class="body-l ellip">{u.name}</span>
                    <span class="label-m muted tnum">{fmtBytes(u.size)}</span>
                  </div>
                  <div class={`pbar ${u.state === 'importing' ? 'indeterminate' : ''}`}><div style={{ width: `${(u.state === 'done' ? 1 : pct) * 100}%` }} /></div>
                  <div class="row-between body-s">
                    <span class={u.state === 'failed' ? 'err' : 'muted'}>
                      {STATE_LABEL[u.state]}{u.state === 'uploading' && ` · ${Math.floor(pct * 100)}%`}{u.error && u.state !== 'uploading' && ` · ${u.error}`}
                    </span>
                    {u.state === 'done' && u.bookId && <a class="link" href={`#/book/${u.bookId}`}>Open book</a>}
                    {u.state === 'failed' && <button class="btn text" onClick={() => retry(u.key)}>Retry</button>}
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      )}
    </div>
  )
}

// ---- Devices (C1, C2) ----

function Pairing() {
  const [p, setP] = useState<{ code: string; expiresAt: number } | null>(null)
  const [, setNow] = useState(0)
  useEffect(() => {
    if (!p) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [p])
  const start = () => api<{ code: string; expiresAt: number }>('/devices/pairing', { method: 'POST' }).then(setP, (e) => toast(e.message))
  if (!p) return <button class="btn filled" onClick={start}><Icon name="qr" /> Link a phone</button>
  const left = p.expiresAt - serverNow()
  const link = `mfbooks://pair?server=${location.origin}&code=${p.code}`
  return (
    <section class="panel-card pairing">
      {left > 0 ? (
        <>
          <div class="qr" dangerouslySetInnerHTML={{ __html: renderSVG(link, { border: 2 }) }} />
          <div class="pair-text">
            <h3 class="title-l">Scan with the MFBooks app</h3>
            <p class="body-m muted">Or enter the server address and code in the app's Server settings.</p>
            <dl class="facts">
              <dt>Server</dt><dd class="mono">{location.origin}</dd>
              <dt>Code</dt><dd class="mono headline-s">{p.code}</dd>
              <dt>Expires</dt><dd class="tnum">in {Math.floor(left / 60000)}:{String(Math.floor((left % 60000) / 1000)).padStart(2, '0')}</dd>
            </dl>
            <button class="btn text" onClick={() => setP(null)}>Done</button>
          </div>
        </>
      ) : (
        <div class="pair-text">
          <p class="body-m">The code expired.</p>
          <button class="btn filled" onClick={start}>New code</button>
        </div>
      )}
    </section>
  )
}

export function DevicesPage() {
  const [list, setList] = useState<Device[] | null>(null)
  const load = () => api<Device[]>('/devices').then(setList, (e) => toast(e.message))
  useSignalEffect(() => { liveDevices.value; load() }) // presence changes arrive over the live socket
  const revoke = async (d: Device) => {
    const body = d.current ? 'This browser will be signed out.' : `${d.name} will be signed out and must be linked again.`
    if (!(await confirm(`Revoke ${d.name}?`, body, 'Revoke'))) return
    try {
      await api(`/devices/${d.id}`, { method: 'DELETE' })
      if (d.current) location.reload()
      else load()
    } catch (e: any) { toast(e.message) }
  }
  return (
    <div class="page narrow">
      <h1 class="page-title">Linked devices</h1>
      <Pairing />
      <ul class="panel-card device-table">
        {list?.map((d) => (
          <li key={d.id} class="device-row">
            <Icon name={d.kind === 'android' ? 'phone' : 'computer'} />
            <div class="grow">
              <div class="body-l">{d.name}{d.current && <span class="tag">This browser</span>}</div>
              <div class="body-s muted"><span class={`dot ${d.online ? 'on' : ''}`} />{d.online ? 'Online' : `Last seen ${fmtAgo(d.lastSeen)}`} · {d.kind === 'android' ? 'Phone' : 'Browser'}</div>
            </div>
            <button class="btn text danger" onClick={() => revoke(d)}>Revoke</button>
          </li>
        ))}
        {list && !list.length && <li class="body-m muted pad">No devices.</li>}
      </ul>
    </div>
  )
}

// ---- Settings ----

export function SettingsPage() {
  const [s, setS] = useState<ServerSettings | null>(null)
  const [name, setName] = useState('')
  useEffect(() => { api<ServerSettings>('/settings').then((x) => { setS(x); setName(x.serverName) }, (e) => toast(e.message)) }, [])
  const save = (p: Partial<Pick<ServerSettings, 'serverName' | 'convert' | 'storageWarnGb'>>) =>
    api<ServerSettings>('/settings', { method: 'PUT', body: p }).then(setS, (e) => toast(e.message))
  const logout = async () => {
    await api('/auth/logout', { method: 'POST' }).catch(() => {})
    location.reload() // ponytail: a reload resets every module's state cleanly
  }
  if (!s) return <div class="page narrow"><div class="loading">Loading…</div></div>
  const total = s.usedBytes + s.freeBytes
  return (
    <div class="page narrow">
      <h1 class="page-title">Settings</h1>
      <section class="panel-card form">
        <label class="field">
          <span class="label-l">Server name</span>
          <input value={name} onInput={(e) => setName(e.currentTarget.value)} onBlur={() => name.trim() && name !== s.serverName && save({ serverName: name.trim() })} />
        </label>
        <Switch label="Convert uploads to Opus" checked={s.convert} onChange={(convert) => save({ convert })} />
        <p class="body-s muted">Smaller files that stream well. The originals' checksums are kept for sync.</p>
      </section>
      <section class="panel-card form">
        <h3 class="title-m">Storage</h3>
        {s.storageLow && <div class="banner"><Icon name="warning" /><span class="body-m">Storage is running low: {fmtBytes(s.freeBytes)} free.</span></div>}
        <div class="pbar big"><div style={{ width: `${total ? (s.usedBytes / total) * 100 : 0}%` }} /></div>
        <div class="row-between body-m"><span>{fmtBytes(s.usedBytes)} used by books</span><span class="muted">{fmtBytes(s.freeBytes)} free</span></div>
        <label class="field">
          <span class="label-l">Warn when free space is below (GB)</span>
          <input type="number" min={0} step={1} value={s.storageWarnGb} onChange={(e) => save({ storageWarnGb: Math.max(0, +e.currentTarget.value) })} />
        </label>
      </section>
      <section class="panel-card form">
        <h3 class="title-m">Data</h3>
        <p class="body-m muted">A zip with the database snapshot, the records as JSON and the covers. Audio is not included.</p>
        <div class="row-actions">
          <a class="btn tonal" href="/api/v1/export" download><Icon name="download" /> Export all data</a>
          <button class="btn outlined danger" onClick={logout}><Icon name="logout" /> Sign out</button>
        </div>
      </section>
    </div>
  )
}
