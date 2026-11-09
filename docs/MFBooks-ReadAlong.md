# MFBooks Read-Along

**Status:** Implemented on `feature/read-along`, 2026-11-09. Not yet tested on a physical phone.
**Designs:** https://claude.ai/artifact/6SSSivasmku8CeYc9zYrR7
**Scope:** add a book's text (FB2, EPUB, TXT) to an audiobook, then scroll and highlight that text as the narration plays.

## Summary

| Question | Decision |
|---|---|
| Where is the text parsed? | `:shared` Kotlin parser, used by the app and the server. |
| Where is text matched to audio? | On the phone first, as a background job. The server only stores the result. A server engine is added only if the phone is too slow (§1.11). |
| How is it matched? | On-device speech recognition with word timings, then a text alignment that tolerates recognition errors. Automatic uses Vosk if the user downloaded it, otherwise Android's own recogniser where it qualifies (Android 14+), otherwise it offers the Vosk download. |
| Is AICore used? | Not for matching. It blocks background use and only gives word timings on real-time streams (§1.6). |
| What is stored? | One immutable text file and one sync map that links word positions to audio time. |
| What happens before matching finishes? | The reader works at once with an approximate sync and sharpens as matching progresses. |
| What decides whether this ships? | A short spike on two real books (§1.11, Phase 0). The phone speed below is not measured yet. |

---

# Part 1. Technical implementation plan

## 1.1 Pipeline

```
 file picker / book folder / web upload
              │
              ▼
 Parse: FB2 / EPUB / TXT ──▶ text.json.gz (immutable, addressed by its SHA-256)
              │
              ├──▶ Approximate sync map, available immediately
              │
              ▼
 AlignWorker: audio → 16 kHz PCM → speech engine → transcript (checkpointed)
              │
              ▼
 Align transcript to text ──▶ sync.json.gz (updated every ~30 min of audio)
              │
              ▼
 Reader: playback ms ──▶ word offset ──▶ highlight + scroll
```

The recogniser only needs to be good enough to produce anchors. The book text is already known, so a 70% accurate transcript still gives thousands of unambiguous word sequences to pin the timeline on. That is why a 45 MB model is enough and a large Whisper model is not needed.

## 1.2 Getting the text in

Four entry points, all ending in the same parser:

| Entry | Mechanism |
|---|---|
| Book screen | `OpenDocument` picker for `.fb2`, `.fb2.zip`, `.epub`, `.txt`. |
| Book folder | The scanner already sees every file in a book folder (`LibraryScanner.kt:153`). A text file there produces a suggestion. It never starts a job on its own. |
| Web player | Upload on the book page. The server parses it with the same code. |
| Zip import on server | Keep text files instead of dropping them (`Library.kt:192-195`, `:234-237`). |

Folder detection rules: extensions `fb2`, `epub`, `txt`, and `fb2.zip`. Exclude `info.txt`, `libby.txt`, and any `.txt` under 50 KB. Add these files to `Candidate.signature` (`Metadata.kt:38`) so that adding one triggers a rescan.

The original file is copied into MFBooks storage. A folder file stays where it is and is never modified.

## 1.3 Parsing

Add **Jsoup** to `:shared`. One dependency covers FB2 (XML mode) and malformed EPUB XHTML, runs on Android and the JVM, and never fetches external DTDs. Zip handling uses `java.util.zip`.

- **FB2:** decode with the existing `decodeText` (windows-1251 is common), remove `<binary>` blocks before parsing, then walk `<body>`, `<section>`, `<title>`, `<p>`, `<poem>`, `<cite>`, `<epigraph>`. The notes body becomes `kind: note` blocks.
- **EPUB:** read `META-INF/container.xml`, then the OPF spine in order. The TOC comes from `nav.xhtml` or `toc.ncx`.
- **TXT:** decode, split on blank lines. Detect headings with the same words the chapter parser treats as generic.
- **Text language:** FB2 `<lang>`, EPUB `dc:language`, otherwise a Cyrillic/Latin letter count, which is enough for the Russian and English targets. It is shown in the review sheet, not chosen there. The spoken language is checked separately (§1.6).
- **Sentences and words:** `java.text.BreakIterator`, computed once at parse time and stored so the web player needs no segmentation code.

Inputs are untrusted, on the server especially:

