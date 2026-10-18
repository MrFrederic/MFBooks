# MFBooks — Design Document

**Status:** Draft v3 · 2026-10-17 (v3: .nfo/.cue/image merge; v2: Expressive UI + colour source, Opus conversion with sidecar metadata, next/previous → seconds skip)
**Audience:** an AI coding agent (or developer) building MFBooks from scratch.
**Source of requirements:** reverse-engineering of *Smart AudioBook Player* 11.8.2 (`ak.alizandro.smartaudiobookplayer`), plus the owner's two asks: a **Material 3 UI in the style of YouTube Music** and **first-class Android Auto**.

> **Clean-room rule.** This document describes *behaviour* observed in the reference app. Do not copy its code, resources, strings, icons or file formats verbatim. The reference app uses the proprietary BASS audio library (un4seen); MFBooks must not. Everything here is meant to be rebuilt with open, first-party Android libraries.

---

## 0. TL;DR for the implementing agent

Build a **local-files audiobook player** for Android in **Kotlin + Jetpack Compose (Material 3 Expressive) + Media3**:

1. The user picks one or more **root folders** (Storage Access Framework). Every sub-folder is a **book**. Files in a folder are the book's tracks.
2. The app scans, extracts metadata/covers/**chapters**, and shows a **library** (New / Started / Finished).
3. Playback runs in a Media3 **`MediaLibraryService`** with ExoPlayer. That one service powers the notification, lock screen, Bluetooth, Wear OS **and Android Auto**.
4. Progress is saved per book, continuously. Per-book: speed, volume boost, EQ, skip-silence, skip intro/outro.
5. Extras: sleep timer with fade-out and shake-to-extend, auto-rewind after pause, bookmarks, history (undo accidental seeks), statistics, widgets.

Section 9 has the phased plan. Phase 1 alone must give a usable app with Android Auto working.

---

## 1. Reverse-engineering findings (what the reference app is)

### 1.1 Identity

| Item | Value |
|---|---|
| Package | `ak.alizandro.smartaudiobookplayer` |
| Version | 11.8.2 (code 301182), "patches v1.22.0" build (licence check patched) |
| minSdk / targetSdk | 30 / 37 |
| APK SHA-256 | `11463d17f0f623115ffee2de6aa961af1b9c2053bd3b4a3be7579c40bf7af866` |
| Code | ~270 app classes (ProGuard-obfuscated), Java/Kotlin, legacy Views + AppCompat + some Material Components. Not Compose. |
| Audio | Two engines: **BASS** (native, `libbass*.so` + `bass_fx` for tempo/pitch + `bassenc_opus`) and Android **`MediaPlayer`** fallback. Setting "Decoder: App 1 / App 2 / System". |
| Noise reduction | `librnnoise.so` (Xiph RNNoise) as a DSP on the BASS stream. |
| Tags | `libtags.so` (BASS TAGS) + Java ID3 parser + MP4 atom parser (`chpl`) + charset detection. |
| Casting | Google Cast framework + embedded **NanoHTTPD** server streaming to the Cast device. |
| Billing | Google Play Billing (Basic vs Full, 30-day trial). **MFBooks has no licensing.** |

### 1.2 Components (from the manifest)

- **`PlayerActivity`**: launcher; also handles `MEDIA_PLAY_FROM_SEARCH` (voice "play X").
- `LibraryActivity`, `BookQueueActivity`, `BookmarksActivity`, `CharactersActivity`, `HistoryActivity`, `SubtitlesActivity`, `PlaybackStatistics[ForBook]Activity`, `HelpActivity`, `ConvertMp3ToOpusActivity`, cover/description search activities (web search plus "paste from Gemini"), and six settings activities (Playback, Sleep, Full-version/UI, Troubleshooting, Advanced, Library).
- **`PlayerService`**: foreground `mediaPlayback` service. It is exported and accepts intent actions, which is how Tasker integration works: `ActionPlayPause, Play, Pause, RewindSmall/Big, FwdSmall/Big, PrevFile, NextFile, AddBookmark, Enable/DisableSleepTimer, Exit`, plus internal `Rewind05…Rewind300`, `Fwd10…Fwd120`, `NextBookmark`, `PrevBook`, `ToggleSpeed`.
- **`MyMediaBrowserService`**: legacy `MediaBrowserServiceCompat` used by Android Auto (`automotive_app_desc.xml` → `<uses name="media"/>`).
- Three home-screen widgets (micro / small / large).

### 1.3 Existing Android Auto support, and why it falls short

The decompiled browse tree is minimal:

```
__ROOT__
 ├─ "New"       (__NEW__)       → books in state New      (playable, cover as inline Bitmap)
 ├─ "Started"   (__STARTED__)   → books in state Started
 └─ "Finished"  (__FINISHED__)  → books in state Finished
```

- Books are leaf `PLAYABLE` items. There is **no chapter browsing, no "Continue listening" entry, and no progress indicator**.
- Artwork is sent as **in-memory Bitmaps** rather than content URIs, which is slow and can exceed binder limits.
- There are no content-style hints (grid vs list).
- Custom actions are limited to Rewind, Fast-forward, Add bookmark and Change speed. The speed button only appears when a setting is on.
- `PlayerService` handles play-from-search and play-from-media-id by relaying intents.
- The phone UI blocks the Library while AA is connected ("The Library is unavailable while the app is connected to Android Auto").
- The sleep timer is turned off in AA by default (`turnOffSleepTimerInAndroidAuto=true`).

MFBooks replaces all of this with a Media3 `MediaLibraryService` (§6).

### 1.4 Library model (observed)

- **Root folders**: one or more SAF tree URIs (`takePersistableUriPermission`). Rules enforced:
  - a root folder can't be the whole storage;
  - one root can't be inside another;
  - root names must be unique (used for USB backup).
- **Book = a sub-folder** under a root. Recommended hierarchy: `Audiobooks/Author/Title/*.mp3`. Loose audio files directly in a root trigger a warning ("move them into folders", including `*.m4b`).
- **Hidden folders**: a `.nomedia` file in a folder hides it.
- **Merge folders**: combine several folders (CD1, CD2, CD3) into one book.
- **Book states**: `New → Started → Finished`, changed automatically and through a long-press menu. Library tabs: **All, New, Started, Finished**. There is also an optional **folder view** and an optional **Started books in the navigation drawer**.
- **Sort**: by path, title, recently played, length, date added. Started books have their own sort.
- **Book queue**: an explicit "play next" list of books.
- **Full scan** vs incremental scan.

**Per-book files written into the book folder**, which makes progress survive reinstalls and syncing:

| File | Content |
|---|---|
| `position.sabp.dat` | Java-serialized `BookData` (current file, position, speed, EQ, boost, skip settings, state, etc.) |
| `bookmarks.sabp.xml` | `<bookmark><title/><description/><fileName/><filePosition/></bookmark>` |
| `characters.sabp.xml` | character name + description list |
| `EmbeddedCover.jpg` | cover extracted from tags |
| `info.txt` | book description |
| `!Smart AudioBook Player Statistics/statistics.xml` (in root) | listening statistics: `book`, `path`, `root`, `time`, `note` |

**`BookData` fields** (reference for MFBooks' schema): folderUri, cachePath, rootCachePath, coverName, embeddedCoverFromFileName, currentFileIndex, fileName, filePosition, fileDuration, numberOfFiles, totalPlaybackTimeCache, currentPlaybackTimeCache, lastPlaybackTime, addTime, bookState, bookQueuePosition, playbackSpeed, savedPlaybackSpeed (for toggle-speed), boostVolume, equalizerLevels, skipSilence, skipStartEndSettings, repeatSettings, showId3Titles, infoTxtExists, lastOpenedTextFile, history (list of `BookHistoryNode`).

### 1.5 Formats and chapters (observed)

- **Audio**: mp3, ogg/oga, aac, mp4/m4a/m4b, opus, wav, flac, amr/awb, mpga, aiff, wv, 3gp, mka/mkv, webm/weba, avi/m4v (audio track), optional wma.
- **Chapter sources**, in priority order per file:
  1. MP4/M4B chapters (`chpl` atom / QuickTime chapter track);
  2. MP3 ID3v2 `CHAP` frames;
  3. **OverDrive** MP3s (`TXXX` frame containing `OverDrive MediaMarkers` XML with `<Markers>`);
  4. Ogg/Opus Vorbis-comment `CHAPTERxxx` / `CHAPTERxxxNAME`;
  5. a `.cue` sheet in the folder;
  6. Libby `openbook.json` (`nav.toc[]` with `title` and `path#offset`);
  7. `libby.txt`.

  If a file has no chapters, the **file itself is the chapter**, titled by ID3 title or filename (user toggle "ID3 titles"). Text tags go through charset detection (cp1251 and friends).
- A **global chapter** has: name, file, position in file, position in book, length. This is what drives "position in book".
- **Covers**: folder images (jpg/jpeg/png/gif/webp), embedded art, or user-picked. The player shows a blurred cover background.
- **Subtitles**: `*.srt` next to the audio file, shown in sync.
- **Text/PDF**: open `*.txt`/`*.pdf` files from the book folder.

### 1.6 Playback behaviour (observed, with exact values)

**Seek buttons.** Small and big rewind/forward with configurable sizes. Long-press on rewind/forward means "very big rewind". Long-press on prev/next file opens the file picker. Available steps:

- rewind: 5, 10, 15, 20, 30, 45, 60, 300 s, or previous file;
- forward: 10, 15, 20, 30, 45, 60, 120 s, next file, add bookmark, next bookmark, previous book, or toggle speed.

**Headset multi-press.** Single, double, triple and quadruple press map to any of the actions above. There are separate mappings for wired and Bluetooth.

**Auto-rewind on resume** (`autoRewind`: Off / Small / Medium, default Medium):

| Pause length | Small | Medium |
|---|---|---|
| < 1 s | 1 s | 1 s |
| < 10 s | 1 s | 2 s |
| < 1 min | 2 s | 5 s |
| < 2 min | 5 s | 10 s |
| < 5 min | 7 s | 15 s |
| < 10 min | 10 s | 20 s |
| < 20 min | 12 s | 25 s |
| ≥ 20 min | 15 s | 30 s |

**Per-book playback speed.** Long-press sets the default. "Toggle speed" swaps between 1.0 and the saved speed. Other per-book options:

- **Skip silence** (per book, with a default);
- **Skip start/end**: fixed seconds trimmed from every file, for repeated intros and outros;
- **Repeat**: current file ×2/×3/×4/×5/∞ or whole book ×2…∞, for language learning;
- **Volume boost** (per book, step control; long-press lowers it).

**Equalizer** (per book, with a long-press default): 5 bands at **60, 230, 910, 4000, 14000 Hz**, each **−15…+15 dB**. Also **balance** (−1…1), **mono**, **pitch**, and **noise reduction** (RNNoise).

**Audio focus and interruptions:**

- pause during messages/navigation prompts (`pauseOnMessage`, default on);
- stop when another player starts (`stopWhenOthersPlaying`, default on);
- resume after a call (`resumePlaybackAtEndOfCall`, default on);
- play when a wired headset connects (`headsetConnectPlay`, default off);
- standard pause on "becoming noisy".

**Safety stops:**

- `forceStop`: stop after N hours of continuous play (default 2 h);
- `forceExit`: exit after long inactivity.

**End of book**: do nothing, switch to the next book, or switch to the next book and play it with the same speed, volume and EQ.

**Prevent word clipping** (some Bluetooth speakers cut the first syllable): plays background noise at level Off/7/14/28/56.

**Sleep timer** (`sleepTime` in minutes: 5, 10 (default), 15, 20, 30, 45, 60, 75, 90, or **end of current file/chapter**):

- the timer counts playback time;
- during the **last 60 s, volume fades linearly to 0**, then playback pauses;
- a "fade-out started" sound plays ~50 s before the end, and a "timer reset" sound plays on reset. Each has a volume of Off/Quiet/Medium/Loud(/Very loud);
- **shake to reset**: an accelerometer delta above `shakeForce` (Very low…Very high = 1, 3, 6, 12, 18) restarts the timer. `trackMotion` sets when shaking counts: Always / Only during fade-out / Never;
- a **schedule** (time window) turns the timer on and off automatically;
- the timer can be switched off automatically in Android Auto;
- each sleep event is written to the history.

**Rotate gesture**: flip the phone face-down to pause and face-up to resume.

**History**: every Prev/Next/Start/Pause/Back/Fwd/ManualSetPosition/Sleep event is stored with its file, position and time. The user can jump back to undo accidental taps.

**Bookmarks**: title, note, file and position. "Quick bookmark" comes from the notification, a headset press or Tasker.

**Statistics**: listening time per book, per day/month/year, kept even after the book is deleted. It can be switched off.

### 1.7 Library maintenance extras (observed)

- **Delete** the current file, finished files, bookmarked files, or a whole folder, with confirmation.
- **Backup to USB drive**: a one-way mirror of the library to a selected folder, with an add/update/delete diff preview.
- **Convert MP3 → Opus 32 kb/s** in the background, only while charging or above a battery threshold, to save space.
- **Cover search** (Bing Images / Google) and **description search** (Goodreads / Google) in a WebView. "Description/Cover from Gemini" is just an instruction screen plus paste-from-clipboard. Descriptions are saved as `info.txt`.
- **Characters list** per book (import from a finished book in the same series).

### 1.8 UI settings (observed)

- Theme: Light / Dark / Black.
- Toolbar buttons (each can be shown or hidden):
  - core: boost, EQ, speed, characters, bookmarks, Cast, repeat, rotate, ID3 titles, skip start/end, convert;
  - library: search, sort, folder view, book queue.
- Progress format: Files, Time, Time %, Time left, Time % left.
- Toggles: chapters on/off, subtitles on/off, filenames, quick-scroll on the right edge.
- Behaviour: lock portrait, keep screen on while charging ("car mode"), cover in notification/widget, back-button action (Minimize / Exit / Library), "lock buttons" (hide everything except play/pause).

---

## 2. Product goals for MFBooks

### 2.1 Goals
1. **Parity** with the reference app's core listening features (§1.4–1.6).
2. **Material 3 Expressive UI** modelled on **YouTube Music**: Expressive components and motion (§7.1), persistent mini-player, expandable full-screen player, big artwork, bottom navigation, bottom-sheet tabs. Colours come from either the **phone's Material You palette or the book cover**, chosen by the user.
3. **Excellent Android Auto**: browsable library with continue-listening, chapters, progress badges, search, voice, and car-safe custom actions.
4. Local-first: no account, no network required, no analytics, no ads, no billing.
5. Progress is robust: it survives app kill, reboot, reinstall (via sidecar files) and library re-scans.

### 2.2 Non-goals (v1)
- Streaming services, cloud sync, podcasts, OPDS.
- Copying the reference app's file formats bit-for-bit. Importing its plain-XML files is P2.
- Licence/trial logic, donation flows, Gemini/web search helpers.

---

## 3. Architecture

### 3.1 Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x | — |
| UI | Jetpack Compose + **Material 3 Expressive** (`androidx.compose.material3` 1.4+ with `@ExperimentalMaterial3ExpressiveApi`; see §7.1) | required by owner |
| Playback | **AndroidX Media3**: `media3-exoplayer`, `media3-session` | one session serves notification, BT, Wear and AA |
| DB | **Room** | library, progress, bookmarks, history, stats |
| Prefs | **DataStore (Preferences)** | app settings |
| Images | **Coil 3** | covers |
| Colours | `dynamicLightColorScheme`/`dynamicDarkColorScheme` (system palette) **or** `material-kolor` scheme seeded from the cover; user chooses (§7.1) | YT Music look |
| Background work | **WorkManager** | library scan |
| Metadata | Media3 `MetadataRetriever` + small hand-written parsers for chapters (§4.3) | — |
| Extra codecs | Media3 **FFmpeg decoder extension** (P2) | wma, ape, wv, alac on weak devices |
| DI | none; manual singletons in `Application` | ponytail: add Hilt only if it hurts |
| Min/target SDK | **minSdk 29**, targetSdk = latest stable | 29 gives scoped-storage-era SAF and Media3 AA without legacy work |

Single module `:app`. Split modules only if build time becomes a problem.

### 3.2 Package layout

```
app.mfbooks
 ├─ MFBooksApp.kt              // singletons: db, prefs, scanner, player connection
 ├─ data/                      // Room entities, DAOs, DataStore, sidecar JSON
 ├─ library/                   // SAF scanner, metadata + chapter parsers, cover resolver
 ├─ playback/
 │   ├─ PlaybackService.kt     // MediaLibraryService + ExoPlayer
 │   ├─ LibraryTree.kt         // browse tree for AA + phone
 │   ├─ BookPlaylist.kt        // book → List<MediaItem> (chapters as clipped items)
 │   ├─ ProgressSaver.kt
 │   ├─ SleepTimer.kt
 │   ├─ AudioEffects.kt        // EQ, boost, balance/mono processors
 │   └─ Commands.kt            // custom SessionCommands + intent actions (Tasker)
 ├─ ui/
 │   ├─ theme/                 // M3 theme, dynamic-from-cover
 │   ├─ home/ library/ book/ player/ settings/ stats/
 │   └─ components/            // MiniPlayer, BookCard, ChapterRow, ...
 └─ widget/                    // Glance widgets (P2)
```

### 3.3 Process model

```
 Compose UI ──MediaController──▶ PlaybackService (MediaLibraryService)
     │                               │  ExoPlayer + effects
     │                               │  ProgressSaver → Room (+ sidecar)
     ▼                               ▼
   Room (Flow) ◀──── LibraryScanner (WorkManager) ────▶ SAF tree URIs
                                     ▲
 Android Auto / BT / Wear / notif ───┘ (all talk to the same MediaSession)
```

- The UI never touches ExoPlayer directly. It uses `MediaController` plus Room flows.
- Custom features (sleep timer, bookmark, speed toggle, history) are **custom `SessionCommand`s**, so the phone UI, AA, Tasker intents and widgets all go through the same path.

---

## 4. Data model and library

### 4.1 Room schema

```kotlin
@Entity data class RootFolder(@PrimaryKey val uri: String, val name: String, val addedAt: Long)

@Entity(indices = [Index("rootUri"), Index("state")])
data class Book(
  @PrimaryKey val id: String,        // stable hash of folder document URI
  val rootUri: String,
  val folderUri: String,             // SAF document URI of the book folder
  val relativePath: String,          // "Author/Title" (for sort-by-path & display)
  val title: String,                 // from tags (album) or folder name
  val author: String?,               // from tags (artist/album_artist) or parent folder
  val narrator: String?,
  val year: String?, val genre: String?, val publisher: String?,
  val series: String?, val seriesIndex: String?,
  val coverUri: String?,             // folder image, embedded-cover cache file, or user pick
  val seedColor: Int?,               // dominant cover colour for "colours from cover" (§7.1)
  val durationMs: Long,
  val fileCount: Int,
  val state: BookState,              // NEW, STARTED, FINISHED
  val addedAt: Long,
  val lastPlayedAt: Long?,
  val queuePosition: Int?,           // book queue
  val hidden: Boolean = false,
  val description: String?,          // merged: info.txt / .nfo / tags (§4.3.1)
  val fieldSources: String?,         // JSON {"title":"nfo","chapters":"cue+embedded",...} for "Book info" screen
  val scanSignature: String          // hash(file names + sizes + mtimes) to skip unchanged books
)

@Entity(primaryKeys = ["bookId", "index"])
data class Track(val bookId: String, val index: Int, val uri: String, val fileName: String,
                 val title: String?, val durationMs: Long, val sizeBytes: Long, val mtime: Long)

@Entity(primaryKeys = ["bookId", "index"])
data class Chapter(val bookId: String, val index: Int, val title: String,
                   val trackIndex: Int, val startInTrackMs: Long, val durationMs: Long,
                   val startInBookMs: Long)              // == reference "GlobalChapter"

@Entity data class BookProgress(          // one row per book, written often
  @PrimaryKey val bookId: String,
  val trackIndex: Int, val positionInTrackMs: Long, val updatedAt: Long)

@Entity data class BookSettings(          // per-book overrides; null = use default
  @PrimaryKey val bookId: String,
  val speed: Float?, val savedToggleSpeed: Float?, val pitch: Float?,
  val boostMb: Int?,                       // millibels for LoudnessEnhancer
  val eqBandsDb: String?,                  // "0,0,0,0,0" for 60/230/910/4k/14k Hz
  val balance: Float?, val mono: Boolean?, val skipSilence: Boolean?,
  val skipStartMs: Long?, val skipEndMs: Long?, val repeatMode: String?)

@Entity data class Bookmark(@PrimaryKey(autoGenerate = true) val id: Long, val bookId: String,
  val trackIndex: Int, val positionMs: Long, val title: String, val note: String?, val createdAt: Long)

@Entity data class HistoryEvent(@PrimaryKey(autoGenerate = true) val id: Long, val bookId: String,
  val action: String /* PREV,NEXT,START,PAUSE,BACK,FWD,SEEK,SLEEP */, val trackIndex: Int,
  val positionMs: Long, val at: Long)

