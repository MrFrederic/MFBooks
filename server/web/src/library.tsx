// The sidebar (navigation + library index), Home, the Library page and the book page.
import { computed, signal } from '@preact/signals'
import { useEffect, useState } from 'preact/hooks'
import {
  api, type BookDetail, type BookSummary, books, bookPosition, details, fetchDetail, fmtAgo, fmtBytes, fmtDuration, fmtTime,
  forgetBook, patchBook, playable, toast,
} from './api'
import { Icon, type IconName } from './icons'
import { book, chapterAt, chaptersOf, ctl, moveTo, nowPlayingId, progressBase, remote, seekTrack, starts, unloadBook, view } from './player'
import { lastSync, push, status, sync } from './sync'
import { Bar, confirm, Cover, go, Menu, Playing, Ring, route, Where } from './ui'

// ---- Library state shared by the sidebar and the Library page ----

type Filter = 'all' | 'started' | 'finished' | 'hidden'
type Sort = 'recent' | 'title' | 'author' | 'added'
const FILTERS: [Filter, string][] = [['all', 'All'], ['started', 'Started'], ['finished', 'Finished'], ['hidden', 'Hidden']]
const SORTS: [Sort, string][] = [['recent', 'Recently played'], ['title', 'Title'], ['author', 'Author'], ['added', 'Recently added']]
const filter = signal<Filter>('all')
const sort = signal<Sort>('recent')
const query = signal('')
const libView = signal<'grid' | 'list'>('grid')

const matches = (b: BookSummary, f: Filter) => {
  if (f === 'hidden') return b.hidden
  if (b.hidden) return false
  switch (f) {
    case 'all': return b.state !== 'FINISHED' // as in the app: finished books live under Finished
    case 'started': return b.state === 'STARTED'
    case 'finished': return b.state === 'FINISHED'
  }
}
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
const played = (b: BookSummary) => b.lastPlayedAt ?? 0
const SORTERS: Record<Sort, (a: BookSummary, b: BookSummary) => number> = {
  recent: (a, b) => played(b) - played(a) || b.addedAt - a.addedAt,
  title: (a, b) => collator.compare(a.title, b.title),
  author: (a, b) => collator.compare(a.author ?? '', b.author ?? '') || collator.compare(a.title, b.title),
  added: (a, b) => b.addedAt - a.addedAt,
}
const shown = computed(() => {
  const q = query.value.trim().toLowerCase()
  return books.value
    .filter((b) => matches(b, filter.value))
    .filter((b) => !q || [b.title, b.author, b.narrator, b.series].some((s) => s?.toLowerCase().includes(q)))
    .sort(SORTERS[sort.value])
})
const hasHidden = computed(() => books.value.some((b) => b.hidden))

/** "3 h 20 min left", "Finished", or the length of a book not started yet. */
function statusLine(b: BookSummary) {
  if (b.state === 'FINISHED') return 'Finished'
  const p = bookPosition(b)
  return p > 0 ? `${fmtDuration(Math.max(0, b.durationMs - p))} left` : fmtDuration(b.durationMs)
}

function FilterSortMenu() {
  const active = filter.value !== 'all' || sort.value !== 'recent'
  return (
    <Menu label="Filter and sort" on={active} trigger={<Icon name="filter" size={20} />} popClass="pop-list">
      {(close) => (
        <>
          <div class="pop-title">Show</div>
          {FILTERS.filter(([f]) => f !== 'hidden' || hasHidden.value || filter.value === 'hidden').map(([f, label]) => (
            <button key={f} class={`list-btn ${filter.value === f ? 'on' : ''}`} onClick={() => { filter.value = f; close() }}>
              {label}{filter.value === f && <Icon name="check" size={18} />}
            </button>
          ))}
          <div class="pop-title">Sort by</div>
          {SORTS.map(([s, label]) => (
            <button key={s} class={`list-btn ${sort.value === s ? 'on' : ''}`} onClick={() => { sort.value = s; close() }}>
              {label}{sort.value === s && <Icon name="check" size={18} />}
            </button>
          ))}
        </>
      )}
    </Menu>
  )
}