- Cap the file at 64 MB and total uncompressed zip content at 256 MB.
- Read zip entries in memory only. Nothing is extracted to disk, so path traversal does not apply.
- Reject EPUBs with non-font entries in `META-INF/encryption.xml`, with a message saying the book is DRM-protected. MFBooks does not remove DRM.

Images are dropped in v1 and shown as a small "Illustration" marker. EPUBs that already contain Media Overlays are treated as plain EPUBs. Add both when someone misses them.

## 1.4 Stored formats

**`text.json.gz`** is immutable once written. All offsets are UTF-16 positions in the concatenation of `blocks[].s`.

```json
{
  "v": 1,
  "lang": "ru",
  "title": "…", "author": "…",
  "source": { "name": "book.fb2", "format": "fb2", "sha256": "…" },
  "toc": [ { "title": "Глава 1", "block": 12 } ],
  "blocks": [
    { "k": "h1", "s": "Глава 1" },
    { "k": "p", "s": "Текст абзаца…", "sen": [0, 41, 96], "em": [[12, 19]] }
  ]
}
```

`k` is one of `h1`, `h2`, `h3`, `p`, `quote`, `poem`, `note`, `image`.

**`sync.json.gz`** points at one exact text version.

```json
{
  "v": 1,
  "textSha": "…",
  "tier": "aligned",
  "engine": "vosk-small-ru-0.22",
  "tracks": [ { "name": "01.m4b", "durationMs": 58253080 } ],
  "chars": [ 4, 7, 5 ],
  "ms": [ 1200, 410, 380 ],
  "ranges": [ { "fromChar": 0, "toChar": 8123456, "quality": 0.93 } ],
  "user": [ { "char": 90211, "ms": 1820400 } ]
}
```

`chars` and `ms` are delta-encoded parallel arrays, one entry per matched word. In memory they become two `IntArray`s, roughly 1.2 MB for a 150,000-word book. Lookup is a binary search. Every sync tier uses this same structure:

- **Approximate:** a few knots, at the book's ends and matched chapters.
- **Aligned:** one knot per word.
- **User fix:** one extra knot, always honoured.

Sizes are small. Expect 0.3 to 1 MB for text and 0.5 to 2 MB for the map on a typical novel. The per-book total stays well under 5 MB.

**Audio identity.** `sha256` is not usable because conversion changes it, and `Book.id` changes if the folder moves. A map is valid when the `tracks` list matches the current tracks by position and each duration is within 1.5 s. On load, knots are rebased per track using current track starts, so Opus conversion error (up to 500 ms per file, `FileConverter.kt:122`) doesn't accumulate across files. If the list doesn't match, the status becomes "audio changed".

## 1.5 Where files live

| Location | Contents | Why |
|---|---|---|
| `filesDir/text/<bookId>/` | `source.*`, `text.json.gz`, `sync.json.gz`, `transcript.gz` | Works for every book, cloud-only included. |
| Book folder, when writable | `.mfbooks.text`, `.mfbooks.sync`, `.mfbooks.transcript` | Matches the `.mfbooks.json` principle. Hours of compute must survive a reinstall or a folder move. |
| Server: `books/<uuid>/text/` | `source.*`, `text.json.gz`, `sync.json.gz` | Other devices and the web player. |

The transcript is independent of the ebook. It is kept so a different edition of the text can be aligned in seconds without repeating recognition.

**Room** adds one table, version 3:

```kotlin
@Entity data class BookText(
  @PrimaryKey val bookId: String,
  val sourceName: String, val format: String, val lang: String,
  val textSha: String, val words: Int,
  val state: String,            // PARSED, QUEUED, ALIGNING, READY, NO_MATCH, AUDIO_CHANGED
  val syncTier: String,         // APPROXIMATE, ALIGNED
  val coverage: Float, val syncedAudioMs: Long,
  val offsetMs: Int = 0, val updatedAt: Long,
)
```

**A hand-written `MIGRATION_2_3` is mandatory.** The database is built with `fallbackToDestructiveMigration(true)`, so forgetting it wipes the library.

## 1.6 Matching text to audio

`AlignWorker` follows `ConvertWorker`: a WorkManager foreground job with a progress notification.

**Speech engines.** Each engine turns a chunk of 16 kHz PCM into `(word, startMs)` pairs. Everything after that is shared.