@Entity data class ListeningSession(@PrimaryKey(autoGenerate = true) val id: Long,
  val bookId: String, val bookTitle: String, val bookPath: String, // denormalised: survives book deletion
  val startedAt: Long, val listenedMs: Long)

@Entity data class Character(@PrimaryKey(autoGenerate = true) val id: Long, val bookId: String,
  val name: String, val description: String)            // P2
```

Use **one `BookProgress` row per book** and upsert it. Do not log every position change.

### 4.2 Scanner

- Input: persisted SAF tree URIs.
- Use `DocumentsContract` child queries directly, **not** `DocumentFile`, which is O(n) IPC per file and very slow on big libraries. One query per folder, projecting `_id, display_name, mime_type, size, last_modified`.
- Rules:
  - A folder that **contains audio files** is a book. Recurse into folders without audio. A folder with both audio files and sub-folders containing audio is a book that includes only its direct files, and the sub-folders are books too. This matches the reference behaviour.
  - Skip folders containing `.nomedia`.
  - Audio files directly in a root: warn in the UI ("move into a folder"). Do not create a giant book.
  - Track order is a **natural sort** on file name (`2.mp3` < `10.mp3`), case-insensitive.
- Incremental: compute `scanSignature` per folder and only re-parse changed books. "Full scan" ignores signatures.
- Merge folders (P2): user-defined `BookMerge(primaryBookId, memberFolderUri)`. Tracks are concatenated in member order.
- Run as a unique WorkManager job on app start, on root add/remove, and from manual refresh. Expose progress ("Analyzing files: n/m").
- When a book's folder disappears, keep its stats and drop the Book (cascade tracks/chapters/progress after confirmation or 30 days; ponytail: just delete, stats are denormalised).

### 4.3 Metadata and chapters

Per track:

1. Media3 `MetadataRetriever.retrieveMetadata` → title, artist, album, artwork bytes, duration.
2. Chapter extraction, trying each step in order (a pure function `parseChapters(bytesProvider, fileName): List<RawChapter>?`):
   - **MP4/M4B**: Nero `chpl` atom inside `moov/udta`; else QuickTime chapter text track (`tref/chap`). Parse the box tree directly, ~150 lines.
   - **MP3**: ID3v2 `CHAP` frames (`TIT2` sub-frame = title, start ms). Fallback: `TXXX` containing `OverDrive MediaMarkers` → parse `<Marker><Name/><Time>mm:ss.fff</Time></Marker>`.
   - **Ogg/Opus/FLAC**: Vorbis comments `CHAPTER001=HH:MM:SS.mmm`, `CHAPTER001NAME=…`.
3. Folder sidecar files: `.cue`, `.nfo`, images, `info.txt`, Libby `openbook.json` / `libby.txt`. These are **merged** with what is embedded in the audio, not used as a blind override; see §4.3.1.
4. No chapters from any source → one chapter = the file, titled by tag title or file name (setting "Use tag titles").
5. Decode text with charset detection when it isn't valid UTF-8/UTF-16. Add `juniversalchardet` (tiny); cp1251 is common.

Leave **one runnable unit test per parser** with a tiny fixture file (MP4 with `chpl`, MP3 with `CHAP`, a cue sheet, an OverDrive tag string, an `.nfo`).

#### 4.3.1 Folder sidecar files (.nfo, .cue, images) and merge rules

When a book is discovered or changes, the scanner reads every non-audio file in the folder that it recognises, and treats each as an extra source next to the embedded tags. The reference app reads `.cue` (strict file-name match only) but **not** `.nfo`.

**Worked example: the owner's `Andy Weir - Project Hail Mary/` folder.**

| File | What it gives | Quirk |
|---|---|---|
| `Andy Weir - Project Hail Mary.m4b` (925 MB) | tags: title, artist = author, **composer = narrator** (inAudible convention), album, date, genre, comment/description; embedded 500×500 cover; **30 embedded chapters titled `001`…`030`** | chapter titles are useless numbers |
| `Andy Weir - Project Hail Mary.cue` | 31 tracks `TITLE "Chapter 01"`… | `FILE "Project Hail Mary.m4b"` **doesn't match** the real file name; times look like `37:4:93`: minutes > 99, no zero padding, and the last field is **hundredths**, not CD frames (93 > 74; 37:04.93 = embedded chapter 2 at 2224.936 s); track 31 starts at 58253.57 s, **after the end** of the 58253.08 s file |
| `Andy Weir - Project Hail Mary.nfo` | Title, Author, **Read By** (narrator), Copyright, Publisher, Genre, Duration, Chapters count, **Book Description**, plus rip info | CRLF, aligned `Key: Value` lines grouped under `====`-underlined section headers |
| `Andy Weir - Project Hail Mary.jpg` | cover, 500×500 | named after the book, not `cover.jpg` |

The expected merged result is: title "Project Hail Mary", author "Andy Weir", narrator "Ray Porter", year 2021, publisher "Audible Studios", the description from the `.nfo`, and **30 chapters with embedded timings and `.cue` titles "Chapter 01"…"Chapter 30"** (cue track 31 dropped). Commit this folder's `.cue` and `.nfo` (text only, never the audio) as test fixtures, and assert exactly this result.

**`.nfo` parser** (pure function `parseNfo(text): NfoData`):
- Charset-detect, normalise CRLF, and treat a line followed by a line of `=`/`-` as a section header.
- In key/value sections, match `^\s*([^:]{2,40}):\s*(.+)$` with case-insensitive key aliases:
  - title ← `Title`, `Book Title`
  - author ← `Author`, `Written By`, `Writer`
  - narrator ← `Read By`, `Narrator`, `Narrated By`, `Reader`
  - year ← `Copyright`, `Year`, `Release Date`, `Audiobook Copyright`; take the first 4-digit year
  - publisher ← `Publisher`
  - genre ← `Genre`
  - series ← `Series`; seriesIndex ← `Series Index`, `Book`, `Part`
  - expectedChapters ← `Chapters`
  - expectedDuration ← `Duration`, parsing `16 hours, 10 minutes, 53 seconds` / `16:10:53`
- A section titled `Description`, `Book Description`, `Summary`, `Plot` or `Publisher's Summary` becomes the description: free text up to the next header, whitespace collapsed.
- Ignore technical sections (`Media Information`, codec, bitrate, ripper). Unknown keys are kept in `fieldSources` raw data only.
- Ignore the genre value `Audiobook`, which carries no information.

