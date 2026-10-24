// Boot, auth (A1, A2, W1), the app shell and the top bar.
import { effect, signal } from '@preact/signals'
import { useState } from 'preact/hooks'
import type { JSX } from 'preact'
import { api, ApiError, books, coverUrl, type Info, me, refreshBooks, signedOut } from './api'
import { ConnectPopover, FullPlayer, NowPlayingPanel, PlayerBar } from './bar'
import { Icon } from './icons'
import { BookView, Home, LibraryPage, Sidebar } from './library'
import { connect } from './live'
import { ctl, nowPlayingId, restoreLastBook, skipBackSec, skipFwdSec, view } from './player'
import { DevicesPage, SettingsPage, UploadPage } from './pages'
import { sync } from './sync'
import { seedFromCover, setSeed } from './theme'
import { ConfirmHost, Switch, Toasts, fullPlayer, nowPanel, route } from './ui'

type Phase = 'loading' | 'setup' | 'signin' | 'ready' | 'down'
const phase = signal<Phase>('loading')
const info = signal<Info | null>(null)

async function boot() {
  try {
    info.value = await api<Info>('/info')
  } catch {
    phase.value = 'down'
    setTimeout(boot, 3000)
    return
  }
  if (info.value.name) document.title = info.value.name
  if (!info.value.setupDone) { phase.value = 'setup'; return }
  try {
    me.value = await api('/me')
  } catch (e) {
    phase.value = e instanceof ApiError && e.status === 401 ? 'signin' : 'down'
    if (phase.value === 'down') setTimeout(boot, 3000)
    return
  }
  signedOut.value = false
  phase.value = 'ready'
  await refreshBooks().catch(() => {})
  sync()
  connect()
  restoreLastBook()
}
effect(() => { if (signedOut.value && phase.value === 'ready') location.reload() }) // session revoked elsewhere

// Colours follow the book that plays (here or on another device), else the book on screen.
let seedFor = ''
effect(() => {
  const r = route.value
  const id = nowPlayingId.value || (r.page === 'book' && r.id)
  const b = id ? books.value.find((x) => x.id === id) : null
  const key = b ? `${b.id}:${b.updatedAt}` : ''
  if (key === seedFor) return
  seedFor = key
  if (!b) return setSeed(null)
  if (b.seedColor != null) return setSeed(b.seedColor)
  if (!b.cover) return setSeed(null)
  seedFromCover(coverUrl(b)).then((s) => { if (seedFor === key) setSeed(s) })
})

// Keyboard: space toggles, arrows skip.
const typing = (t: EventTarget | null) => t instanceof HTMLElement && !!t.closest('input, textarea, select, [contenteditable]')
addEventListener('keydown', (e) => {
  if (phase.value !== 'ready' || e.ctrlKey || e.metaKey || e.altKey || typing(e.target)) return
  if (e.code === 'Space') { e.preventDefault(); if (!e.repeat) ctl.toggle() }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); ctl.skip(-skipBackSec.value * 1000) }
  else if (e.key === 'ArrowRight') { e.preventDefault(); ctl.skip(skipFwdSec.value * 1000) }
})
addEventListener('keyup', (e) => { if (e.code === 'Space' && !typing(e.target)) e.preventDefault() }) // no button click on space

// ---- Auth pages ----