| Engine | Needs | Word timings | File input | Long background job | Use |
|---|---|---|---|---|---|
| **Android on-device** `SpeechRecognizer.createOnDeviceSpeechRecognizer` | Android 14+ for timings. A language pack for ru-RU or en-US. | Yes, `EXTRA_REQUEST_WORD_TIMING`, API 34 | Yes, `EXTRA_AUDIO_SOURCE`, API 33 | Not documented, spike must test | First choice where it passes the probe |
| **Vosk small** | Any supported phone. An optional download: 14 MB of native libraries plus a 40–45 MB model per language. None of it ships in the APK. | Yes | We feed PCM directly | Yes | Used by Automatic whenever it is installed |
| **ML Kit GenAI Advanced** (AICore) | Pixel 10 or 11, or listed flagship chips. Locked bootloader. | Streaming only | ONE_SHOT file input throws if timings are on | No. GenAI inference is foreground-only, with a per-app quota. | Not used |
| **ML Kit GenAI Basic** | API 31+. Russian is beta. Alpha library. | Streaming only | Same limit | Unconfirmed | Not used. It's described as similar to `SpeechRecognizer`, which needs no dependency. |

**Why AICore is out.** Matching a 16-hour book takes hours. The ML Kit GenAI overview says inference is allowed only while the app is the top foreground app, and a foreground service gets `BACKGROUND_USE_BLOCKED`. It also enforces per-app quotas. That text covers GenAI inference in general and the speech page doesn't repeat it, so the spike confirms it if a supported phone is available. Word timings need a stream fed at about real time, which would mean about 16 hours per 16-hour book even with no other limits. Whole-file mode is fast but rejects timings. Revisit AICore if Google lifts these limits.

**Android engine caveats.** The docs say several of these extras "may have no effect" depending on the recogniser, and the general `SpeechRecognizer` docs say it isn't intended for continuous recognition. So the engine is never trusted on API level alone:

- `checkRecognitionSupport` and `triggerModelDownload` handle the language pack.
- Before a job, a **60-second probe** on the book's own audio must return word timings that increase, match the book text, and run faster than the required speed. A failed probe falls back to Vosk without bothering the user.
- Use `EXTRA_SEGMENTED_SESSION` keyed on `EXTRA_AUDIO_SOURCE`, one session per 5-minute chunk. Timestamps are relative to the session, so word time is `chunkStartMs + getTimestampMillis()`.
- `SpeechRecognizer` must be created on the main thread and destroyed after each session.
- Check whether `RECORD_AUDIO` is needed with file input. The app doesn't request it today, and asking for the microphone would be a poor trade. If it's required, the Android engine stays off by default.

**Spoken language.** The user doesn't pick a language. A manual override stays on the Text and sync screen for when detection is wrong. On Android 14+, the job sets `EXTRA_ENABLE_LANGUAGE_DETECTION` with `EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES = [ru-RU, en-US]`, sampling 30 seconds at 10%, 50% and 80% of the book. Only `CONFIDENT` or better results count. The result:

- Chooses the recognition language and updates the book, overriding a wrong language tag in the file.
- Warns before hours of work if the audio and text languages differ, for example an English ebook with a Russian narration.
- Where detection is unavailable, the text language is used and the early-stop rule below catches a mismatch.

**Steps:**

1. **Engine and model.** Apply the speech engine setting. The job never downloads Vosk. If it needs Vosk and it is missing, the book card offers the download. Settings → Reading → Speech models downloads and deletes it per language, and deleting the last language removes the libraries too. Models are `vosk-model-small-ru-0.22` (45 MB) and `vosk-model-small-en-us-0.15` (40 MB), Apache 2.0. The native libraries come from the Vosk and JNA AARs on Maven Central. All of these are checked against pinned SHA-256 hashes. Android language packs download through the system.
2. **Decode.** `MediaExtractor` and `MediaCodec` to 16 kHz mono, reusing the convert decoder and `Resampler`. Remote tracks open with the Bearer header already used for playback.
3. **Order.** Start at the listener's current position, go forward, then wrap around. The part of the book they're about to hear gets good sync first.
4. **Recognise.** Append words to the transcript with a checkpoint per 5 minutes of audio. Work resumes from the last checkpoint.
5. **Align.** A pure function in `:shared`, re-run every ~30 minutes of newly transcribed audio. It takes seconds, so the reader improves progressively.

**Alignment function:**

