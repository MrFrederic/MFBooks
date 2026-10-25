// The player: bottom bar (W2), its popovers, Connect (W4/W5), the Now playing panel and the full-screen player.
import { signal } from '@preact/signals'
import { useEffect, useRef, useState } from 'preact/hooks'
import { coverUrl, fmtDuration, fmtTime, playable, toast } from './api'
import { Icon, SkipIcon } from './icons'
import { cmd, devices, type LiveDevice } from './live'
import {
  boostMb, book, buffering, chapterAt, chaptersOf, ctl, eq, moveTo, myId, remote, setBoost, setEq, skipBackSec, skipFwdSec,
  sleep, view, volume,
} from './player'
import { serverNow } from './sync'
import { Cover, fullPlayer, Menu, nowPanel, Playing, Popover, Range } from './ui'

const SPEEDS = [0.8, 1, 1.2, 1.5, 1.75, 2]
const SLEEPS: [number | null, string][] = [[5, '5 minutes'], [10, '10 minutes'], [15, '15 minutes'], [30, '30 minutes'], [45, '45 minutes'], [60, '1 hour'], [-1, 'End of chapter']]
const BAND_LABELS = ['60', '230', '910', '4k', '14k']

const fmtSpeed = (s: number) => `${s.toFixed(2).replace(/0$/, '').replace(/\.0$/, '')}×`
const kindIcon = (k: string) => (k === 'android' ? 'phone' : 'computer') as 'phone' | 'computer'

function current() {
  const v = view.value
  const chs = v.book ? chaptersOf(v.book) : []
  const i = chapterAt(chs, v.bookMs)
  return { v, chs, i, c: chs[i] }
}

// ---- Transport and seek ----

function Transport({ big }: { big?: boolean }) {
  const v = view.value
  const none = !v.book && !v.remote
  const s = big ? 32 : 26
  return (
    <div class={`transport ${big ? 'big' : ''}`}>
      <button class="icon-btn" disabled={none} title={`Back ${skipBackSec.value} seconds`} aria-label={`Back ${skipBackSec.value} seconds`} onClick={() => ctl.skip(-skipBackSec.value * 1000)}><SkipIcon dir="back" sec={skipBackSec.value} size={s} /></button>
      <button class="icon-btn" disabled={none} title="Previous chapter" aria-label="Previous chapter" onClick={() => ctl.chapter(-1)}><Icon name="prev" size={s} /></button>
      <button class={`play ${v.playing ? 'playing' : ''} ${v.playing && !v.remote && buffering.value ? 'loading' : ''}`} disabled={none}
        aria-label={v.playing ? (buffering.value && !v.remote ? 'Loading, click to pause' : 'Pause') : 'Play'} onClick={ctl.toggle}>
        <Icon name={v.playing ? 'pause' : 'play'} size={big ? 36 : 26} />
      </button>
      <button class="icon-btn" disabled={none} title="Next chapter" aria-label="Next chapter" onClick={() => ctl.chapter(1)}><Icon name="next" size={s} /></button>
      <button class="icon-btn" disabled={none} title={`Forward ${skipFwdSec.value} seconds`} aria-label={`Forward ${skipFwdSec.value} seconds`} onClick={() => ctl.skip(skipFwdSec.value * 1000)}><SkipIcon dir="fwd" sec={skipFwdSec.value} size={s} /></button>
    </div>
  )
}

function SeekBar() {
  const { v, c } = current()
  const [drag, setDrag] = useState<number | null>(null)
  const start = c?.start ?? 0
  const dur = c?.dur ?? v.book?.durationMs ?? 0
  const at = drag ?? Math.max(0, Math.min(dur, v.bookMs - start))
  return (
    <div class="seek">
      <span class="tnum">{fmtTime(at)}</span>
      <Range
        label="Position in chapter" valueText={`${fmtTime(at)} of ${fmtTime(dur)}`} min={0} max={Math.max(1, dur)} step={1000} value={at}
        disabled={!v.book} onInput={setDrag} onChange={(x) => { setDrag(null); ctl.seekBook(start + x) }}
      />
      <span class="tnum">-{fmtTime(dur - at)}</span>
    </div>
  )
}

// ---- Popovers ----