**`.cue` parser, made tolerant:**
- `INDEX 01 m:s:f` with any number of digits per field.
- Decide the last field's unit once per sheet: if any value is ≥ 75, the sheet uses **hundredths**; otherwise it uses CD **frames** (÷75). The difference is under 1 s either way, so this heuristic is safe.
- `TITLE` per track becomes the chapter title, and `PERFORMER` at sheet level is an author fallback.
- **File binding**, applied in order:
  1. exact `FILE` name match;
  2. case-insensitive match ignoring extension;
  3. if the folder has exactly one audio file, or the cue has one `FILE`, bind to it;
  4. otherwise fuzzy-match by the longest common substring of base names, which must be ≥ 60 % of the shorter name;
  5. else ignore the cue and log it in `fieldSources`.
- Drop tracks whose start is ≥ file duration − 1 s, and a non-monotonic track.
- A cue sheet named after one audio file (`Book.m4b.cue` or `Book.cue` next to `Book.m4b`) applies to that file only.

**Images (covers):**
- Candidates are all images in the folder (jpg, jpeg, png, webp, gif), ranked:
  1. names `cover`/`folder`/`front`/`poster`;
  2. a name equal to the audio file's or the folder's base name (the example);
  3. any other image, skipping names with `back`, `cd`, `disc`, `inlay`, `booklet`, or a size under 200 px.