function Search({ big }: { big?: boolean }) {
  return (
    <label class={`search ${big ? 'big' : ''}`}>
      <Icon name="search" size={18} />
      <input type="search" placeholder="Search books" aria-label="Search books" value={query.value} onInput={(e) => (query.value = e.currentTarget.value)} />
    </label>
  )
}

// ---- Sidebar ----

const SYNC: Record<string, [IconName, string]> = {
  synced: ['cloudDone', 'Synced'], syncing: ['sync', 'Syncing'], offline: ['cloudOff', 'Offline'], failed: ['error', 'Sync failed'],
}

function NavLink({ href, icon, label, on, trailing }: { href: string; icon: IconName; label: string; on: boolean; trailing?: preact.ComponentChildren }) {
  return (
    <a href={href} class={`nav ${on ? 'on' : ''}`} aria-current={on ? 'page' : undefined} title={label}>
      <Icon name={icon} size={22} /><span class="nav-label">{label}</span>{trailing}
    </a>
  )
}

export function Sidebar({ name, rail }: { name: string; rail: boolean }) {
  const p = route.value.page
  const [icon, syncLabel] = SYNC[status.value]!
  const playingId = nowPlayingId.value
  return (
    <nav class={`sidebar ${rail ? 'rail' : ''}`} aria-label="Main">
      <a href="#/" class="brand" title={name}><span class="brand-mark"><Icon name="play" size={18} /></span><span class="nav-label title-m ellip">{name}</span></a>
      <div class="nav-group">
        <NavLink href="#/" icon="home" label="Home" on={p === 'home'} />
        <NavLink href="#/library" icon="library" label="Library" on={p === 'library'} />
        <NavLink href="#/upload" icon="upload" label="Add books" on={p === 'upload'} />
      </div>
      {!rail && (
        <section class="side-lib" aria-label="Your books">
          <div class="side-lib-head">
            <h2 class="title-s">Your books</h2>
            <FilterSortMenu />
            <a class="icon-btn sm" href="#/library" title="Open the library" aria-label="Open the library"><Icon name="expand" size={18} /></a>
          </div>
          <Search />
          {filter.value !== 'all' && (
            <button class="filter-tag label-m" onClick={() => (filter.value = 'all')} aria-label={`Clear filter ${FILTERS.find((f) => f[0] === filter.value)?.[1]}`}>
              {FILTERS.find((f) => f[0] === filter.value)?.[1]}<Icon name="close" size={14} />
            </button>
          )}
          <ul class="side-list">
            {shown.value.map((b) => (
              <li key={b.id}>
                <a href={`#/book/${b.id}`} class={`side-item ${b.id === playingId ? 'now' : ''} ${route.value.id === b.id ? 'sel' : ''} ${playable(b) ? '' : 'dim'}`}>
                  <Cover b={b} class="xs" />
                  <span class="side-text">
                    <span class="ellip side-title">{b.title}</span>
                    <span class="ellip side-sub">{b.author ?? 'Unknown author'}</span>
                  </span>
                  {b.id === playingId ? <Playing on={view.value.playing} /> : <Where b={b} />}
                </a>
              </li>
            ))}
            {!shown.value.length && <li class="side-empty">{books.value.length ? 'No books match.' : 'No books yet.'}</li>}
          </ul>
        </section>
      )}
      <div class="nav-group foot">
        <button class={`nav sync ${status.value}`} onClick={() => sync()} title={`${syncLabel}. Last synced ${fmtAgo(lastSync.value)}. Click to sync now.`}>
          <Icon name={icon} size={22} class={status.value === 'syncing' ? 'spin' : ''} /><span class="nav-label">{syncLabel}<span class="nav-sub">{fmtAgo(lastSync.value)}</span></span>
        </button>
        <NavLink href="#/devices" icon="devices" label="Linked devices" on={p === 'devices'} />
        <NavLink href="#/settings" icon="settings" label="Settings" on={p === 'settings'} />
      </div>
    </nav>
  )
}