function SpeedMenu() {
  const v = view.value
  const [val, setVal] = useState<number | null>(null)
  const s = val ?? v.speed
  const commit = (x: number) => { setVal(null); ctl.speed(Math.round(Math.min(3, Math.max(0.5, x)) * 20) / 20) }
  return (
    <Menu label={`Playback speed ${fmtSpeed(v.speed)}`} trigger={<span class="speed-text tnum">{fmtSpeed(v.speed)}</span>} on={v.speed !== 1} disabled={!v.book} class="pill" popClass="pop-speed">
      <div class="pop-title">Playback speed</div>
      <div class="speed-row">
        <button class="icon-btn tonal" aria-label="Slower" onClick={() => commit(s - 0.05)}>−</button>
        <span class="speed-big tnum">{fmtSpeed(s)}</span>
        <button class="icon-btn tonal" aria-label="Faster" onClick={() => commit(s + 0.05)}>+</button>
      </div>
      <Range label="Speed" min={0.5} max={3} step={0.05} value={s} onInput={setVal} onChange={commit} />
      <div class="chips">
        {SPEEDS.map((x) => <button key={x} class={`chip ${Math.abs(x - v.speed) < 0.001 ? 'on' : ''}`} onClick={() => commit(x)}>{fmtSpeed(x)}</button>)}
      </div>
    </Menu>
  )
}

function SleepMenu() {
  const v = view.value
  const left = v.sleepAt != null ? v.sleepAt - serverNow() : null
  const eoc = !v.remote && sleep.value != null && 'chapterEnd' in sleep.value
  return (
    <Menu label={left != null ? `Sleep timer, ${fmtTime(left)} left` : 'Sleep timer'} on={left != null} disabled={!v.book}
      trigger={<><Icon name="sleep" size={20} />{left != null && <span class="trigger-note tnum">{eoc ? 'ch' : Math.ceil(left / 60000)}</span>}</>} popClass="pop-list">
      {(close) => (
        <>
          <div class="pop-title">Sleep timer{left != null && <span class="muted tnum"> {fmtTime(left)} left</span>}</div>
          {SLEEPS.map(([m, label]) => (
            <button key={label} class={`list-btn ${m === -1 && eoc ? 'on' : ''}`} onClick={() => { ctl.sleep(m); close() }}>
              {label}{m === -1 && eoc && <Icon name="check" size={18} />}
            </button>
          ))}
          {left != null && <button class="list-btn danger" onClick={() => { ctl.sleep(null); close() }}>Turn off</button>}
          <p class="pop-note">The sound fades out over the last 10 seconds.</p>
        </>
      )}
    </Menu>
  )
}

function EqMenu() {
  const v = view.value
  const bands = eq.value
  const set = (i: number, x: number, save: boolean) => setEq(bands.map((b, j) => (j === i ? x : b)), save)
  return (
    <Menu label={v.remote ? 'Equalizer (affects this browser only)' : 'Equalizer'} on={bands.some((b) => b !== 0)} disabled={!book.value || !!v.remote}
      trigger={<Icon name="eq" size={20} />} popClass="pop-eq">
      <div class="pop-title">Equalizer<span class="muted"> for this book</span></div>
      <div class="eq-bands">
        {bands.map((b, i) => (
          <div class="eq-band" key={i}>
            <span class="tnum">{b > 0 ? '+' : ''}{b}</span>
            <Range vertical label={`${BAND_LABELS[i]} Hz`} valueText={`${b} dB`} min={-12} max={12} step={1} value={b} onInput={(x) => set(i, x, false)} onChange={(x) => set(i, x, true)} />
            <span class="muted">{BAND_LABELS[i]}</span>
          </div>
        ))}
      </div>
      <button class="btn text sm" disabled={!bands.some((b) => b !== 0)} onClick={() => setEq([0, 0, 0, 0, 0], true)}>Reset</button>
    </Menu>
  )
}