- **Embedded vs folder image**: take the higher-resolution one; on a tie, prefer the folder file, which is usually the original scan. The user can override in "Change cover".

**Merge rules.** Each field is taken from the first source in its priority list that has a non-blank value. "User" means edits made in MFBooks, stored in `.mfbooks.json`.

| Field | Priority |
|---|---|
| title | user → `.nfo` → tag `album` → tag `title` (single-file books) → folder name |
| author | user → `.nfo` → tag `album_artist` → tag `artist` → cue `PERFORMER` → parent folder name |
| narrator | user → `.nfo` → tag `narrator`/`TXXX:NARRATOR` → tag `composer` (only if the file is an M4B/MP4 or the encoder tag says inAudible/OpenAudible/Audible) |
| year, publisher, genre, series | user → `.nfo` → tags |
| description | user → `info.txt` → `.nfo` → tag `description`/`comment`/`©des` (the longest one) |
| cover | user → resolution rule above |
| chapters | see below |

**Chapter merge**, per audio file:
1. Gather candidates: embedded (§4.3 step 2), the bound `.cue`, Libby json/txt, and the cached sidecar copy (for converted files, §4.5).
2. If both a cue and embedded chapters exist and **align** (counts differ by ≤ 1 after dropping out-of-range cue tracks, and ≥ 80 % of starts match within 2 s), **combine** them. Timings come from embedded (more precise). Each title comes from whichever is more descriptive: a title counts as *generic* if it matches `^\s*(\d+|chapter\s*\d+|track\s*\d+|part\s*\d+)\s*$`; a non-generic title beats a generic one; if both are generic, prefer the cue's (e.g. "Chapter 01" over "001"). In the example this gives 30 chapters titled "Chapter 01"…"Chapter 30".
3. If they don't align, use the source with more chapters that lie inside the file duration. Ties go to embedded.
4. Sanity: starts strictly increasing, first start ≥ 0, every start < duration, and drop zero-length chapters. Compare against the `.nfo` `Chapters` count only as a diagnostic, never to drop data.
5. Record the winning sources in `Book.fieldSources` (e.g. `"chapters":"embedded-timing+cue-titles"`), and show them in a "Book info" sheet so the user can see where each value came from.

**Caching and change detection:**
- The sidecar files' names, sizes and mtimes are part of `scanSignature`. Adding or editing a `.nfo`, `.cue` or image triggers a re-merge of that book only.
- The merged result is written into `.mfbooks.json` (`book` + `files[].chapters`), so later scans are fast and converted files keep their chapters (§4.6).
- MFBooks **never modifies** `.nfo`, `.cue` or images; user edits go only into `.mfbooks.json`.

### 4.4 Covers

Priority: user-picked → the folder-image vs embedded-art resolution rule in §4.3.1 → embedded art (cache as `filesDir/covers/<bookId>.jpg`) → generated placeholder (initials on a seeded colour). Expose covers to AA through a `FileProvider`/`ContentProvider` URI (§6.4).

### 4.5 Book sidecar `.mfbooks.json` (progress + metadata)

Each book folder gets one `.mfbooks.json`. It does two jobs:

1. **Portable progress.** This keeps the reference app's best property: progress travels with the files and survives reinstalls.
2. **Metadata store.** It holds the chapters, tags and cover reference the app uses. This is what lets Opus conversion (§4.6) throw away the MP3's ID3 tags without losing chapters, and it also makes re-scans fast.

```json
{
  "v": 1,
  "updatedAt": 1760700000000,
  "progress": { "file": "05 - Chapter Five.opus", "positionMs": 734000, "state": "STARTED" },
  "settings": { "speed": 1.25, "skipSilence": true, "boostMb": 300, "eqBandsDb": [0,2,3,1,0] },
  "book": {
    "title": "Dune", "author": "Frank Herbert", "narrator": "Scott Brick", "year": "1965",
    "genre": "Science fiction", "description": "…", "cover": "cover.jpg"
  },
  "files": [
    {
      "name": "05 - Chapter Five.opus",
      "size": 18234567, "mtime": 1760600000000, "durationMs": 2412000,
      "title": "Chapter Five",
      "tags": { "artist": "Frank Herbert", "album": "Dune", "track": "5" },
      "chapters": [ { "title": "Part 1", "startMs": 0 }, { "title": "Part 2", "startMs": 1203000 } ],
      "converted": { "from": "05 - Chapter Five.mp3", "codec": "mp3", "bitrateKbps": 128, "at": 1760600000000 }
    }
  ],
  "bookmarks": [ { "file": "03.opus", "positionMs": 12000, "title": "…", "note": "…" } ]
}
```

Rules:

- **Files are matched by `name` + `size` + `mtime`.** If all three match, the scanner uses the cached `durationMs`, `title`, `tags` and `chapters` instead of re-parsing the file. This is fast, and it is the only metadata source for converted files.
- If the file changed, re-parse it. If the new parse finds no chapters but the cached entry has `converted` set and its `durationMs` is within 1 s, keep the cached chapters. Opus files have no ID3 chapters to rediscover.
- `book` and `files[].chapters` hold the **merged** result of §4.3.1, not any single source. On re-merge, sources still present win; the cached copy is used only for converted files whose original tags are gone. User-edited fields are marked (`"userEdited": ["title","chapters"]`) and are never overwritten by a scan.
- When it is written:
  - the full document is written after each scan of the book;
  - `progress`/`settings` are written on pause, on stop, on book switch, and at most every 5 min while playing. Never on every tick, because SAF writes are slow;
  - the file is written after each converted file (§4.6).