// ---- Cards ----

function PlayButton({ b, size = 'm' }: { b: BookSummary; size?: 'm' | 'l' }) {
  const v = view.value
  const isNow = v.book?.id === b.id || v.remote?.state?.bookId === b.id
  if (!playable(b) && !(isNow && v.remote)) return null
  const on = isNow && v.playing
  return (
    <button class={`fab ${size} ${on ? 'playing' : ''}`} aria-label={on ? `Pause ${b.title}` : `Play ${b.title}`}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); isNow ? ctl.toggle() : ctl.playHere(b.id) }}>
      <Icon name={on ? 'pause' : 'play'} size={size === 'l' ? 28 : 22} />
    </button>
  )
}

function Converting({ b }: { b: BookSummary }) {
  const c = b.cloud
  if (!c || (c.conversion !== 'converting' && c.conversion !== 'queued')) return null
  return (
    <span class="conv" title={c.conversion === 'queued' ? 'Waiting to convert to Opus' : 'Converting to Opus'}>
      <Ring value={c.conversion === 'queued' ? 0 : c.progress} size={22} />{c.conversion === 'queued' ? 'Queued' : `${Math.round(c.progress * 100)}%`}
    </span>
  )
}

function BookCard({ b }: { b: BookSummary }) {
  const p = b.durationMs ? bookPosition(b) / b.durationMs : 0
  return (
    <a class={`card ${nowPlayingId.value === b.id ? 'now' : ''} ${playable(b) ? '' : 'dim'}`} href={`#/book/${b.id}`}>
      <div class="card-cover">
        <Cover b={b} />
        <Converting b={b} />
        <PlayButton b={b} />
      </div>
      <div class="card-title ellip2">{b.title}</div>
      <div class="card-sub ellip"><Where b={b} />{b.author ?? 'Unknown author'}</div>
      {b.state === 'STARTED' && p > 0 && <Bar value={p} />}
    </a>
  )
}

// ---- Home: what to listen to next, never a second copy of the library ----

function ContinueHero({ b }: { b: BookSummary }) {
  useEffect(() => { if (!details.value[b.id]) fetchDetail(b.id).catch(() => {}) }, [b.id])
  const v = view.value
  const d = details.value[b.id]
  const isNow = v.book?.id === b.id || v.remote?.state?.bookId === b.id
  const pos = isNow ? v.bookMs : bookPosition(b)
  const chs = d ? chaptersOf(d) : []
  const ch = chs[chapterAt(chs, pos)]
  const r = isNow ? v.remote : null
  return (
    <section class="hero-continue" aria-label="Continue listening">
      {b.cover && <div class="hero-glow" style={{ backgroundImage: `url(${`/api/v1/books/${b.id}/cover?v=${b.updatedAt}`})` }} />}
      <a href={`#/book/${b.id}`} class="hero-cover-link"><Cover b={b} class="hero-cover" /></a>
      <div class="hero-body">
        <p class="hero-kicker">{isNow && v.playing ? (r ? `Playing on ${r.name}` : 'Now playing') : 'Pick up where you left off'}</p>
        <h1 class="hero-title"><a href={`#/book/${b.id}`}>{b.title}</a></h1>
        <p class="hero-author">{b.author ?? 'Unknown author'}</p>
        {ch && <p class="hero-chapter">{ch.title}</p>}
        <div class="hero-progress">
          <Bar value={b.durationMs ? pos / b.durationMs : 0} />
          <span class="tnum">{fmtDuration(Math.max(0, b.durationMs - pos))} left</span>
        </div>
        <div class="hero-actions">
          {playable(b) || r ? (
            <button class="btn filled big" onClick={() => (isNow ? ctl.toggle() : ctl.playHere(b.id))}>
              <Icon name={isNow && v.playing ? 'pause' : 'play'} size={24} />{isNow && v.playing ? 'Pause' : 'Resume'}
            </button>
          ) : <Uploading />}
          {r && <button class="btn tonal big" onClick={() => moveTo('here')}><Icon name="computer" size={22} />Play here</button>}
        </div>
      </div>
    </section>
  )
}