function BoostMenu() {
  const v = view.value
  const db = boostMb.value / 100
  return (
    <Menu label={db > 0 ? `Volume boost +${db} dB` : 'Volume boost'} on={db > 0} disabled={!book.value || !!v.remote}
      trigger={<><Icon name="boost" size={20} />{db > 0 && <span class="trigger-note tnum">+{Math.round(db)}</span>}</>} popClass="pop-boost">
      <div class="pop-title">Volume boost<span class="muted"> for this book</span></div>
      <div class="boost-row">
        <Range label="Volume boost" valueText={`${db} dB`} min={0} max={12} step={0.5} value={db} onInput={(x) => setBoost(x * 100, false)} onChange={(x) => setBoost(x * 100, true)} />
        <span class="tnum boost-val">+{db.toFixed(1)} dB</span>
      </div>
      <p class="pop-note">Makes quiet recordings louder. High values can distort.</p>
    </Menu>
  )
}

let lastVolume = 1
function Volume() {
  const vol = volume.value
  return (
    <div class="volume">
      <button class="icon-btn" title={vol ? 'Mute' : 'Unmute'} aria-label={vol ? 'Mute' : 'Unmute'} onClick={() => { if (vol) { lastVolume = vol; volume.value = 0 } else volume.value = lastVolume || 1 }}>
        <Icon name={vol ? 'volume' : 'volumeOff'} size={20} />
      </button>
      <Range label="Volume" valueText={`${Math.round(vol * 100)} %`} min={0} max={1} step={0.01} value={vol} onInput={(x) => (volume.value = x)} />
    </div>
  )
}

// ---- Connect (W4): a popover over the device button, like Spotify's picker ----

/** The element the Connect popover hangs from; null = closed. */
const connectAt = signal<HTMLElement | null>(null)

function ConnectButton() {
  const r = remote.value
  const ref = useRef<HTMLButtonElement>(null)
  return (
    <button ref={ref} class={`icon-btn ${r ? 'on' : ''} ${connectAt.value ? 'open' : ''}`} title="Connect to a device" aria-label="Connect to a device" aria-haspopup="dialog"
      aria-expanded={!!connectAt.value} onClick={() => (connectAt.value = connectAt.value ? null : ref.current)}>
      <Icon name={r ? kindIcon(r.kind) : 'devices'} size={20} />
    </button>
  )
}

export function ConnectPopover() {
  const at = connectAt.value
  const mine = myId()
  const r = remote.value
  const { v, c } = current()
  const [busy, setBusy] = useState<string | null>(null)
  if (!at) return null
  const others = devices.value.filter((d) => d.online && d.id !== mine && d.id !== r?.id)
  const close = () => (connectAt.value = null)
  const pick = async (t: LiveDevice | 'here') => {
    setBusy(t === 'here' ? 'here' : t.id)
    try { await moveTo(t); close() } finally { setBusy(null) }
  }
  return (
    <Popover anchor={at} onClose={close} label="Connect to a device" class="pop-connect">
      <div class="pop-title">Connect</div>
      <div class="device-current">
        <div class="dc-top">
          <div class="dc-text">
            <div class="dc-name">{r ? r.name : 'This browser'}</div>
            {v.title && <div class="dc-what ellip"><Playing on={v.playing} />{c?.title ?? v.title}</div>}
          </div>
          <Icon name={r ? kindIcon(r.kind) : 'computer'} size={36} />
        </div>
        {r ? <RemoteVolume d={r} /> : <Volume />}
      </div>
      {((r && playable(v.book)) || others.length > 0) && <div class="pop-sub">Select a device</div>}
      {/* A book that isn't on the server can be controlled on the phone, not moved here. */}
      {r && playable(v.book) && (
        <button class="device" disabled={!!busy} onClick={() => pick('here')}>
          <Icon name="computer" /><span class="grow">This browser</span>{busy === 'here' && <span class="spinner" />}
        </button>
      )}
      {others.map((d) => (
        <button key={d.id} class="device" disabled={!!busy} onClick={() => pick(d)}>
          <Icon name={kindIcon(d.kind)} />
          <span class="grow">
            <span class="block">{d.name}</span>
            {d.state?.title && <span class="device-sub ellip">{d.state.playing ? 'Playing' : 'Paused'}: {d.state.title}</span>}
          </span>
          {busy === d.id && <span class="spinner" />}
        </button>
      ))}
      {!r && !others.length && <p class="pop-note">No other device is online. Open MFBooks on your phone, or <a href="#/devices" class="link" onClick={close}>link a phone</a>.</p>}
    </Popover>
  )
}

