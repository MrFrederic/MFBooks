// Read-along: the text view of the full player, and "Add text" on the book page.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { api, ApiError, type BookDetail, details, fetchDetail } from './api'
import { Icon } from './icons'
import { ctl, view } from './player'
import { SyncMap, type SyncDocJson, type SyncKind } from './syncmap'
import { onChanges } from './sync'
import { keep, Menu, Range } from './ui'

/** Cover or text in the full player, remembered per browser. */
export const textMode = keep('mfb.textMode', false)
const fontSize = keep('mfb.readerSize', 22)

const MIN_SIZE = 14, MAX_SIZE = 32
const MAX_SOURCE = 64 * 1024 * 1024
/** Readers notice a highlight that lags more than one that leads a little (§1.8). */
const LEAD_MS = 100

interface Block { k: string; s: string; sen?: number[]; em?: number[][] }
interface TextDoc { blocks: Block[] }

onChanges((cs) => { for (const c of cs) if (c.kind === 'text' && details.value[c.id]) fetchDetail(c.id).catch(() => {}) })

// ---- The document as HTML: built once, so the highlight can touch single spans without any diffing ----

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function inner(b: Block, from: number, to: number) {
  let out = '', at = from
  for (const [s, e] of b.em ?? []) {
    const a = Math.max(s!, from), z = Math.min(e!, to)
    if (a >= z) continue
    if (a > at) out += esc(b.s.slice(at, a))
    out += `<em>${esc(b.s.slice(a, z))}</em>`
    at = z
  }
  return out + esc(b.s.slice(at, to))
}

interface Built { html: string; starts: number[]; first: number[]; last: number[] }

/** [starts]: book position of each sentence. [first]/[last]: the sentences of the block holding each sentence. */
function build(doc: TextDoc): Built {
  let html = '', pos = 0
  const starts: number[] = [], first: number[] = [], last: number[] = []
  for (const b of doc.blocks) {
    if (b.k !== 'image' && b.s) {
      const cuts = b.sen?.length ? (b.sen[0] === 0 ? b.sen : [0, ...b.sen]) : [0]
      const a = starts.length, z = a + cuts.length - 1
      const spans = cuts.map((c, i) => {
        starts.push(pos + c); first.push(a); last.push(z)
        return `<span data-s="${a + i}">${inner(b, c, cuts[i + 1] ?? b.s.length)}</span>`
      })
      const tag = b.k === 'h1' || b.k === 'h2' || b.k === 'h3' ? b.k : b.k === 'quote' ? 'blockquote' : 'p'
      html += `<${tag} class="k-${esc(b.k)}">${spans.join('')}</${tag}>`
    }
    pos += b.s.length
  }
  return { html, starts, first, last }
}

function floorIn(a: number[], x: number) {
  let lo = 0, hi = a.length - 1, r = -1
  while (lo <= hi) {
    const m = (lo + hi) >> 1
    if (a[m]! <= x) { r = m; lo = m + 1 } else hi = m - 1
  }
  return r
}

// ---- Word spans exist only in the sentence being read ----

interface Word { el: HTMLElement; at: number }

function wordify(s: HTMLElement, sentenceStart: number): Word[] {
  const out: Word[] = []
  const nodes: Text[] = []
  const walk = document.createTreeWalker(s, NodeFilter.SHOW_TEXT)
  while (walk.nextNode()) nodes.push(walk.currentNode as Text)
  let at = sentenceStart
  for (const n of nodes) {
    const t = n.data, f = document.createDocumentFragment()
    let last = 0
    for (const m of t.matchAll(/\S+/g)) {
      if (m.index > last) f.append(t.slice(last, m.index))
      const el = document.createElement('span')
      el.textContent = m[0]
      f.append(el)
      out.push({ el, at: at + m.index })
      last = m.index + m[0].length
    }
    if (last < t.length) f.append(t.slice(last))
    at += t.length
    n.replaceWith(f)
  }
  return out
}

// ---- The reader ----

function SizeMenu() {
  const s = fontSize.value
  const set = (x: number) => (fontSize.value = Math.max(MIN_SIZE, Math.min(MAX_SIZE, x)))
  return (
    <Menu label="Reading settings" trigger={<span class="reader-aa">Aa</span>} class="pill" popClass="pop-speed">
      <div class="pop-title">Text size</div>
      <div class="speed-row">
        <button class="icon-btn tonal" aria-label="Smaller" onClick={() => set(s - 1)}>−</button>
        <span class="speed-big tnum">{s}</span>
        <button class="icon-btn tonal" aria-label="Larger" onClick={() => set(s + 1)}>+</button>
      </div>
      <Range label="Text size" min={MIN_SIZE} max={MAX_SIZE} step={1} value={s} onInput={set} />
    </Menu>
  )
}

type Loaded = { doc: TextDoc; map: SyncMap | null } | 'loading' | 'failed'

