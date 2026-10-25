// Router, shell state and small widgets.
import { signal } from '@preact/signals'
import type { ComponentChildren } from 'preact'
import { render } from 'preact'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { type BookSummary, coverUrl, initials, toasts } from './api'
import { Icon } from './icons'

// ---- Hash router: #/, #/library, #/book/{id}, #/upload, #/devices, #/settings ----

const parse = () => {
  const [, page = '', id] = (location.hash.slice(1) || '/').split('/')
  return { page: page || 'home', id }
}
export const route = signal(parse())
addEventListener('hashchange', () => {
  route.value = parse()
  document.querySelector('.main')?.scrollTo({ top: 0 })
})
export const go = (hash: string) => { location.hash = hash }

// ---- Shell state ----

function stored<T>(k: string, def: T): T {
  try { return JSON.parse(localStorage.getItem(k) ?? 'null') ?? def } catch { return def }
}
const keep = <T,>(k: string, def: T) => {
  const s = signal<T>(stored(k, def))
  s.subscribe((v) => { try { localStorage.setItem(k, JSON.stringify(v)) } catch {} })
  return s
}
/** The right-hand "Now playing" panel, remembered per browser. */
export const nowPanel = keep('mfb.nowPanel', true)
/** Full-screen player (Spotify's "full screen" view). */
export const fullPlayer = signal(false)

// ---- Popovers: anchored to a trigger, rendered on top of everything ----

/**
 * Renders [children] into its own root at the end of <body>, out of any scrolling or clipping parent.
 * Not preact/compat's createPortal: importing compat makes every input's onChange fire on each input event
 * (React's semantics), which turned slider releases into a seek per pixel.
 */
function Portal({ children }: { children: ComponentChildren }) {
  const host = useMemo(() => document.createElement('div'), [])
  useLayoutEffect(() => { document.body.appendChild(host); return () => { render(null, host); host.remove() } }, [])
  useLayoutEffect(() => { render(<>{children}</>, host) })
  return null
}

export function Popover(p: { anchor: HTMLElement | null; onClose: () => void; children: ComponentChildren; label: string; class?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [at, setAt] = useState<{ left: number; top: number } | null>(null)
  useLayoutEffect(() => {
    const a = p.anchor?.getBoundingClientRect(), el = ref.current
    if (!a || !el) return
    const w = el.offsetWidth, h = el.offsetHeight, m = 8
    const left = Math.min(innerWidth - w - m, Math.max(m, a.left + a.width / 2 - w / 2))
    const above = a.top - h - m
    setAt({ left, top: above >= m ? above : Math.min(innerHeight - h - m, a.bottom + m) })
  }, [p.anchor])
  useEffect(() => {
    const out = (e: PointerEvent) => { const t = e.target as Node; if (!ref.current?.contains(t) && !p.anchor?.contains(t)) p.onClose() }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); p.onClose(); p.anchor?.focus() } }
    document.addEventListener('pointerdown', out, true)
    addEventListener('keydown', esc, true)
    ref.current?.querySelector<HTMLElement>('button, input, a')?.focus({ preventScroll: true })
    return () => { document.removeEventListener('pointerdown', out, true); removeEventListener('keydown', esc, true) }
  }, [])
  return (
    <Portal>
      <div ref={ref} role="dialog" aria-label={p.label} class={`popover ${p.class ?? ''}`}
        style={at ? { left: at.left, top: at.top } : { visibility: 'hidden', left: 0, top: 0 }}>
        {p.children}
      </div>
    </Portal>
  )
}

/** A control that opens a popover. `children` may be a render function receiving `close`. */
export function Menu(p: {
  label: string; trigger: ComponentChildren; on?: boolean; disabled?: boolean; class?: string; popClass?: string
  children: ComponentChildren | ((close: () => void) => ComponentChildren)
}) {
  const [open, setOpen] = useState(false)
  const btn = useRef<HTMLButtonElement>(null)
  const close = () => setOpen(false)
  return (
    <>
      <button ref={btn} class={`icon-btn ${p.on ? 'on' : ''} ${open ? 'open' : ''} ${p.class ?? ''}`} title={p.label} aria-label={p.label}
        aria-haspopup="dialog" aria-expanded={open} disabled={p.disabled} onClick={() => setOpen(!open)}>
        {p.trigger}
      </button>
      {open && <Popover anchor={btn.current} onClose={close} label={p.label} class={p.popClass}>{typeof p.children === 'function' ? p.children(close) : p.children}</Popover>}
    </>
  )
}