const Uploading = () => <p class="note"><Icon name="cloud" size={18} />Still uploading from the phone. It can play once every file has arrived.</p>

function Shelf({ title, list, more }: { title: string; list: BookSummary[]; more?: () => void }) {
  if (!list.length) return null
  return (
    <section class="shelf">
      <div class="shelf-head">
        <h2 class="section-title">{title}</h2>
        {more && <button class="btn text sm" onClick={more}>Show all</button>}
      </div>
      <div class="shelf-row">{list.map((b) => <BookCard key={b.id} b={b} />)}</div>
    </section>
  )
}

export function Home() {
  const all = books.value.filter((b) => !b.hidden)
  if (!all.length) return <EmptyLibrary />
  const now = nowPlayingId.value
  const started = all.filter((b) => b.state === 'STARTED').sort((a, b) => played(b) - played(a))
  const heroBook = all.find((b) => b.id === now) ?? started[0] ?? null
  const inProgress = started.filter((b) => b.id !== heroBook?.id).slice(0, 12)
  const fresh = all.filter((b) => b.state === 'NEW' && b.id !== heroBook?.id).sort(SORTERS.added).slice(0, 12)
  const finished = all.filter((b) => b.state === 'FINISHED' && b.id !== heroBook?.id).sort((a, b) => played(b) - played(a)).slice(0, 12)
  // A shelf is one row that fits the window; "Show all" opens the Library with the same filter.
  const show = (f: Filter, s: Sort) => () => { filter.value = f; sort.value = s; go('#/library') }
  return (
    <div class="page home">
      {heroBook ? <ContinueHero b={heroBook} /> : <h1 class="page-title">Start a book</h1>}
      <Shelf title="In progress" list={inProgress} more={show('started', 'recent')} />
      <Shelf title="Not started" list={fresh} more={show('all', 'added')} />
      <Shelf title="Finished" list={finished} more={show('finished', 'recent')} />
    </div>
  )
}

function EmptyLibrary() {
  return (
    <div class="page empty-state">
      <Icon name="book" size={56} />
      <h1 class="page-title">Your library is empty</h1>
      <p class="muted">Upload an audio file or a zip of a book folder, or use "Add to cloud" in the phone app.</p>
      <a class="btn filled big" href="#/upload"><Icon name="upload" />Add books</a>
    </div>
  )
}

// ---- Library page: the whole index, as a grid or a table ----