/** The other device's media volume, set with a live command (it reports the level in its state). */
function RemoteVolume({ d }: { d: LiveDevice }) {
  const [drag, setDrag] = useState<number | null>(null)
  const v = drag ?? d.state?.volume
  if (v == null) return null // an app too old to report it
  const send = (x: number) => { setDrag(null); cmd(d.id, 'volume', { volume: x }).catch((e: Error) => toast(`${d.name}: ${e.message}`)) }
  return (
    <div class="volume">
      <Icon name={v ? 'volume' : 'volumeOff'} size={20} />
      <Range label={`Volume on ${d.name}`} valueText={`${Math.round(v * 100)} %`} min={0} max={1} step={0.01} value={v} onInput={setDrag} onChange={send} />
    </div>
  )
}

/** Spotify's coloured "Playing on…" strip under the bar while another device plays. */
function RemoteStrip() {
  const r = remote.value
  const ref = useRef<HTMLButtonElement>(null)
  if (!r) return null
  return (
    <button ref={ref} class="remote-strip" onClick={() => (connectAt.value = ref.current)}>
      <Icon name={kindIcon(r.kind)} size={16} />{r.state?.playing ? 'Playing on' : 'Connected to'} {r.name}
    </button>
  )
}

// ---- Bottom bar ----

export function PlayerBar() {
  const { v, c } = current()
  const d = v.book
  const id = d?.id ?? v.remote?.state?.bookId
  return (
    <footer class="player" aria-label="Player">
      <div class="player-row">
        <div class="np-mini">
          {id && v.title ? (
            <>
              <button class="np-cover" title="Open the full-screen player" aria-label="Open the full-screen player" onClick={() => (fullPlayer.value = true)}>
                {d ? <Cover b={d} /> : <div class="cover ph" />}
                <span class="np-cover-hint"><Icon name="expand" size={18} /></span>
              </button>
              <div class="np-text">
                <span class="np-chapter ellip">{c?.title ?? v.title}</span>
                <span class="np-book ellip"><a href={`#/book/${id}`}>{v.title}</a>{d?.author && <span class="muted">, {d.author}</span>}</span>
              </div>
            </>
          ) : <span class="muted">Choose a book to start listening</span>}
        </div>
        <div class="player-center">
          <Transport />
          <SeekBar />
        </div>
        <div class="player-tools">
          <SpeedMenu />
          <SleepMenu />
          <EqMenu />
          <BoostMenu />
          <span class="tools-sep" />
          <ConnectButton />
          <button class={`icon-btn wide-only ${nowPanel.value ? 'on' : ''}`} title={nowPanel.value ? 'Hide Now playing' : 'Show Now playing'} aria-label="Now playing panel" aria-pressed={nowPanel.value} onClick={() => (nowPanel.value = !nowPanel.value)}>
            <Icon name="panel" size={20} />
          </button>
          <Volume />
          <button class="icon-btn" disabled={!v.book} title="Full-screen player" aria-label="Full-screen player" onClick={() => (fullPlayer.value = true)}><Icon name="fullscreen" size={20} /></button>
        </div>
      </div>
      <RemoteStrip />
    </footer>
  )
}

// ---- Right panel: Now playing ----

function ChapterList({ compact }: { compact?: boolean }) {
  const { v, chs, i } = current()
  const ref = useRef<HTMLOListElement>(null)
  // Scroll only the list (scrollIntoView would also scroll the full-screen overlay and the page around it).
  useEffect(() => {
    const ol = ref.current, li = ol?.querySelector<HTMLElement>('.current')
    if (!ol || !li) return
    const box = ol.scrollHeight > ol.clientHeight ? ol : (ol.closest('.now-scroll') as HTMLElement | null)
    if (box) box.scrollTop = li.offsetTop - box.clientHeight / 2 + li.offsetHeight / 2 // both boxes are the offsetParent
  }, [v.book?.id, i])
  return (
    <ol class={`chapters ${compact ? 'compact' : ''}`} ref={ref}>
      {chs.map((ch, k) => (
        <li key={k}>
          <button class={`chapter ${k === i ? 'current' : ''} ${k < i ? 'done' : ''}`} onClick={() => ctl.seekBook(ch.start)}>
            <span class="ch-num tnum">{k === i ? <Playing on={v.playing} /> : k + 1}</span>
            <span class="ch-title ellip">{ch.title}</span>
            <span class="ch-dur tnum">{fmtTime(ch.dur)}</span>
          </button>
        </li>
      ))}
    </ol>
  )
}