- Writes are crash-safe: write `.mfbooks.json.tmp`, then rename it over the old file (`DocumentsContract.renameDocument`; if the provider doesn't support it, delete and rename).
- When it is read: during scan. Progress is taken from it if Room has no progress for the book or the sidecar's `updatedAt` is newer.
- Unknown keys must be preserved on rewrite (forward compatibility). Bump `v` only on breaking changes.
- Setting "Save progress in book folders" (default **on**). It needs write permission on the tree; if that fails, quietly fall back to Room only.
- P2 importer: read the reference app's `bookmarks.sabp.xml` (plain XML, §1.4) and `statistics.xml`. Skip `position.sabp.dat`: it is Java-serialized and obfuscated, so importing it is fragile.

### 4.6 MP3 → Opus conversion (space saver)

The reference app converts MP3s to Opus 32 kb/s but warns that chapter metadata is lost. MFBooks must lose nothing the app uses: chapters, titles, tags and cover are captured into `.mfbooks.json` **before** the original file is touched.

**Eligibility** (same as the reference): MP3 files with bitrate > 50 kb/s, in root folders the user ticks. Opus 32 kb/s is fine for speech; the screen warns not to convert music.

**Pipeline per file**, using only platform APIs:

1. Read metadata: tags, title, embedded cover and chapters (§4.3). Extract the cover to `cover.jpg` in the folder if the book has no folder image.
2. Write the file's entry into `.mfbooks.json`, with `chapters`, `tags`, `title`, `durationMs` and `converted.from`. This is the commit point for metadata; if it fails, stop and don't convert.
3. Decode: `MediaExtractor` + `MediaCodec` (MP3 → PCM). Downmix to mono if the source is mono or the user picked "mono" (default: keep channels). Resample to 48 kHz, which Opus requires.
4. Encode: `MediaCodec` Opus encoder (`MediaFormat.MIMETYPE_AUDIO_OPUS`, available from API 29, so it matches minSdk), `KEY_BIT_RATE = 32000`.
5. Mux: `MediaMuxer` with `MUXER_OUTPUT_OGG` (API 29+) into `<name>.opus.tmp` in the same folder.
6. Verify: decoded duration of the new file is within ±500 ms of the original. Otherwise delete the temp file and mark the file `failed` in the job state.
7. Swap: delete the MP3, rename `.opus.tmp` → `<same base name>.opus`. Update the sidecar entry (`name`, `size`, `mtime`), and remap any `progress.file` and `bookmarks[].file` that pointed at the old name. Positions don't change, because the timeline is identical.
8. Update Room in the same step (track URI, size, file name), so playback and bookmarks keep working without a full rescan.

Decisions and safeguards:

- **Extension becomes `.opus`.** The reference keeps `.mp3`, which misleads other players. Other apps will still play the file, but only MFBooks knows its chapters, because they live in the sidecar. The confirmation screen says so.
- Tags are kept in the sidecar only, since `MediaMuxer` can't write Vorbis comments. ponytail: writing an `OpusTags` page ourselves is ~100 lines; add it if other players' display matters.
- Never convert the file that is currently playing. Skip it and retry at the end.
- Runs as a WorkManager job with constraints `requiresCharging` **or** battery ≥ user threshold (default 80 %), and `requiresStorageNotLow`. It resumes after interruption from per-file state stored in the job's `Data` plus the sidecar's `converted` markers. A file that already has `converted` is skipped.
- Progress notification: "Converting 12/340 · saved 1.8 GB". Saved bytes accumulate in DataStore (`convertSavedBytes`), with a reset action.
- Pre-flight screen: the explicit warnings (irreversible, keep a backup, speech-only) and a confirmation checkbox, same as the reference.
- Requires "Save progress in book folders" to be on, because the sidecar is the metadata store. The switch is locked on while converted files exist, with an explanation.
- One test: convert a 10-second MP3 fixture with an ID3 `CHAP` frame. Assert that the output is Ogg/Opus, that the duration is within 500 ms, and that the sidecar chapters equal the original chapters.

---

## 5. Playback engine

### 5.1 Book → playlist

A book is played as **one ExoPlayer playlist**:

- If the book has real chapters, each chapter becomes a `MediaItem` built from the track URI with a **`ClippingConfiguration(start, end)`**. The AA queue, the notification title and Wear then show the current chapter. What the **next/previous** buttons do is a separate, configurable mapping (§5.6); by default they skip by seconds rather than chapters.
- If it has none, each file is one `MediaItem`.
- `mediaId = "$bookId/$chapterIndex"`, with `MediaMetadata` (title = chapter title, artist = author, albumTitle = book title, artworkUri, `extras` with book offsets).
- Position in book = `chapter.startInBookMs + player.currentPosition`. Keep this as one helper function.
- Skip start/end (per book): apply as extra clipping on file-items. In chapter mode, apply only to the first and last chapter of each file.

> ponytail: clipping a large M4B into N items makes ExoPlayer re-open the same file per chapter boundary. That is fine in practice. If gaps are audible, fall back to whole-file items and implement chapter skip as `seekTo` within the file.

### 5.2 Player configuration

```kotlin
ExoPlayer.Builder(ctx)
  .setAudioAttributes(AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
      /* handleAudioFocus = */ true)
  .setHandleAudioBecomingNoisy(true)
  .setWakeMode(C.WAKE_MODE_LOCAL)
  .setSeekBackIncrementMs(prefs.rewindSmallMs).setSeekForwardIncrementMs(prefs.fwdSmallMs)
  .setRenderersFactory(customRenderersFactoryWithAudioProcessors)
  .build()
```

- **Speed and pitch**: `player.playbackParameters = PlaybackParameters(speed, pitch)`. Speed range 0.5–3.0, step 0.05.
- **Skip silence**: `exoPlayer.skipSilenceEnabled`.
- **Volume boost**: `android.media.audiofx.LoudnessEnhancer(player.audioSessionId)`, 0–15 dB (`setTargetGain(mB)`).
- **Equalizer**: 5 bands at 60/230/910/4000/14000 Hz, ±15 dB. Use a custom Media3 **`AudioProcessor`** of 5 biquad peaking filters (RBJ cookbook, Q≈1). The system `audiofx.Equalizer` has device-dependent band layouts and a ±15 dB range isn't guaranteed. ~120 lines plus one test that checks unity gain at 0 dB.
- **Balance and mono**: `ChannelMixingAudioProcessor` (Media3) with a per-book matrix.
- **Noise reduction (P3)**: an RNNoise `AudioProcessor` via JNI (BSD licence). Only if asked.
- Interruptions:
  - `handleAudioFocus=true` gives pause on calls/transient loss and resume afterwards. Map the settings "pause during messages", "stop when others playing" and "resume after call". Media3 already ducks or pauses on transient loss; implement the setting by listening to focus changes through a `Player.Listener` (`onPlayWhenReadyChanged` reason `AUDIO_FOCUS_LOSS`) and deciding whether to re-resume.
  - "Play on headset connect": `AudioDeviceCallback` → `play()` when a wired or BT A2DP device appears and the setting is on.
- **Auto-rewind on resume**: in `onIsPlayingChanged(true)`, look up the pause length in the §1.6 table and `seekTo(pos − n)`, clamped to the chapter start. This is a pure function `autoRewindMs(pauseMs, level)` with a unit test.
- **Force stop** after N hours of continuous playback: a coroutine in the service.

### 5.3 Progress saving

- Every 5 s while playing, plus on pause, seek, chapter change and service destroy → upsert `BookProgress`. Sidecar rules are in §4.5.
- Book state transitions:
  - `NEW → STARTED` on the first play beyond 10 s;
  - `STARTED → FINISHED` when the end of the last item is reached, or by user action;
  - "Mark as new" resets progress after confirmation.
- `ListeningSession`: open on play, close on pause, accumulating real listened time (wall time while `isPlaying`).

### 5.4 Sleep timer

A state machine in `SleepTimer.kt`, owned by the service and exposed as a `StateFlow` plus a custom command.

- Modes: `Off`, `Duration(minutes)` (5, 10, 15, 20, 30, 45, 60, 75, 90, custom), `EndOfChapter`.
- It counts **playback time only**, so it pauses while the player is paused.
- In the last 60 s, `player.volume` ramps linearly 1→0. At 0 it pauses, then restores the volume.
- A fade-start chime plays at T−50 s and a reset chime on shake, using `SoundPool` with the chosen volume. The chimes must be original sounds.
- Shake: accelerometer listener, active only while the timer runs. Trigger when |Δaccel| > threshold (1, 3, 6, 12, 18 m/s²); `trackMotion` = Always / During fade-out / Never. A shake restarts the countdown.
- Schedule (P2): `from`/`to` local times. When playback starts inside the window, arm the timer automatically.
- In Android Auto: setting "Disable sleep timer while in car" (default on). Detect the car with `androidx.car.app.connection.CarConnection` (`CONNECTION_TYPE_PROJECTION`).
- Each trigger writes a `HistoryEvent(SLEEP)`.

### 5.5 Commands (one vocabulary for everything)

Custom `SessionCommand` actions, also accepted as `Intent` actions on an exported receiver for Tasker/automation (`app.mfbooks.action.*`):

```
PLAY_PAUSE, PLAY, PAUSE, REWIND_SMALL, REWIND_BIG, FWD_SMALL, FWD_BIG,
PREV_CHAPTER, NEXT_CHAPTER, PREV_BOOK, NEXT_BOOK,
ADD_BOOKMARK, NEXT_BOOKMARK, TOGGLE_SPEED, SET_SPEED(extra float),
SLEEP_ON, SLEEP_OFF, SLEEP_EXTEND, EXIT
```

Headset multi-press: in `MediaSession.Callback.onMediaButtonEvent`, count `KEYCODE_HEADSETHOOK`/`MEDIA_PLAY_PAUSE` presses within 400 ms. Map 1/2/3/4 presses to any command (defaults: play-pause / forward small / rewind small / add bookmark).

### 5.6 Next / previous button mapping

The reference app does this for Bluetooth devices: the remote's ⏭/⏮ keys (`KEYCODE_MEDIA_NEXT`/`PREVIOUS`) run a configurable action, usually a seconds skip. MFBooks applies the same mapping to **every external surface**: Bluetooth/wired remotes, steering-wheel buttons, the Android Auto ⏮/⏭ buttons, the notification, the lock screen, Wear OS, and Quick Settings media controls.

Settings (Controls → "Next / previous buttons"):

| Key | Options | Default |
|---|---|---|
| `nextAction` | Forward 10 / 15 / 20 / **30** / 45 / 60 / 120 s · Next chapter · Next file · Add bookmark · Next bookmark · Next book · Toggle speed | Forward 30 s |
| `prevAction` | Rewind 5 / 10 / 15 / 20 / **30** / 45 / 60 / 300 s · Previous chapter · Previous file · Previous book | Rewind 30 s |

(The values are the reference app's lists. "Forward/Rewind small" in the in-app UI uses its own sizes, §7.3.)

Implementation:

- Wrap ExoPlayer in a **`ForwardingPlayer`** handed to the `MediaLibrarySession`. Override `seekToNext()`, `seekToPrevious()`, `seekToNextMediaItem()` and `seekToPreviousMediaItem()` to run the mapped command. Because every controller (AA, notification, Bluetooth via the session, Wear) calls these, one override covers them all.
- `getAvailableCommands()` keeps advertising `COMMAND_SEEK_TO_NEXT`/`PREVIOUS` even on the first or last chapter, so the buttons never grey out when they mean "skip 30 s".
- Seconds skips go through one helper, `seekByMs(delta)`, that works in **book time**: book position + delta → (item index, offset), clamped to [0, book length]. Plain `seekForward()` stops at the end of a clipped chapter item, so it must not be used. Auto-rewind (§5.2) uses the same helper and may cross into the previous chapter.
- Media buttons that arrive as raw key events (`onMediaButtonEvent` with `KEYCODE_MEDIA_NEXT`/`PREVIOUS`/`FAST_FORWARD`/`REWIND`) map the same way. `FAST_FORWARD`/`REWIND` always mean forward/rewind small.
- The **in-app** full player keeps explicit, labelled buttons: chapter skip ⏮⏭ and seconds skip ⟲⟳ (§7.3). The mapping only changes what the *generic* next/previous means elsewhere.
- The AA and notification icons for ⏮/⏭ reflect the mapping. With seconds mapped, use `CommandButton` icons `ICON_SKIP_BACK_30`/`ICON_SKIP_FORWARD_30` (or the matching 5/10/15 icon) in the `SLOT_BACK`/`SLOT_FORWARD` slots instead of the chapter icons. Chapter navigation in the car is then the AA queue / book's chapter list, plus an optional overflow button (§6.5).
- Unit test: `seekByMs` across a chapter boundary in both directions and at both book ends.

---

## 6. Android Auto (the headline feature)

### 6.1 Manifest

```xml
<service android:name=".playback.PlaybackService"
    android:foregroundServiceType="mediaPlayback" android:exported="true">
  <intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService"/>
    <action android:name="android.media.browse.MediaBrowserService"/>
  </intent-filter>
</service>
<meta-data android:name="com.google.android.gms.car.application"
           android:resource="@xml/automotive_app_desc"/>   <!-- <uses name="media"/> -->
<!-- optional, for AA app icon colouring -->
<meta-data android:name="androidx.car.app.TintableAttributionIcon" android:resource="@drawable/ic_mfbooks_mono"/>
```

Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. Nothing else is needed.

### 6.2 Browse tree

The root returns **at most 4 tabs** (the AA limit). Use a grid style for books and a list style for chapters.

```
root  (extras: CONTENT_STYLE_BROWSABLE=GRID, CONTENT_STYLE_PLAYABLE=LIST)
 ├─ [tab] Continue    → Started books sorted by lastPlayedAt  (PLAYABLE+BROWSABLE)
 ├─ [tab] New         → New books                              (PLAYABLE+BROWSABLE)
 ├─ [tab] Library     → Authors (browsable) → books
 └─ [tab] Finished    → Finished books
book item (browsable + playable)
 └─ children: chapters (playable)  → "Chapter 7 · 12:34"
```

- **Book items**: `isPlayable=true` (tapping resumes at the saved position) **and** `isBrowsable=true`. Opening a book shows its chapters. Tapping a chapter starts that chapter.
- **Progress badges** in `MediaMetadata.extras`:
  - `DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS` = `NOT_PLAYED` / `PARTIALLY_PLAYED` / `FULLY_PLAYED`;
  - `DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE` = 0.0–1.0;
  - subtitle = author; description = "3 h 12 m left".
- **Group titles**: under Library → Authors, use `EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE` for the first letter if the list is long.
- Respect `MediaLibraryService.LibraryParams` and `BrowserRoot` hints, and page through `onGetChildren(page, pageSize)`.
- **Recent root**: `onGetLibraryRoot` with `params.isRecent` → return a root whose only child is the last-played book. This enables system media resumption (Bluetooth connect → "resume MFBooks").
- **`onPlaybackResumption`**: rebuild the last book's playlist at its saved position. Required for resume-on-connect and the "play" button on an idle head unit.

### 6.3 Search and voice

- `onSearch` / `onGetSearchResult`: case-insensitive match on title/author/narrator over Room (FTS4 table, or `LIKE` for v1). Return books.
- Voice "Hey Google, play *Dune* on MFBooks" arrives as `onSetMediaItems` / `onAddMediaItems` with `requestMetadata.searchQuery`. Resolve it to the best book and resume it. An empty query resumes the last book.
- Declare `MEDIA_PLAY_FROM_SEARCH` on the activity as well, for phone voice.

### 6.4 Artwork

`artworkUri = content://app.mfbooks.covers/<bookId>` served by a tiny `ContentProvider` (`openFile` returns the cached JPEG, downscaled to ≤ 512 px). **Never** put Bitmaps or byte arrays in browse items.

### 6.5 Transport controls in the car

Configure `MediaSession` **media button preferences** (Media3 `CommandButton`s with slots):

| Slot | Button |
|---|---|
| Back (⏮) | `prevAction` from §5.6. Default rewind 30 s, icon `ICON_SKIP_BACK_30`. |
| Forward (⏭) | `nextAction` from §5.6. Default forward 30 s, icon `ICON_SKIP_FORWARD_30`. |
| Overflow 1 | Playback speed (cycles 1.0 → 1.25 → 1.5 → 1.75 → 2.0 → 0.75; icon shows the value) |
| Overflow 2 | Sleep timer (off → 15 → 30 → 60 → end of chapter → off; icon reflects state) |
| Overflow 3 | Add bookmark (toast/notification "Bookmark added") |
| Overflow 4 | Next chapter. Shown only when ⏭ is mapped to seconds, so chapter skip stays reachable. |

Steering-wheel and Bluetooth ⏮/⏭ follow the same mapping automatically, because they go through the same `ForwardingPlayer`.

- AA's queue view shows the playlist, so chapters appear in the queue for free (§5.1).
- Keep the phone UI usable while connected; the reference app blocks the Library, MFBooks doesn't.
- Bookmark title in the car = the chapter title plus a timestamp. No text entry while driving.

### 6.6 Testing Android Auto

- Desktop Head Unit (DHU) from the SDK: run `desktop-head-unit` and enable AA developer mode on the phone.
- Media3 **`MediaController`-based instrumentation test** that browses the root → tabs → book → chapters and asserts item flags and extras.
- Manual checklist: cold start in the car with an empty library (show an error item, "Open MFBooks on your phone to add a folder"), resume after a BT reconnect, voice play by title, speed and sleep buttons, chapter skip, night mode artwork.

---

## 7. UI — Material 3, YouTube Music style

### 7.1 Principles
- **Edge-to-edge**, `Scaffold` with **NavigationBar** (bottom) on phones and **NavigationRail** on tablets/foldables (`NavigationSuiteScaffold`).
- **Persistent mini-player** above the nav bar on every top-level screen. Swipe up or tap expands it into the **full player**, a shared-element transition from the mini-player artwork. Swipe down collapses it.
- **Material 3 Expressive throughout.** Wrap the app in `MaterialExpressiveTheme` with `motionScheme = MotionScheme.expressive()`. Use the Expressive components rather than plain M3 equivalents wherever one exists:

  | Where | Expressive element |
  |---|---|
  | Full-player transport | `ButtonGroup` with connected buttons. The play/pause button is a large shape-morphing `FilledIconButton`/`ToggleButton` (round ↔ squircle on press and state change). |
  | Chapter/book progress | `LinearWavyProgressIndicator` while playing (wave flattens on pause), plain `Slider` thumb for scrubbing |
  | Loading/scanning | `LoadingIndicator` / `ContainedLoadingIndicator` (morphing shapes) instead of spinners |
  | Full-player secondary actions (speed, sleep, EQ, bookmark) | `HorizontalFloatingToolbar` or a scrollable row of `ToggleButton`s/chips that change shape when active |
  | Library primary action ("Resume") | `FloatingActionButtonMenu` / `MediumExtendedFloatingActionButton` |
  | Book detail "Play / Queue" | `SplitButtonLayout` (Play + menu for "Play from beginning / Play chapter…") |
  | Top bars | `LargeFlexibleTopAppBar` / `MediumFlexibleTopAppBar` with subtitle (author) |
  | Navigation | `ShortNavigationBar` (phones) / `WideNavigationRail` (tablets) |
  | Covers in grids and carousels | `MaterialShapes` (Cookie, Clover, Pill…) for placeholders and the current-book highlight; rounded-28 dp for real covers |
  | Typography | Emphasized type styles (`displayMediumEmphasized`, `titleLargeEmphasized`) for the now-playing title and Home hero |

  Any Expressive API still marked experimental is acceptable. Pin the Compose BOM / material3 version in `libs.versions.toml` and don't hand-roll a lookalike of an existing component.
- **Colour source, a user setting** (`colorSource`, Appearance → "App colours"):
  - **System palette** (default): Material You `dynamicLightColorScheme(ctx)` / `dynamicDarkColorScheme(ctx)`, inherited from the phone's wallpaper/theme. The colours stay the same when the book changes.
  - **From cover**: seed an M3 `ColorScheme` with the dominant cover colour (`material-kolor` `rememberDynamicColorScheme(seed, isDark, style = PaletteStyle.TonalSpot)`). Animate the scheme between books with `animateColorAsState` over ~400 ms. Falls back to the system palette when the book has no cover.
  - The setting applies app-wide: player, mini-player, book detail, notification accent and widgets. Compute the cover seed once per book during the scan and store it as `Book.seedColor: Int?`, so switching is instant and works offline.
  - Devices with no dynamic colour support fall back to the MFBooks brand scheme in both modes.
- Themes (`theme`): System / Light / Dark / **AMOLED black** (keep the reference's "Black"). Black = the chosen scheme with `surface`/`background` forced to `#000000`.
- Typography: M3 Expressive type scale. Book titles use `titleLarge`/`headlineSmall`. Times use `tabular` figures (`FontFeatureSettings "tnum"`).
- Motion: the expressive spring `MotionScheme`, with `animateContentSize` on chips and sheets. Respect reduced-motion (fall back to `MotionScheme.standard()`).
- Accessibility: every icon button has a content description; minimum touch target 48 dp; the seek bar is operable with TalkBack (custom accessibility actions ±30 s / next chapter).

### 7.2 Navigation

```
Bottom nav:   Home   ·   Library   ·   Stats
Top app bar:  search icon, settings (avatar-style overflow on Home like YT Music)
Overlay:      MiniPlayer ⇄ FullPlayer (sheet)
Detail:       Book screen (push)
```

### 7.3 Screens

**Home** (like the YT Music home feed):
- Hero "**Continue listening**" card for the current book: big cover, title, author, chapter, progress bar, play FAB.
- Horizontal carousels (`HorizontalMultiBrowseCarousel`): *Started*, *Recently added*, *New*, *Finished recently*.
- Book queue row if the queue isn't empty ("Up next").
- Empty state: an illustration, "Add your audiobooks folder" button, and the recommended folder layout `Audiobooks/Author/Title/`.

**Library**:
- Filter **chips**: All · New · Started · Finished (+ "Hidden" in overflow). Multi-select is off; one at a time.
- Sort menu: Title, Author, Recently played, Date added, Length, Path.
- View toggle: **Grid** (covers, 2–4 columns adaptive) / **List** (cover thumb, title, author, progress, time left) / **Folders** (tree browse like the reference "folder view").
- Fast-scroll letter rail on the right (reference "quick scroll").
- Search: M3 `SearchBar` with live results.
- Long-press → bottom sheet: Mark New/Started/Finished, Add to queue, Change cover, Edit details, Hide, Delete files…, Book info.
- Pull-to-refresh = incremental scan. Overflow menu has "Full rescan".

**Book detail** (like a YT Music album page):
- Large cover with a blurred, colour-tinted backdrop, title, author, narrator, total length, "x h y m left", state chip.
- Primary buttons: **Play / Resume**, Add to queue, Bookmarks, More.
- Description (`info.txt` / tag comment), expandable.
- **Chapters list**: number, title, duration. The current chapter is highlighted with an equalizer animation, and listened chapters are dimmed.
- Files section (collapsed) when chapters ≠ files.

**Full player** (the main event):
- Top: collapse chevron, "Playing from *Book*" label, overflow (sleep, EQ, speed, bookmarks, cast (P3), book info, settings).
- **Cover art**, large and rounded (28 dp), with a subtle shadow; the background is a gradient from the active scheme's `primaryContainer` to `surface` (cover-tinted or system-tinted, per the colour setting). Swipe the cover left/right for previous/next chapter (YT Music gesture).
- Title = chapter title; subtitle = book title · author.
- **Two-level progress**: the main `Slider` = chapter progress with elapsed/remaining. A thin secondary bar or text below = book progress ("Book 37% · 6 h 12 m left"). The progress format is configurable (§1.8).
- Transport row: ⏮ prev chapter · ⟲ rewind (label "30") · ▶/⏸ large expressive play button (shape morphs) · ⟳ forward · ⏭ next chapter. Long-press on rewind/forward = big skip; long-press prev/next = chapter picker.
- **Chip row** (scrollable): `1.25×` speed · `Sleep 15:00` (live countdown) · `EQ` · `Boost` · `Bookmark +` · `Skip silence`. Chips show state.
- **Bottom sheet tabs** (the YT Music "Up next / Lyrics / Related" pattern): **Chapters** · **Bookmarks** · **History** (undo list). Subtitles (SRT) can be a 4th tab (P2), styled like YT Music lyrics: synced, auto-scroll, tap a line to seek.
- Lock mode (reference "lock buttons"): a lock icon hides everything except play/pause. P2.
- Rotate-to-pause gesture: setting, off by default (P2).

**Dialogs/sheets** (modal bottom sheets, not old-style dialogs):
- *Speed*: big value readout, slider 0.5–3.0, preset chips (0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0), "Set as default", "Apply to this book only".
- *Sleep timer*: preset chips, End of chapter, a custom duration picker, shake settings shortcut, schedule (P2).
- *Equalizer*: 5 vertical sliders (±15 dB) with presets (Flat, Voice clarity, Bass cut, Treble boost), Balance, Mono, Pitch, Reset, "Set as default".
- *Boost*: slider 0–15 dB with a warning about clipping above 10 dB.
- *Bookmark*: title (prefilled with chapter + time) and note.

**Stats** (P1):
- Total listening time with a period switch (Week / Month / Year / All).
- Bar chart per day (Compose Canvas, no chart library).
- List of books with listened time; this includes deleted books.
- "Finished this year" count.

**Settings** (M3 list items, grouped):

| Group | Items |
|---|---|
| Library | Root folders (add/remove), Save progress in book folders, Use tag titles, Show `.nomedia`-hidden books, Rescan |
| Playback | Auto-rewind (Off/Small/Medium), Default speed, Default skip-silence, End-of-book action, Pause during messages, Stop when other apps play, Resume after call, Play on headset connect, Force stop after N h |
| Sleep timer | Default duration, Fade-out length, Shake to reset + sensitivity + when, Chime volumes, Disable in car, Schedule (P2) |
| Controls | **Next / previous buttons action** (§5.6, applies to Bluetooth, car, notification, lock screen, Wear), Headset multi-press mapping (1/2/3/4 press), In-app rewind/forward small & big sizes |
| Appearance | Theme, **App colours: System palette / From cover**, Progress format, Show chapters, Keep screen on while charging, Lock portrait |
| Automation | Tasker intents list (read-only, copyable) |
| About | Version, licences |

### 7.4 Notification
The Media3 default `MediaNotification`, with the same `CommandButton` preferences as AA (§6.5): ⏮/⏭ follow the §5.6 mapping (default ±30 s, with seconds icons), plus play/pause, and speed/bookmark in the expanded view. Show the cover as large icon (setting) and the chapter title as the title.

### 7.5 Widgets (P2)
Glance widgets: small (cover + play/pause + rewind), large (cover, title, chapter, progress, rewind/play/forward, sleep).

---

## 8. Feature parity matrix

| # | Feature (reference) | MFBooks | Priority |
|---|---|---|---|
| 1 | Root folders via SAF, multiple | yes | **P0** |
| 2 | Folder = book, natural sort, `.nomedia` hide | yes | **P0** |
| 3 | New/Started/Finished states + tabs | yes | **P0** |
| 4 | Chapters: M4B/MP4, ID3 CHAP, Vorbis, cue | yes | **P0** |
| 4a | Folder `.nfo` / `.cue` / images merged with embedded tags (§4.3.1) | yes; the reference has cue only, with strict name match | **P0** |
| 5 | Chapters: OverDrive, Libby json/txt | yes | P1 |
| 6 | Save/resume position per book | yes | **P0** |
| 7 | Progress + metadata sidecar in book folder | `.mfbooks.json` (§4.5) | P1 |
| 8 | Speed per book, default, toggle | yes | **P0** |
| 9 | Skip silence | yes | P1 |
| 10 | Volume boost | LoudnessEnhancer | P1 |
| 11 | 5-band EQ ±15 dB, balance, mono, pitch | AudioProcessor | P1 |
| 12 | Noise reduction (RNNoise) | optional | P3 |
| 13 | Sleep timer: duration/end-of-chapter, fade, shake | yes | **P0** (shake P1) |
| 14 | Sleep schedule | yes | P2 |
| 15 | Auto-rewind after pause | yes (exact table) | **P0** |
| 16 | Configurable rewind/forward sizes | yes | **P0** |
| 17 | Headset single/multi-press mapping | yes | P1 |
| 17a | Next/previous → configurable seconds skip (BT, car, notification, Wear) | `ForwardingPlayer` (§5.6) | **P0** |
| 18 | Bookmarks (title, note, quick add) | yes | P1 |
| 19 | History / undo | yes | P1 |
| 20 | Statistics (survive deletion) | yes | P1 |
| 21 | Book queue | yes | P2 |
| 22 | End-of-book: next book (+same settings) | yes | P1 |
| 23 | Skip start/end of each file | yes | P2 |
| 24 | Repeat file/book N× | yes | P2 |
| 25 | Covers: folder/embedded/pick | yes | **P0** (pick P1) |
| 26 | Cover/description web search | open browser intent only | P3 |
| 27 | Description from `info.txt` | yes | P1 |
| 28 | SRT subtitles | lyrics-style tab | P2 |
| 29 | Open txt/pdf from folder | `ACTION_VIEW` intent | P2 |
| 30 | Characters list | yes | P3 |
| 31 | Merge folders (CD1/CD2) | yes | P2 |
| 32 | Delete files/finished/folder | yes, with confirmation | P2 |
| 33 | Notification + lock screen | Media3 | **P0** |
| 34 | Android Auto | redesigned (§6) | **P0** |
| 35 | Wear OS controls | free via MediaSession | **P0** |
| 36 | Widgets | Glance | P2 |
| 37 | Tasker intents | yes | P1 |
| 38 | Chromecast | Media3 Cast (local files need an HTTP server) | P3 |
| 39 | Rotate-to-pause gesture | yes | P2 |
| 40 | Prevent word clipping (BT) | silent pre-roll / noise | P3 |
| 41 | Backup library to USB | — | Drop (use a file manager) |
| 42 | Convert MP3 → Opus | platform MediaCodec + Ogg muxer; chapters/tags kept in sidecar (§4.6) | P2 |
| 43 | Gemini description/cover helpers | — | Drop |
| 44 | Licence / trial / donate / review prompt | — | Drop |
| 45 | Decoder choice (App1/App2/System) | — | Drop (ExoPlayer + FFmpeg ext) |
| 46 | Theme colours: system palette or from cover | user setting (§7.1) | **P0** |

---

## 9. Delivery plan

Each phase ends with a working, installable APK.

**Phase 1: Listenable (P0)**
1. Project skeleton: Compose, M3 theme, nav bar, Room, DataStore.
2. Onboarding: pick a root folder; persist permission.
3. Scanner + metadata + chapter parsers (M4B, ID3 CHAP, Vorbis, tolerant cue) + `.nfo` parser + sidecar-file merge (§4.3.1) + covers + unit tests.
4. `PlaybackService` (MediaLibraryService + ExoPlayer), book→playlist with chapter clipping, progress saving, auto-rewind, rewind/forward sizes, speed, **next/previous → seconds-skip mapping via `ForwardingPlayer` + `seekByMs`** (§5.6).
5. **Android Auto browse tree + artwork provider + search + resumption + custom buttons.** Verify on the DHU.
6. UI: Home (continue listening + carousels), Library (chips, grid/list, sort, search), Book detail (chapters), mini-player ↔ full player built from **M3 Expressive** components (§7.1), **colour source setting (system palette / from cover)**, speed sheet, sleep timer sheet (duration + end of chapter + fade).

**Phase 2: Parity (P1)**
Sidecar `.mfbooks.json` with progress + metadata cache (§4.5), skip silence, boost, EQ/balance/mono/pitch, bookmarks, history, statistics, shake-to-extend, headset multi-press, end-of-book action, OverDrive/Libby chapters, Tasker intents, cover picker, `info.txt`.

**Phase 3: Nice-to-have (P2)**
Book queue, merge folders, skip start/end, repeat, SRT tab, widgets, sleep schedule, delete actions, lock mode, rotate gesture, reference-app XML import, FFmpeg extension, **MP3 → Opus conversion preserving chapters/tags in the sidecar** (§4.6).

**Phase 4: Optional (P3)**
Cast, RNNoise, characters, word-clipping fix, web cover search.

### 9.1 Definition of done (Phase 1)
- [ ] Adding a root with 200 books scans in < 30 s on a mid-range phone, and re-scan of an unchanged library takes < 3 s.
- [ ] An M4B with embedded chapters shows correct chapter titles and durations; the in-app ⏮/⏭ move by chapter, and seconds skip crosses chapter boundaries correctly.
- [ ] With defaults, ⏮/⏭ on a Bluetooth remote, the notification, the lock screen and AA rewind/forward 30 s (seconds icons shown). Switching `nextAction` to "Next chapter" changes all of them at once.
- [ ] The `Andy Weir - Project Hail Mary` fixture merges to: narrator Ray Porter, publisher Audible Studios, description from the `.nfo`, folder `.jpg` cover, 30 chapters with embedded timings and `.cue` titles "Chapter 01"…"Chapter 30".
- [ ] Kill the app mid-playback, reopen: it resumes within 5 s of the last position, and auto-rewind applies.
- [ ] Android Auto (DHU): 4 tabs visible; Continue tab shows progress badges; opening a book lists chapters; voice "play <title>" works; speed and sleep buttons work; reconnecting BT offers resume.
- [ ] Sleep timer 5 min: fades during the last 60 s, pauses, restores volume for next play.
- [ ] Expressive components in use (button group with morphing play button, wavy progress, loading indicator, flexible top bars). Light/dark/black themes. "App colours" switches between the phone's Material You palette and cover colours, live, and falls back to the system palette for books without covers; TalkBack can operate play/pause, seek and chapter skip.
- [ ] No network permission in the manifest.

---

## 10. Risks and decisions

| Topic | Decision / note |
|---|---|
| SAF performance | Use raw `DocumentsContract` queries, cache results in Room, and scan incrementally. |
| Chapter-clipped playlist | Simplest way to get chapter-aware controls everywhere. Fallback is in §5.1. |
| Exotic codecs | ExoPlayer covers mp3/m4a/m4b/aac/ogg/opus/flac/wav/amr/mkv/webm. Add the FFmpeg extension in P3 for wma/ape/wv, and for alac on devices without a decoder. |
| EQ quality | Custom biquad processor, so behaviour is the same on every device. The system `Equalizer` is not used. |
| Writes into the user's folders | Sidecar on by default but tolerant of read-only trees. Never write anything other than `.mfbooks.json`, plus `info.txt` when the user saves a description. |
| AA policy | Media apps must not show settings or text input in the car. All configuration stays on the phone, which the design follows. |
| Copyright | Clean-room. No BASS, no reference assets. Chimes and icons are original or Material Symbols. |

---

## Appendix A: Evidence

Static analysis only; the APK was never executed. Tools: REA (JADX 0.7.1 headless engine) and aapt2 36.0.0.

| Finding | Evidence |
|---|---|
| Package, SDKs, permissions, manifest components | REA `inspect_android_package` `ev_363601bf82d164ca3b90f1d7245652f2c247da1a7a52e229728ab19d08425bf3` |
| App class inventory | REA `search_android_classes` `ev_f667d35e0ecc5d33f3d5a0e255b1782f479b5960e8cf0c8d65fcca01428a5e0b` |
| `BookData` fields | REA `inspect_android_class` `ev_d00bd2213b123631b86f525722ef298983d27a328ebcd40b795e9a40034e49fc` |
| AA browse tree (`__ROOT__/__NEW__/__STARTED__/__FINISHED__`) | REA `inspect_android_method` `ev_560fd4fcd5d83dec87ca90e10f6898f8201781bc4fdb0ed00e3223a1adec979e`, `ev_21cfced213c2697eae07994a4f48158061a4af2ea4b576fb4c845a90b0b558c4` |
| Bookmark XML format | REA `inspect_android_method` `ev_013657a56a8ae560431a790c63666f93db45d00456b6d3ae0778f03ac368f9dc` |
| Auto-rewind table, sleep fade/shake logic, AA custom actions, prefs keys and defaults, EQ bands, chapter sources | Full JADX decompile of the same APK (same SHA-256), classes `PlayerService`, `l6` (sleep), `x3` (ID3/OverDrive chapters), `chapters/Mp4AtomIdentifier` (`chpl`), `y7` (audio engine), `EqualizerLevels`, `PlayerSettings*Activity` |
| Feature strings | aapt2 `dump resources` (default locale) |

**Unknowns / not verified:**
- runtime UI appearance (no device was available; the ADB MCP failed to connect);
- the exact QuickTime chapter-track support beyond the `chpl` atom;
- the full headset multi-press defaults;
- the exact sleep-schedule semantics;
- how merge-folder state is persisted.

The design does not depend on any of these.