export function LibraryPage() {
  const list = shown.value
  const playingId = nowPlayingId.value
  return (
    <div class="page library">
      <header class="lib-head">
        <h1 class="page-title">Library</h1>
        <Search big />
      </header>
      <div class="lib-tools">
        <div class="chips" role="radiogroup" aria-label="Show">
          {FILTERS.filter(([f]) => f !== 'hidden' || hasHidden.value || filter.value === 'hidden').map(([f, label]) => (
            <button key={f} role="radio" aria-checked={filter.value === f} class={`chip ${filter.value === f ? 'on' : ''}`} onClick={() => (filter.value = f)}>
              {label}<span class="chip-count tnum">{books.value.filter((b) => matches(b, f)).length}</span>
            </button>
          ))}
        </div>
        <div class="grow" />
        <label class="select">
          <span class="muted">Sort</span>
          <select value={sort.value} onChange={(e) => (sort.value = e.currentTarget.value as Sort)}>
            {SORTS.map(([s, label]) => <option key={s} value={s}>{label}</option>)}
          </select>
          <Icon name="chevronDown" size={18} />
        </label>
        <div class="seg" role="radiogroup" aria-label="View">
          <button role="radio" aria-checked={libView.value === 'grid'} class={libView.value === 'grid' ? 'on' : ''} title="Grid" onClick={() => (libView.value = 'grid')}><Icon name="grid" size={20} /></button>
          <button role="radio" aria-checked={libView.value === 'list'} class={libView.value === 'list' ? 'on' : ''} title="List" onClick={() => (libView.value = 'list')}><Icon name="list" size={20} /></button>
        </div>
      </div>
      {!list.length ? <p class="muted pad-top">{books.value.length ? 'No books match.' : 'No books yet.'}</p>
        : libView.value === 'grid' ? <div class="grid">{list.map((b) => <BookCard key={b.id} b={b} />)}</div>
        : (
          <table class="lib-table">
            <thead><tr><th class="c-title">Title</th><th>Author</th><th>Length</th><th>Progress</th><th>Added</th></tr></thead>
            <tbody>
              {list.map((b) => (
                <tr key={b.id} class={`${b.id === playingId ? 'now' : ''} ${playable(b) ? '' : 'dim'}`} onClick={() => go(`#/book/${b.id}`)}>
                  <td class="c-title"><a href={`#/book/${b.id}`} class="row-title"><Cover b={b} class="xs" /><span class="ellip">{b.title}</span><Where b={b} /></a></td>
                  <td class="muted ellip">{b.author}</td>
                  <td class="muted tnum">{fmtDuration(b.durationMs)}</td>
                  <td class="muted">{statusLine(b)}</td>
                  <td class="muted tnum">{new Date(b.addedAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
    </div>
  )
}

// ---- Book page ----

const stripHtml = (s: string) => s.replace(/<br\s*\/?>|<\/p>/gi, '\n').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').trim()
const bookMsOf = (d: BookDetail, trackIndex: number, ms: number) => (starts(d)[trackIndex] ?? 0) + ms

function ConflictBanner({ d }: { d: BookDetail }) {
  const c = d.conflict!
  const here = d.progress ? bookMsOf(d, d.progress.trackIndex, d.progress.positionInTrackMs) : 0
  const theirs = bookMsOf(d, c.trackIndex, c.positionInTrackMs)
  // Keep one = push it as progress with base = the current seq (progress.seq).
  const keep = (trackIndex: number, positionInTrackMs: number) => {
    if (book.value?.id === d.id) seekTrack(trackIndex, positionInTrackMs)
    else push({ kind: 'progress', id: d.id, base: progressBase(d.id), data: { trackIndex, positionInTrackMs } })
    details.value = { ...details.value, [d.id]: { ...d, conflict: null, progress: { trackIndex, positionInTrackMs, updatedAt: Date.now(), seq: d.progress?.seq } } }
  }
  return (
    <div class="banner" role="alert">
      <Icon name="warning" />
      <p class="grow">This book was moved on two devices while offline. Keep this position at <b class="tnum">{fmtTime(here)}</b>, or {c.deviceName}'s at <b class="tnum">{fmtTime(theirs)}</b>?</p>
      <button class="btn tonal" onClick={() => keep(d.progress?.trackIndex ?? 0, d.progress?.positionInTrackMs ?? 0)}>Keep {fmtTime(here)}</button>
      <button class="btn filled" onClick={() => keep(c.trackIndex, c.positionInTrackMs)}>Use {fmtTime(theirs)}</button>
    </div>
  )
}

function BookMenu({ d }: { d: BookDetail }) {
  const setState = (state: BookDetail['state']) => {
    push({ kind: 'book', id: d.id, data: { state } })
    patchBook(d.id, { state })
  }
  const delCloud = async () => {
    if (!(await confirm('Delete the cloud copy?', `The audio files of "${d.title}" are removed from the server, so it leaves the web player. Its progress and stats stay, and so does the copy on your phone.`))) return
    try {
      if (book.value?.id === d.id) unloadBook()
      await api(`/books/${d.id}/files`, { method: 'DELETE' })
      forgetBook(d.id)
      go('#/')
    } catch (e: any) { toast(e.message) }
  }
  const delBook = async () => {
    if (!(await confirm('Delete this book everywhere?', `"${d.title}" is removed from the server and from every synced device, with its progress and bookmarks.`, 'Delete book'))) return
    try {
      if (book.value?.id === d.id) unloadBook()
      await api(`/books/${d.id}`, { method: 'DELETE' })
      forgetBook(d.id)
      go('#/')
    } catch (e: any) { toast(e.message) }
  }
  return (
    <Menu label="More actions" trigger={<Icon name="more" />} class="tonal lg" popClass="pop-list">
      {(close) => (
        <>
          {d.state !== 'FINISHED' && <button class="list-btn" onClick={() => { setState('FINISHED'); close() }}><Icon name="check" size={20} />Mark as finished</button>}
          {d.state !== 'NEW' && <button class="list-btn" onClick={() => { setState('NEW'); close() }}><Icon name="replay" size={20} />Mark as not started</button>}
          {d.cloud && <button class="list-btn danger" onClick={() => { close(); delCloud() }}><Icon name="cloudOff" size={20} />Delete cloud copy</button>}
          <button class="list-btn danger" onClick={() => { close(); delBook() }}><Icon name="delete" size={20} />Delete book</button>
        </>
      )}
    </Menu>
  )
}

const CONV: Record<string, string> = { none: 'Original files', queued: 'Waiting to convert', converting: 'Converting to Opus', done: 'Converted to Opus', failed: 'Conversion failed' }

export function BookView({ id }: { id: string }) {
  useEffect(() => { fetchDetail(id).catch((e) => toast(e.message)) }, [id])
  const [more, setMore] = useState(false)
  const d = details.value[id]
  const s = books.value.find((b) => b.id === id)
  if (!d) return <div class="page"><p class="muted pad-top">{s ? s.title : 'Loading…'}</p></div>
  if (!d.cloud) return (
    <div class="page empty-state">
      <h1 class="page-title">{d.title}</h1>
      <p class="muted">This book isn't on the server. Upload it from the phone app to listen here.</p>
      <a class="btn tonal" href="#/">Back to Home</a>
    </div>
  )

  const v = view.value
  const isNow = v.book?.id === id || v.remote?.state?.bookId === id
  const here = playable(d)
  const r = isNow ? remote.value : null
  const pos = isNow ? v.bookMs : bookPosition(d)
  const chs = chaptersOf(d)
  const cur = pos > 0 ? chapterAt(chs, pos) : -1
  const started = pos > 1000 && d.state !== 'FINISHED'
  const toggle = () => (isNow && (r || book.value) ? ctl.toggle() : ctl.playHere(id))
  const playChapter = (i: number) => {
    const c = chs[i]!
    if (r) ctl.seekBook(c.start)
    else if (here) ctl.playHere(id, { trackIndex: c.trackIndex, positionMs: c.startInTrackMs })
  }
  const desc = d.description && stripHtml(d.description)
  const c = d.cloud

  return (
    <div class="page book">
      {d.cover && <div class="book-glow" style={{ backgroundImage: `url(/api/v1/books/${d.id}/cover?v=${d.updatedAt})` }} />}
      {d.conflict && <ConflictBanner d={d} />}
      <header class="book-head">
        <Cover b={d} class="book-cover" />
        <div class="book-meta">
          {d.series && <p class="book-series">{d.series}{d.seriesIndex != null && `, book ${d.seriesIndex}`}</p>}
          <h1 class="book-title">{d.title}</h1>
          <p class="book-author">{d.author ?? 'Unknown author'}</p>
          {d.narrator && <p class="muted">Read by {d.narrator}</p>}
          <div class="tags">
            <span class="tag">{fmtDuration(d.durationMs)}</span>
            <span class="tag">{chs.length} {d.chapters.length ? 'chapters' : 'tracks'}</span>
            {d.year && <span class="tag">{d.year}</span>}
            {d.genre && <span class="tag">{d.genre}</span>}
            {d.state === 'FINISHED' && <span class="tag accent">Finished</span>}
          </div>
          {started && (
            <div class="book-progress">
              <Bar value={pos / (d.durationMs || 1)} />
              <span class="tnum">{fmtDuration(d.durationMs - pos)} left</span>
            </div>
          )}
          <div class="book-actions">
            {here || r ? (
              <button class={`btn filled big play-pill ${isNow && v.playing ? 'playing' : ''}`} onClick={toggle}>
                <Icon name={isNow && v.playing ? 'pause' : 'play'} size={26} />{isNow && v.playing ? 'Pause' : started ? 'Resume' : 'Play'}
              </button>
            ) : <Uploading />}
            {r && <button class="btn tonal big" onClick={() => moveTo('here')}><Icon name="computer" size={22} />Play here</button>}
            <BookMenu d={d} />
          </div>
          {r && <p class="note primary"><Icon name={r.kind === 'android' ? 'phone' : 'computer'} size={18} />{v.playing ? 'Playing' : 'Paused'} on {r.name}</p>}
        </div>
      </header>

      <div class="book-body">
        <section class="chapters-sec" aria-label={d.chapters.length ? 'Chapters' : 'Tracks'}>
          <h2 class="section-title">{d.chapters.length ? 'Chapters' : 'Tracks'}</h2>
          <ol class="chapters">
            {chs.map((ch, i) => (
              <li key={i}>
                <button class={`chapter ${i === cur ? 'current' : ''} ${i < cur ? 'done' : ''}`} disabled={!here && !r} onClick={() => playChapter(i)}>
                  <span class="ch-num tnum">{i === cur && isNow ? <Playing on={v.playing} /> : i + 1}</span>
                  <span class="ch-title ellip">{ch.title}</span>
                  <span class="ch-dur tnum">{fmtTime(ch.dur)}</span>
                </button>
              </li>
            ))}
          </ol>
        </section>
        <aside class="book-side">
          {desc && (
            <section class="side-card">
              <h2 class="card-heading">About</h2>
              <p class={`about ${more ? 'open' : ''}`}>{desc}</p>
              {desc.length > 320 && <button class="btn text sm" onClick={() => setMore(!more)}>{more ? 'Show less' : 'Show more'}</button>}
            </section>
          )}
          <section class="side-card">
            <h2 class="card-heading">Details</h2>
            <dl class="facts">
              {d.narrator && <><dt>Narrator</dt><dd>{d.narrator}</dd></>}
              {d.publisher && <><dt>Publisher</dt><dd>{d.publisher}</dd></>}
              {d.year && <><dt>Year</dt><dd>{d.year}</dd></>}
              <dt>Added</dt><dd>{new Date(d.addedAt).toLocaleDateString()}</dd>
              {d.lastPlayedAt && <><dt>Last played</dt><dd>{fmtAgo(d.lastPlayedAt)}</dd></>}
            </dl>
          </section>
          <section class="side-card">
            <h2 class="card-heading">Cloud copy</h2>
            {c ? (
              <dl class="facts">
                <dt>Storage</dt><dd>{fmtBytes(c.bytes)}, {c.files} {c.files === 1 ? 'file' : 'files'}</dd>
                <dt>Status</dt>
                <dd>
                  {c.ready < c.files ? `Uploading, ${c.ready} of ${c.files} files` : CONV[c.conversion]}
                  {c.conversion === 'converting' && <span class="conv-inline"><Ring value={c.progress} size={20} />{Math.round(c.progress * 100)}%</span>}
                  {c.conversion === 'failed' && c.error && <span class="err block">{c.error}</span>}
                </dd>
                <dt>Checked</dt><dd>{c.verified ? 'Every file matches its checksum' : 'Not yet'}</dd>
              </dl>
            ) : null}
          </section>
        </aside>
      </div>
    </div>
  )
}