export function TextReader({ d }: { d: BookDetail }) {
  const [loaded, setLoaded] = useState<Loaded>('loading')
  const textSha = d.text?.textSha, syncSha = d.text?.syncSha
  useEffect(() => {
    let live = true
    setLoaded('loading')
    Promise.all([
      api<TextDoc>(`/books/${d.id}/text/content`),
      api<SyncDocJson>(`/books/${d.id}/text/sync`).catch(() => null), // no map: read without a highlight
    ]).then(([doc, sync]) => live && setLoaded({ doc, map: sync && SyncMap.of(sync, d.tracks) }), () => live && setLoaded('failed'))
    return () => { live = false }
  }, [d.id, textSha, syncSha])

  if (loaded === 'loading') return <div class="reader-pane"><p class="loading">Loading…</p></div>
  if (loaded === 'failed') return <div class="reader-pane"><p class="loading">Couldn't load the text.</p></div>
  return <Pane key={textSha} doc={loaded.doc} map={loaded.map} offsetMs={d.text?.offsetMs ?? 0} />
}

function Pane({ doc, map, offsetMs }: { doc: TextDoc; map: SyncMap | null; offsetMs: number }) {
  const built = useMemo(() => build(doc), [doc])
  const scroller = useRef<HTMLDivElement>(null)
  const article = useRef<HTMLElement>(null)
  const body = useRef<HTMLDivElement>(null)
  const bar = useRef<HTMLDivElement>(null)
  const [follow, setFollow] = useState(true)
  const [kind, setKind] = useState<SyncKind | null>(null)
  const following = useRef(true)
  following.current = follow
  const place = useRef(() => {})

  useLayoutEffect(() => {
    if (!map) return
    const sents = [...body.current!.querySelectorAll<HTMLElement>('[data-s]')]
    const calm = matchMedia('(prefers-reduced-motion: reduce)').matches
    let frame = 0, lastChar = -1, lastKind: SyncKind | null = null
    let from = -1, to = -1 // the highlighted sentences
    let pastEnd = 0 // sentences before this index are dimmed
    let words: Word[] = [], wordSent = -1, wordHtml = '', wordAt = -1
    let seenMs = NaN, seenAt = 0

    const clearWords = () => {
      if (wordSent >= 0) sents[wordSent]!.innerHTML = wordHtml
      words = []; wordSent = -1; wordAt = -1
    }

    /** Puts the bar on the highlighted sentences and, when following, the text at a third of the view. */
    const measure = (jump: boolean) => {
      const box = article.current, sc = scroller.current, b = bar.current
      if (!box || !sc || !b) return
      if (from < 0) { b.style.opacity = '0'; return }
      const a = sents[from]!.getClientRects(), z = sents[to]!.getClientRects()
      if (!a.length || !z.length) return
      const origin = box.getBoundingClientRect().top
      const top = a[0]!.top - origin
      b.style.opacity = '1'
      b.style.top = `${top}px`
      b.style.height = `${z[z.length - 1]!.bottom - a[0]!.top}px`
      if (!following.current) return
      const target = Math.max(0, top + box.offsetTop - sc.clientHeight * 0.32)
      const far = Math.abs(target - sc.scrollTop) > sc.clientHeight * 2
      sc.scrollTo({ top: target, behavior: jump || far || calm ? 'auto' : 'smooth' })
      if (far) requestAnimationFrame(() => { if (following.current) sc.scrollTop = Math.max(0, top + box.offsetTop - sc.clientHeight * 0.32) }) // content-visibility has settled by now
    }
    place.current = () => measure(true)

    const setPast = (end: number) => {
      for (let i = Math.min(pastEnd, end); i < Math.max(pastEnd, end); i++) sents[i]!.classList.toggle('past', i < end)
      pastEnd = end
    }

    const setCurrent = (a: number, z: number) => {
      if (a === from && z === to) return false
      for (let i = from; i >= 0 && i <= to; i++) sents[i]!.classList.remove('cur')
      from = a; to = z
      for (let i = a; a >= 0 && i <= z; i++) sents[i]!.classList.add('cur')
      return true
    }

    const update = (char: number, k: SyncKind) => {
      if (k !== lastKind) { lastKind = k; setKind(k) }
      if (k === 'none') {
        clearWords()
        if (setCurrent(-1, -1)) measure(false)
        return
      }
      const i = Math.max(0, floorIn(built.starts, char))
      const a = k === 'approx' ? built.first[i]! : i
      const z = k === 'approx' ? built.last[i]! : i
      if (setCurrent(a, z)) { setPast(a); measure(lastChar < 0) }
      if (k !== 'word') { clearWords(); return }
      if (wordSent !== i) {
        clearWords()
        wordSent = i; wordHtml = sents[i]!.innerHTML
        words = wordify(sents[i]!, built.starts[i]!)
      }
      let w = words.length - 1
      while (w > 0 && words[w]!.at > char) w--
      if (w === wordAt) return
      if (wordAt >= 0) words[wordAt]?.el.classList.remove('word')
      words[w]?.el.classList.add('word')
      wordAt = w
    }

    const tick = (now: number) => {
      frame = requestAnimationFrame(tick)
      const v = view.peek()
      if (v.bookMs !== seenMs) { seenMs = v.bookMs; seenAt = now }
      const ahead = v.playing ? Math.min(1500, now - seenAt) * v.speed : 0
      const { char, kind: k } = map.at(v.bookMs + ahead + offsetMs + LEAD_MS)
      if (char === lastChar && k === lastKind) return
      update(char, k)
      lastChar = char
    }
    frame = requestAnimationFrame(tick)

    const ro = new ResizeObserver(() => measure(true)) // window, or text size
    ro.observe(body.current!)
    return () => { cancelAnimationFrame(frame); ro.disconnect(); clearWords() }
  }, [built, map, offsetMs])

  useEffect(() => { if (follow) place.current() }, [follow])

  const leave = () => setFollow(false)
  const click = (e: MouseEvent) => {
    if (!map || !getSelection()?.isCollapsed) return
    const s = (e.target as HTMLElement).closest<HTMLElement>('[data-s]')
    if (!s) return
    ctl.seekBook(Math.max(0, map.msAt(built.starts[+s.dataset.s!]!) - offsetMs))
    setFollow(true)
  }

  return (
    <div class="reader-pane">
      <div class="reader-scroll" ref={scroller} tabIndex={0} onWheel={leave} onTouchMove={leave}
        onPointerDown={(e) => { if (e.target === e.currentTarget) leave() }}
        onKeyDown={(e) => { if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'].includes(e.key)) leave() }}>
        <article ref={article} class={`reader ${map ? 'synced' : ''}`} style={{ '--reader-size': `${fontSize.value}px` }} onClick={click}>
          <div class="reader-top">
            {kind === 'approx' && <span class="tag">Approximate</span>}
            {kind === 'none' && <span class="tag">Not in the text</span>}
            <SizeMenu />
          </div>
          <div class="reader-bar" ref={bar} />
          <div class="reader-body" ref={body} dangerouslySetInnerHTML={{ __html: built.html }} />
        </article>
      </div>
      {map && !follow && (
        <button class="btn filled reader-back" onClick={() => setFollow(true)}><Icon name="arrowDown" size={20} />Back to narration</button>
      )}
    </div>
  )
}

export function TextToggle() {
  const on = textMode.value
  return (
    <div class="text-toggle">
      <button class={on ? '' : 'on'} aria-pressed={!on} onClick={() => (textMode.value = false)}>Cover</button>
      <button class={on ? 'on' : ''} aria-pressed={on} onClick={() => (textMode.value = true)}>Text</button>
    </div>
  )
}

// ---- Book page ----

const REASONS: Record<string, string> = {
  drm: 'This book is DRM-protected. MFBooks can only read DRM-free files.',
  empty: "MFBooks couldn't find any book text in this file.",
  too_large: 'This file is over the 64 MB limit.',
  unsupported: "This file type isn't supported. Use FB2, EPUB or TXT.",
  exists: 'This book already has text.',
}

const LANGS: Record<string, string> = { en: 'English', ru: 'Russian' }

export function TextCard({ d }: { d: BookDetail }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [over, setOver] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const t = d.text

  const add = async (f: File | undefined) => {
    if (!f || busy) return
    setError(null)
    if (f.size > MAX_SOURCE) return setError(REASONS.too_large!)
    setBusy(true)
    try {
      await api(`/books/${d.id}/text/source?name=${encodeURIComponent(f.name)}`, { method: 'PUT', raw: f, headers: { 'Content-Type': 'application/octet-stream' } })
      await fetchDetail(d.id)
    } catch (e: any) {
      setError((e instanceof ApiError && e.reason && REASONS[e.reason]) || e.message)
    } finally { setBusy(false) }
  }

  if (t) return (
    <section class="side-card">
      <h2 class="card-heading">Text</h2>
      <dl class="facts">
        <dt>Language</dt><dd>{LANGS[t.lang] ?? t.lang}</dd>
        {t.words > 0 && <><dt>Words</dt><dd class="tnum">{t.words.toLocaleString()}</dd></>}
        <dt>Sync</dt><dd>{t.tier === 'aligned' ? `Matched, ${Math.round(t.coverage * 100)}%` : 'Approximate'}</dd>
        <dt>File</dt><dd>{t.sourceName}</dd>
      </dl>
      {t.tier !== 'aligned' && <p class="body-s muted text-note">Accurate sync comes from the phone app.</p>}
    </section>
  )

  return (
    <section class={`side-card text-drop ${over ? 'over' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); add(e.dataTransfer?.files[0]) }}>
      <h2 class="card-heading">Text</h2>
      <button class="btn tonal" disabled={busy} onClick={() => input.current?.click()}>
        {busy ? <span class="spinner" /> : <Icon name="add" size={20} />}Add text
      </button>
      <input ref={input} type="file" hidden accept=".fb2,.zip,.epub,.txt" onChange={(e) => { add(e.currentTarget.files?.[0]); e.currentTarget.value = '' }} />
      <p class={`body-s text-note ${error ? 'err' : 'muted'}`}>
        {busy ? 'Reading the book…' : error ?? 'Drop an FB2, EPUB or TXT here. Accurate sync comes from the phone app.'}
      </p>
    </section>
  )
}