1. Normalise both sides: NFKC, lowercase, `ё` → `е`, strip punctuation.
2. Find 5-word sequences that appear exactly once in both the book and the transcript.
3. Keep the longest increasing subsequence of those anchors. Moved or repeated passages drop out here, in O(n log n).
4. Between adjacent anchors, run word-level edit distance. The gaps are short, which avoids the book-sized dynamic programming table that makes plain Needleman-Wunsch impractical.
5. Book words with no match get interpolated times inside their gap.
6. Gaps with no anchors for over 90 s of audio or 300 words become unsynced ranges, which covers music, publisher intros, abridged passages and front matter.
7. `quality` for a range is the matched-word ratio.

Required check: one `:shared` unit test that corrupts a known word/time list (random substitutions, deletions, a shuffled paragraph, an inserted intro) and asserts the timing error stays inside a bound.

**Limits to design around:**

- Android 15+ allows `mediaProcessing` foreground services 6 hours per 24 hours, and the same again for `dataSync`, per type. The budget resets when the user opens the app. A long book can hit this, so `onStopped` must checkpoint and reschedule. Do not treat it as a failure.
- A small Vosk model needs about 300 MB of memory while running. Run one or two recognisers. Back off on `PowerManager` thermal status `SEVERE` with either engine.
- Vosk's Java classes ship, but `libvosk.so` and `libjnidispatch.so` are excluded from the APK and loaded from app storage. The AAR versions in `ModelStore` must match `app/build.gradle.kts`.

**Default constraints:** while charging, or above the existing battery threshold. The user can choose "Start now".

**Outcome rules** (thresholds to be tuned in the spike):

| Result | Status shown |
|---|---|
| Book coverage ≥ 80% | Ready |
| 50 to 80% | Ready, unsynced parts listed |
| Under 50% after the first hour of audio | Stop early: "Text doesn't match the audio" |

## 1.7 Approximate sync

Computed in the same step as parsing, without a model:

- If audio chapter titles match TOC titles for at least 70% of chapters, create a knot at each match.
- Otherwise use only the book's start and end.
- Between knots, time is proportional to character count.
- The highlight is limited to the paragraph, with an "Approximate" label.

The user's "Narrator is here" action adds a knot and corrects the region at once. This tier also gives users something to read during a job that may take hours.

## 1.8 Reader

**Time source.** `PlayerConnection.nowPlaying.positionInBookMs` updates every 250 ms while playing. The reader extrapolates between updates inside `withFrameNanos`, using `speed` and `isPlaying`. Polling stays as it is, and the reader follows remote playback through the same state.

```
lookupMs = positionInBookMs + extrapolation + offsetMs + 100
```

The fixed 100 ms lead exists because readers notice a highlight that lags more than one that leads slightly. It can be tuned in the spike.

**Rendering.** `LazyColumn` with one item per block, one `Text` each. The highlight is drawn in `Modifier.drawBehind` from `TextLayoutResult.getPathForRange`, so word changes redraw without re-layout or recomposing the list. This is new territory for the app (no `AnnotatedString` styling exists yet).

**Scrolling.** Follow mode keeps the current line at about 35% of the viewport. Any drag switches to free scrolling and shows "Back to narration".

**Tap to seek** uses `seekInBook`, which already logs a `HistoryEvent`, so undo comes free.

**Placement in `FullPlayer`.** The cover slot at `FullPlayer.kt:206` is the one flexible area. Text replaces it. Inside text mode, the swipe gestures on the cover are disabled so vertical scrolling works.

**To check in the spike:** that the position ExoPlayer reports with `skipSilenceEnabled` stays in media time. The code survey did not cover it.

**Android Auto:** no text.

## 1.9 Server and sync

Text bytes are blobs. Metadata goes through the existing record sync.

- New record kind `text`, carrying `{textSha, syncSha, lang, words, tier, coverage, offsetMs, sourceName}`.
- The server rejects unknown kinds with 400 (`Sync.kt:76`), so the server must ship first. Add `features: ["text"]` to the server info response and have the app send `text` records only when it is present.
- The app ignores kinds it doesn't handle during pull only if `applyOne` has a fallthrough. Verify this against the `when` at `app/.../server/Sync.kt:255` before release.

```
PUT    /api/v1/books/{id}/text/source      raw file, 64 MB cap. Parses if no content exists.
PUT    /api/v1/books/{id}/text/content     text.json.gz from the app
GET    /api/v1/books/{id}/text/content     ETag = textSha
GET    /api/v1/books/{id}/text/source
GET    /api/v1/books/{id}/text/sync        ETag = syncSha
PUT    /api/v1/books/{id}/text/sync        If-Match; last writer wins
DELETE /api/v1/books/{id}/text
```