export function NowPlayingPanel() {
  const { v } = current()
  const d = v.book
  if (!d) return null
  const r = v.remote
  return (
    <aside class="now-panel" aria-label="Now playing">
      <div class="panel-head">
        <a class="title-s ellip" href={`#/book/${d.id}`}>{d.title}</a>
        <button class="icon-btn sm" title="Full-screen player" aria-label="Full-screen player" onClick={() => (fullPlayer.value = true)}><Icon name="fullscreen" size={18} /></button>
        <button class="icon-btn sm" title="Hide Now playing" aria-label="Hide Now playing" onClick={() => (nowPanel.value = false)}><Icon name="close" size={18} /></button>
      </div>
      <div class="now-scroll">
        <button class="now-cover" onClick={() => (fullPlayer.value = true)} aria-label="Open the full-screen player"><Cover b={d} /></button>
        <h2 class="now-title"><a href={`#/book/${d.id}`}>{d.title}</a></h2>
        <p class="muted">{d.author ?? 'Unknown author'}</p>
        <p class="now-left tnum">{fmtDuration(Math.max(0, d.durationMs - v.bookMs))} left in the book</p>
        {r && (
          <div class="now-device">
            <Icon name={kindIcon(r.kind)} size={20} />
            <span class="grow">{v.playing ? 'Playing' : 'Paused'} on {r.name}</span>
            {playable(d) && <button class="btn tonal sm" onClick={() => moveTo('here')}>Play here</button>}
          </div>
        )}
        <h3 class="card-heading">Chapters</h3>
        <ChapterList compact />
      </div>
    </aside>
  )
}

// ---- Full-screen player ----

export function FullPlayer() {
  const { v, c, chs, i } = current()
  const d = v.book
  useEffect(() => {
    const el = document.documentElement
    el.requestFullscreen?.().catch(() => {})
    const left = () => { if (!document.fullscreenElement) fullPlayer.value = false }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape' && !document.querySelector('.popover')) fullPlayer.value = false }
    document.addEventListener('fullscreenchange', left)
    addEventListener('keydown', esc)
    return () => {
      document.removeEventListener('fullscreenchange', left)
      removeEventListener('keydown', esc)
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    }
  }, [])
  if (!d) { fullPlayer.value = false; return null }
  const r = v.remote
  return (
    <div class="full" role="dialog" aria-modal="true" aria-label={`Full-screen player, ${d.title}`}>
      {d.cover && <div class="full-bg" style={{ backgroundImage: `url(${coverUrl(d)})` }} />}
      <header class="full-top">
        <button class="icon-btn" title="Exit full screen" aria-label="Exit full screen" onClick={() => (fullPlayer.value = false)}><Icon name="fullscreenExit" /></button>
        <a class="full-book ellip" href={`#/book/${d.id}`} onClick={() => (fullPlayer.value = false)}>{d.title}</a>
        {r && <span class="full-device"><Icon name={kindIcon(r.kind)} size={18} />{v.playing ? 'Playing' : 'Paused'} on {r.name}</span>}
      </header>
      <div class="full-main">
        <Cover b={d} class="full-cover" />
        <div class="full-side">
          <p class="full-count tnum">Chapter {i + 1} of {chs.length}</p>
          <h1 class="full-chapter">{c?.title ?? d.title}</h1>
          <p class="full-author">{d.author ?? 'Unknown author'}</p>
          <ChapterList />
        </div>
      </div>
      <footer class="full-controls">
        <SeekBar />
        <div class="full-row">
          <div class="full-tools left"><SpeedMenu /><SleepMenu /></div>
          <Transport big />
          <div class="full-tools right"><EqMenu /><BoostMenu /><ConnectButton /><Volume /></div>
        </div>
      </footer>
    </div>
  )
}