function deviceName() {
  const ua = navigator.userAgent
  const b = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : 'Browser'
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${b} on ${os}` : b
}

function AuthCard({ title, sub, children }: { title: string; sub?: string; children: JSX.Element | JSX.Element[] }) {
  return (
    <div class="auth">
      <div class="auth-card">
        <div class="brand-mark"><Icon name="play" size={28} /></div>
        <h1 class="headline-m">{title}</h1>
        {sub && <p class="body-m muted">{sub}</p>}
        {children}
      </div>
    </div>
  )
}

function useForm() {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const run = (f: () => Promise<unknown>) => async (e: Event) => {
    e.preventDefault()
    setBusy(true); setErr(null)
    try { await f() } catch (x: any) { setErr(x.message) } finally { setBusy(false) }
  }
  return { busy, err, setErr, run }
}

const Field = (p: { label: string; type?: 'text' | 'password'; value: string; set: (v: string) => void; auto?: string; min?: number }) => (
  <label class="field">
    <span class="label-l">{p.label}</span>
    <input type={(p.type ?? 'text') as 'text'} value={p.value} required minLength={p.min} autoComplete={p.auto} onInput={(e) => p.set(e.currentTarget.value)} />
  </label>
)

function SetupPage() {
  const [u, setU] = useState(''), [pw, setPw] = useState(''), [pw2, setPw2] = useState('')
  const [name, setName] = useState('MFBooks'), [convert, setConvert] = useState(true)
  const f = useForm()
  const submit = f.run(async () => {
    if (pw !== pw2) throw new Error("The passwords don't match")
    await api('/setup', { body: { username: u.trim(), password: pw, serverName: name.trim(), convert } })
    await boot()
  })
  return (
    <AuthCard title="Set up your server" sub="Create the owner account. You can link your phone afterwards.">
      <form onSubmit={submit} class="form">
        <Field label="Username" value={u} set={setU} auto="username" />
        <Field label="Password" type="password" value={pw} set={setPw} auto="new-password" min={8} />
        <Field label="Repeat password" type="password" value={pw2} set={setPw2} auto="new-password" min={8} />
        <Field label="Server name" value={name} set={setName} />
        <Switch label="Convert uploads to Opus" checked={convert} onChange={setConvert} />
        {f.err && <p class="err body-m">{f.err}</p>}
        <button class="btn filled big" disabled={f.busy}>Create account</button>
      </form>
    </AuthCard>
  )
}

function SignInPage() {
  const [mode, setMode] = useState<'login' | 'reset'>('login')
  const [u, setU] = useState(''), [pw, setPw] = useState(''), [pw2, setPw2] = useState(''), [code, setCode] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const f = useForm()
  const login = f.run(async () => {
    try {
      await api('/auth/login', { body: { username: u.trim(), password: pw, deviceName: deviceName(), kind: 'web' } })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) throw new Error('Wrong username or password')
      if (e instanceof ApiError && e.status === 429) throw new Error('Too many attempts. Wait a few minutes and try again.')
      throw e
    }
    await boot()
  })
  const forgot = async () => {
    f.setErr(null)
    try {
      await api('/auth/reset/request', { method: 'POST' })
      setMode('reset'); setPw(''); setPw2('')
    } catch (e: any) { f.setErr(e.message) }
  }
  const reset = f.run(async () => {
    if (pw !== pw2) throw new Error("The passwords don't match")
    await api('/auth/reset', { body: { code: code.trim(), password: pw } })
    setMode('login'); setPw(''); setPw2(''); setCode('')
    setNote('Password changed. Sign in with the new one.')
  })
  if (mode === 'reset') {
    return (
      <AuthCard title="Reset password" sub="A one-time code was printed to the server log. It is valid for 15 minutes.">
        <form onSubmit={reset} class="form">
          <Field label="Code from the server log" value={code} set={setCode} auto="one-time-code" />
          <Field label="New password" type="password" value={pw} set={setPw} auto="new-password" min={8} />
          <Field label="Repeat new password" type="password" value={pw2} set={setPw2} auto="new-password" min={8} />
          {f.err && <p class="err body-m">{f.err}</p>}
          <button class="btn filled big" disabled={f.busy}>Set password</button>
          <button type="button" class="btn text" onClick={() => setMode('login')}>Back to sign in</button>
        </form>
      </AuthCard>
    )
  }
  return (
    <AuthCard title={info.value?.name || 'MFBooks'} sub="Sign in to listen in the browser.">
      <form onSubmit={login} class="form">
        <Field label="Username" value={u} set={setU} auto="username" />
        <Field label="Password" type="password" value={pw} set={setPw} auto="current-password" />
        {note && <p class="ok body-m">{note}</p>}
        {f.err && <p class="err body-m">{f.err}</p>}
        <button class="btn filled big" disabled={f.busy}>Sign in</button>
        <button type="button" class="btn text" onClick={forgot}>Forgot password</button>
      </form>
    </AuthCard>
  )
}

// ---- Shell ----

const narrowQ = matchMedia('(max-width: 899px)'), wideQ = matchMedia('(min-width: 1280px)')
const narrow = signal(narrowQ.matches), wide = signal(wideQ.matches)
narrowQ.addEventListener('change', () => (narrow.value = narrowQ.matches))
wideQ.addEventListener('change', () => (wide.value = wideQ.matches))

function Page() {
  const r = route.value
  switch (r.page) {
    case 'book': return r.id ? <BookView key={r.id} id={r.id} /> : <Home />
    case 'library': return <LibraryPage />
    case 'upload': return <UploadPage />
    case 'devices': return <DevicesPage />
    case 'settings': return <SettingsPage />
    default: return <Home />
  }
}

/** The playing book's cover, blurred into the light behind the whole app. */
function Ambient() {
  const d = view.value.book
  return <div class="ambient" aria-hidden="true">{d?.cover && <div key={d.id} class="ambient-img" style={{ backgroundImage: `url(${coverUrl(d)})` }} />}</div>
}

function Shell() {
  // The Library page shows the whole index, so the sidebar folds to a rail instead of repeating it.
  const rail = narrow.value || route.value.page === 'library'
  const panel = nowPanel.value && wide.value && !!view.value.book
  return (
    <div class={`shell ${rail ? 'is-rail' : ''} ${panel ? 'has-panel' : ''}`}>
      <Ambient />
      <Sidebar name={info.value?.name || 'MFBooks'} rail={rail} />
      <main class="main" id="main">{<Page />}</main>
      {panel && <NowPlayingPanel />}
      <PlayerBar />
      {fullPlayer.value && <FullPlayer />}
      <ConnectPopover />
    </div>
  )
}

export function App() {
  const content = {
    loading: <div class="auth"><div class="spinner big" /></div>,
    down: <AuthCard title="Can't reach the server" sub="Retrying…"><div class="spinner" /></AuthCard>,
    setup: <SetupPage />,
    signin: <SignInPage />,
    ready: <Shell />,
  }[phase.value]
  return <>{content}<Toasts /><ConfirmHost /></>
}

boot()