**Rule:** the first `text.json.gz` produced for a book is the one every device uses. Parser upgrades never rewrite it, because offsets in the sync map depend on it. The app uploads both source and content. The server parses only for web uploads.

Text works for any book with a `serverId`, including books whose audio was never uploaded.

**Web player:** a text view in `FullPlayer`, driven by `view.bookMs`, with highlight spans updated from `requestAnimationFrame`. The web can upload text and show approximate sync. Accurate alignment needs a phone until Phase 4.

## 1.10 Files touched

| Area | Files |
|---|---|
| New in `:shared` | `text/TextParser.kt`, `text/TextModel.kt`, `text/Aligner.kt`, `text/SyncMap.kt`, one test each for parser and aligner |
| New in app | `text/TextStore.kt`, `text/AlignWorker.kt`, `text/SpeechEngines.kt` (Android and Vosk), `text/ModelStore.kt`, `ui/reader/Reader.kt`, `ui/reader/ReaderSheets.kt`, `ui/book/TextPanel.kt` |
| Changed in app | `Entities.kt`, `AppDatabase.kt` (migration), `Prefs.kt`, `Metadata.kt`, `Sidecar.kt`, `FullPlayer.kt`, `BookDetail.kt`, `SettingsPages.kt`, `server/Sync.kt`, `AndroidManifest.xml` |
| Changed on server | `Sync.kt` (`Kind.ALL` in shared), `Library.kt`, `Main.kt`, `API.md` |
| Changed on web | `api.ts`, `bar.tsx`, `pages.tsx`, new `reader.tsx` |

## 1.11 Phases

| Phase | Delivers | Exit condition |
|---|---|---|
| **0. Spike** | The Android engine, then Vosk, with the aligner, on one real Russian and one real English book, on a real phone. | Go/no-go on the numbers below, and which engine is the default. |
| **1. Reader** | Parser, storage, Room migration, reader, approximate sync, management screen. Local only. | A real FB2 and EPUB read comfortably, 60 fps while highlighting. |
| **2. Alignment** | `AlignWorker`, model downloads, progressive updates, failure handling. | Spike targets hold on three more books. |
| **3. Sync and web** | Server endpoints, record kind, web reader, web upload. | Aligned on phone, read in browser. |
| **4. Server engine (only if needed)** | Run recognition on the server. | See below. |

**Spike targets.** These are goals, not measurements. I found no reliable benchmark for this workload.

| Metric | Target |
|---|---|
| Recognition speed on a mid-range phone | At least 4× realtime (16 h book in under 4 h) |
| Battery | Finishes on one overnight charge without thermal throttling |
| Word timing error | Median under 300 ms |
| Coverage on a matching edition | At least 90% of narrated text |

**First results, Android 14 x86_64 emulator, 2026-11-09:**

| Check | English, 13 min LibriVox | Russian, 20 min LibriVox |
|---|---|---|
| Android engine | File input worked without `RECORD_AUDIO`, 60 s of audio took about 2 s, text was good, but no word timings (`RECOGNITION_PARTS` absent), so the probe fell back to Vosk | No on-device ru-RU recogniser, fell back to Vosk |
| Vosk small | 13 min in about 80 s, 86% of book words matched, 99% of audio synced | Whole 20 min text synced, 85% of book words matched |

Emulator speed says little about a phone.

**Pixel 9 Pro, Android 17, English 13 min book, 2026-11-09:**

| Engine | Time for the book | Words heard | Book words matched | Synced audio |
|---|---|---|---|---|
| Android on-device, 5× feed | 3 min 37 s | 1,925 | 80% | 99% |
| Vosk small | about 2 min at most | 2,159 | 86% | 99% |

Russian 20 min book, same phone:

| Engine | Time for the book | Words heard | Book words matched | Synced audio |
|---|---|---|---|---|
| Android on-device, 5× feed | 5 min 50 s, including a 47 MB language pack | 2,023 | 73% | 100% |
| Vosk small | under 3 min, including its model download | not counted | 85% | 100% |

The reader on this phone: median frame 9 ms, 2.6% janky frames, highlight and playhead aligned.