// ---- Confirm dialog ----

const dlg = signal<null | { title: string; body: string; ok: string; resolve: (v: boolean) => void }>(null)
export const confirm = (title: string, body: string, ok = 'Delete') =>
  new Promise<boolean>((resolve) => { dlg.value = { title, body, ok, resolve } })

export function ConfirmHost() {
  const d = dlg.value
  const cancel = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!d) return
    cancel.current?.focus()
    const k = (e: KeyboardEvent) => e.key === 'Escape' && close(false)
    addEventListener('keydown', k)
    return () => removeEventListener('keydown', k)
  }, [d])
  if (!d) return null
  const close = (v: boolean) => { dlg.value = null; d.resolve(v) }
  return (
    <div class="scrim" onClick={() => close(false)}>
      <div class="dialog" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <h2 class="headline-s">{d.title}</h2>
        <p class="body-m muted">{d.body}</p>
        <div class="actions">
          <button ref={cancel} class="btn text" onClick={() => close(false)}>Cancel</button>
          <button class="btn filled danger" onClick={() => close(true)}>{d.ok}</button>
        </div>
      </div>
    </div>
  )
}

export function Toasts() {
  return (
    <div class="toasts" role="status" aria-live="polite">
      {toasts.value.map((t) => <div key={t.id} class="toast">{t.text}</div>)}
    </div>
  )
}

// ---- Widgets ----

/** M3-style slider; `onInput` while dragging, `onChange` on release. */
export function Range(p: { min: number; max: number; step?: number; value: number; label: string; vertical?: boolean; disabled?: boolean; class?: string; valueText?: string; onInput?: (v: number) => void; onChange?: (v: number) => void }) {
  const span = p.max - p.min || 1
  const pct = Math.max(0, Math.min(100, ((p.value - p.min) / span) * 100))
  return (
    <input
      type="range" class={`range ${p.vertical ? 'vertical' : ''} ${p.class ?? ''}`} aria-label={p.label} aria-valuetext={p.valueText}
      min={p.min} max={p.max} step={p.step ?? 1} value={p.value} disabled={p.disabled}
      style={{ '--p': `${pct}%` } as Record<string, string>}
      onInput={(e) => p.onInput?.(+e.currentTarget.value)}
      onChange={(e) => p.onChange?.(+e.currentTarget.value)}
    />
  )
}

export function Switch(p: { checked: boolean; label: string; onChange: (v: boolean) => void }) {
  return (
    <label class="switch-row">
      <span>{p.label}</span>
      <input type="checkbox" role="switch" class="switch" checked={p.checked} onChange={(e) => p.onChange(e.currentTarget.checked)} />
    </label>
  )
}

export function Cover({ b, class: cls }: { b: Pick<BookSummary, 'id' | 'updatedAt' | 'cover' | 'title'>; class?: string }) {
  return b.cover
    ? <img class={`cover ${cls ?? ''}`} src={coverUrl(b)} alt="" loading="lazy" draggable={false} />
    : <div class={`cover ph ${cls ?? ''}`} aria-hidden="true"><span>{initials(b.title) || '?'}</span></div>
}

/** A cloud copy still arriving (U5); every listed book has one. */
export function Where({ b }: { b: BookSummary }) {
  if (b.cloud && b.cloud.ready < b.cloud.files) return <span class="where dim" title="Uploading to the cloud"><Icon name="cloud" size={14} /></span>
  return null
}

/** Three bars that move while something plays. */
export const Playing = ({ on = true }: { on?: boolean }) => <span class={`eq-anim ${on ? 'on' : ''}`} aria-hidden="true"><i /><i /><i /></span>

/** Circular progress (U4). */
export function Ring({ value, size = 40 }: { value: number; size?: number }) {
  const r = 16, c = 2 * Math.PI * r
  return (
    <svg class="ring" width={size} height={size} viewBox="0 0 40 40" aria-hidden="true">
      <circle cx="20" cy="20" r={r} class="ring-track" />
      <circle cx="20" cy="20" r={r} class="ring-fill" stroke-dasharray={`${c * Math.max(0.02, value)} ${c}`} transform="rotate(-90 20 20)" />
    </svg>
  )
}

export function Bar({ value }: { value: number }) {
  return <div class="pbar"><div style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%` }} /></div>
}