Word timing between the two engines differs by a median of 110 ms. Feeding the recogniser with no pacing lost about 90% of the speech, which is why the feed is limited. File input works without `RECORD_AUDIO`. The app must declare `android.speech.RecognitionService` in `<queries>`, or the recogniser looks unavailable.

**Android engine questions the spike must answer**, separately for ru-RU and en-US:

1. Is `EXTRA_AUDIO_SOURCE` honoured, or does it silently open the microphone?
2. Are word timings present and accurate in segmented sessions?
3. Does it run faster than real time, or is the input paced?
4. Does it keep working with the screen off under a foreground service?
5. Is `RECORD_AUDIO` required?
6. Is there a session length limit, and do segments drop silence from the timeline?
7. Does language detection work on file input?

If a Pixel 10/11 or a listed flagship is available, also confirm the AICore foreground-only block and whether a stream can be fed faster than real time.

**Phase 4 trigger.** Build it only if the phone misses the speed target or if cloud-only libraries make phone processing awkward. The server needs real changes: a glibc base image instead of Alpine (the Vosk JAR ships glibc libraries), memory beyond today's `-Xmx256m` configuration, and a job added to the existing `Converter` pattern.

**Alternatives rejected for now:**

- Whisper (word timings are less direct, heavier on phones, and Whisper can hallucinate during silence and music). It remains the fallback if Vosk accuracy on Russian falls short.
- aeneas (AGPL, its authors say it wasn't designed for word-level alignment).
- CTC forced alignment, which is what Storyteller v3 moved to. Strong accuracy, but much more work. Worth revisiting if word timing misses the target.

---

# Part 2. User requirements

## 2.1 Who and why

The listener owns DRM-free audio and text for the same book. They want to follow the words while listening, glance at a name they couldn't catch, or switch between reading and listening without losing their place.

## 2.2 What users can do

**P0: first release (Phases 1 and 2)**

| # | Requirement |
|---|---|
| R1 | Add a text file to any book from the book screen. |
| R2 | Be offered a text file found in the book's folder. |
| R3 | See what was parsed (title, author, detected language, words, chapters) before anything starts. |
| R3a | Be offered Vosk, with its benefits and an on-the-spot download, wherever matching can start. |
| R4 | Read immediately, before matching finishes, with a clear "Approximate" label. |
| R5 | Choose when matching runs: now, or when charging. |
| R6 | See progress on the book screen and in a notification. Pause, resume or cancel it. |
| R7 | Switch between cover and text in the player without interrupting playback. |
| R8 | See the current word and sentence highlighted, with text scrolling to follow. |
| R9 | Scroll freely while audio continues, then return with one tap. |
| R10 | Tap a sentence to play from it, with undo. |
| R11 | Long-press a sentence to play from here, bookmark it, copy it, or mark "Narrator is here". |
| R12 | Change the font, size, spacing, margins, alignment and highlight style. |
| R13 | Replace or remove the text, or match it again. Removing never touches audio or the original file. |
| R14 | See which chapters are synced and which aren't. |
| R15 | Nudge sync earlier or later, per book. |
| R16 | Get a clear message and a next step for every failure (§2.5). |
| R17 | Keep using text and sync after reinstalling the app, when book folders are writable. |

**P1: with the server (Phase 3)**

| # | Requirement |
|---|---|
| R18 | Text and sync appear on all paired devices without repeating the work. |
| R19 | Read along in the web player. |
| R20 | Upload text from the web player. |

**P2: later, if wanted**

Search inside the text, tappable footnotes, illustrations, reading ahead without audio, EPUB Media Overlays import, generated transcript when no ebook exists.

**Out of scope:** DRM removal, editing the book text, PDF (text order is unreliable), buying or downloading ebooks, Android Auto text.

## 2.3 Settings

**Reader, global, in the Aa sheet and Settings → Reading**

| Setting | Options | Default |
|---|---|---|
| Font | Literata, Device serif, Device sans | Literata |
| Text size | 14 to 28 sp | 19 sp |
| Line spacing | Compact, Normal, Relaxed | Normal |
| Margins | Narrow, Normal, Wide | Normal |
| Alignment | Left, Justified (hyphenated) | Left |
| Highlight | Playhead, Band, Word only, Off | Playhead |
| Dim narrated text | On, Off | On |
| Follow narration | On, Off | On |
| Keep screen on while reading | On, Off | On |
| Tap on text | Play from here, Do nothing | Play from here |

Theme stays in Appearance. The reader follows the existing Light, Dark, Black and cover colours.

**Matching, global, in Settings → Reading**

| Setting | Options | Default |
|---|---|---|
| Run matching | While charging, Charging or battery above threshold, Any time | While charging |
| Speech engine | Automatic, Android, Vosk. Android is hidden on phones that can't use it. Vosk can't be selected until a language is downloaded. | Automatic |
| Offer text found in book folders | On, Off | On |
| Save text and sync in book folders | On, Off | On |
| Vosk languages | Each language shows Downloaded or Not downloaded, with download or delete | none downloaded |

**Per book, on the Text & sync screen**

Language, sync offset (±5 s in 100 ms steps), Match again, Replace, Remove.

## 2.4 Flows

**Add from the book screen**

1. Book screen → Text panel → **Add book text**.
2. System file picker.
3. Review sheet: title, author, language, word and chapter counts. A warning appears if the title or author differs from the audiobook.
4. Choose **Match while charging** (default) or **Match now**. First use of a language shows the model download size.
5. Reader is available at once in Approximate mode. The Text panel shows progress.
6. Notification on completion. The label clears and word highlighting turns on.

**From the book folder**

The Text panel shows "`book.fb2` found in this folder" with **Use this file** and **Dismiss**. It continues from step 3 above.

**Read along**

Player → **Text**. The text is positioned at the narration. Scroll away and the highlight continues out of sight, with **Back to narration** showing. The controls can retreat to a small bar for a larger reading area.

**Fix a wrong spot**

Long-press a sentence → **Narrator is here** → that region snaps into place and the correction stays through later matching.

**Manage**

Book screen → Text panel → **Manage**: status, per-chapter coverage, offset, language, Match again, Replace, Remove.

**Replace**

Pick a new file → review sheet → matching against the saved transcript, which takes seconds if the audio is unchanged.

**Remove**

Confirmation lists exactly what will be deleted and states that audio and the original file are kept. Bookmarks are time-based, so they survive.

**Audio changes**

Status becomes **Audio changed**. Text remains readable with Approximate sync. Offers **Match again**.

**Web**

Book page → **Add text** → drag or choose a file → readable straight away with Approximate sync. The page explains that accurate sync comes from the phone app.

## 2.5 Error and edge states

| Situation | User sees | Actions |
|---|---|---|
| DRM-protected EPUB | "This book is DRM-protected. MFBooks can only read DRM-free files." | Choose another file |
| Unreadable or empty file | "MFBooks couldn't find any book text in this file." | Choose another file |
| File too large | "This file is over the 64 MB limit." | Choose another file |
| Title or author differ | "This text may be a different book." with both shown | Continue, Choose another |
| Text doesn't match audio | "Text doesn't match the audio" with the matched percentage | Replace, Change language, Keep approximate sync |
| Audio and text languages differ | "Audio is in English, text is in Russian" | Replace, Continue |
| Unsupported language | "Word matching isn't available for this language yet. Approximate sync still works." | Keep approximate sync |
| No network for model | "Connect to download the 45 MB speech model." | Retry |
| Job interrupted | Shown as paused with the reason. Resumes automatically. | Start now, Cancel |
| Low storage | Shown before download. | Free up space |
| Unsynced range during playback | Highlight hides. "Not in the text" label shows until narration returns. | None needed |

---

# Part 3. Interface designs

The designs are on the canvas linked at the top of this document. They include a working prototype of the read-along highlight.

| Group | Screens |
|---|---|
| Read along | Player text view (interactive), focused reader, approximate sync in light theme, sentence menu, reading settings |
| Add text | Book screen suggestion, review sheet, matching in progress, problem states |
| Manage | Text & sync screen, Settings → Reading |
| Web | Web player read-along |

## Sources

- [Storyteller v3 aligner](https://runtimewire.com/article/storyteller-v3-ctc-aligner-immersive-reading)
- [aeneas](https://pypi.org/project/aeneas-ng/)
- [Calliope paper on highlight lag and aligner drift](https://arxiv.org/html/2602.10735)
- [Vosk models](https://alphacephei.com/vosk/models)
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent), [RecognitionPart](https://developer.android.com/reference/android/speech/RecognitionPart)
- [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) and [GenAI background and quota rules](https://developers.google.com/ml-kit/genai)
- [Android foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
