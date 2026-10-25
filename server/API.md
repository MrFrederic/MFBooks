# MFBooks Server API (v1)

All paths are under `/api/v1`. JSON in and out. Times are epoch milliseconds (server clock unless noted).
Errors: HTTP status + `{"error": "message"}`.

## Auth

Two ways to authenticate, both backed by one device row each:

- Browser: HttpOnly cookie `mfb` (SameSite=Strict), set by `setup` and `auth/login`.
- App: `Authorization: Bearer <token>` from `auth/login` (kind `android`) or `devices/pair`.

Every endpoint needs auth except `info`, `setup` (until done), `auth/login`, `auth/reset*`, `devices/pair` and signed media links.

| Method | Path | Body → Response |
|---|---|---|
| GET | `/info` | → `{name, version, apiVersion: 1, minAppVersion, setupDone}` |
| GET | `/setup` | → `{setupDone}` |
| POST | `/setup` | `{username, password, serverName, convert}` → `{deviceId}` + cookie. 409 once done |
| POST | `/auth/login` | `{username, password, deviceName, kind: "web"\|"android"}` → `{deviceId, token?}` (token only for android; web gets the cookie). 401 bad credentials, 429 locked out |
| POST | `/auth/logout` | → `{}`; revokes the calling device |
| POST | `/auth/reset/request` | → `{}`; prints a one-time code to the server log (valid 15 min) |
| POST | `/auth/reset` | `{code, password}` → `{}`; signs out every browser |
| GET | `/me` | → `{username, deviceId, deviceName, kind}` |

## Devices

| Method | Path | Body → Response |
|---|---|---|
| GET | `/devices` | → `[{id, name, kind, lastSeen, online, current}]` |
| DELETE | `/devices/{id}` | → `{}` |
| POST | `/devices/pairing` | → `{code, expiresAt}`; the web UI shows a QR of `mfbooks://pair?server=<origin>&code=<code>` |
| POST | `/devices/pair` | `{code, deviceName}` → `{deviceId, token}` (public, single use, 10 min) |

## Settings and storage

| Method | Path | Body → Response |
|---|---|---|
| GET | `/settings` | → `{serverName, convert, storageWarnGb, usedBytes, freeBytes, storageLow}` |
| PUT | `/settings` | `{serverName?, convert?, storageWarnGb?}` → same as GET |
| GET | `/export` | → zip: `mfbooks.db` snapshot, `records.json`, `covers/` (audio is not included) |

## Library

`BookSummary`:
```
{ id, title, author, narrator, series, seriesIndex, year, genre, durationMs, seedColor, state ("NEW"|"STARTED"|"FINISHED"),
  addedAt, lastPlayedAt, hidden, updatedAt, cover: bool,
  cloud: null | { files, ready, bytes, verified, conversion: "none"|"queued"|"converting"|"done"|"failed", progress (0..1), error? },
  progress: null | { trackIndex, positionInTrackMs, positionInBookMs, updatedAt, seq } }
```
`cloud` is null for a book the phone synced but never uploaded. `verified` = every original's SHA-256 matched on arrival.

`BookDetail` = `BookSummary` + `{ description, publisher, tracks: [Track], chapters: [Chapter], settings: Settings|null, bookmarks: [Bookmark], conflict: null|Conflict }`
- Track: `{index, fileName, title, durationMs, sizeBytes, sha256, cloud: null | {state: "receiving"|"ready", format, bytes, received}}`
- Chapter: `{index, title, trackIndex, startInTrackMs, durationMs, startInBookMs}`
- Settings: `{speed, savedToggleSpeed, pitch, boostMb, eqBandsDb ("0,0,0,0,0" for 60/230/910/4k/14k Hz), balance, mono, skipSilence, skipStartMs, skipEndMs, repeatMode}` (each nullable)
- Bookmark: `{id, trackIndex, positionMs, title, note, createdAt}`
- Conflict: `{deviceName, trackIndex, positionInTrackMs, updatedAt}`: another device's offline position; resolve by pushing a `progress` change with `base` = the current seq

| Method | Path | Body → Response |
|---|---|---|
| GET | `/books` | → `[BookSummary]` (not deleted) |
| GET | `/books/{id}` | → `BookDetail` |
| GET | `/books/{id}/cover` | → image |
| POST | `/books/{id}/cover` | raw image body (≤ 10 MB, Content-Type image/jpeg, png or webp) → `{}` |
| POST | `/books` | app upload: `{id?, data: BookData}` (see Sync) → `{id}`; creates the record and an empty cloud file per track |
| GET | `/books/{id}/files/{index}/offset` | → `{offset, size}` for resuming |
| POST | `/books/{id}/files/{index}` | header `Upload-Offset: n`, raw body chunk (≤ 16 MB) → `{offset}`; on the last chunk the SHA-256 is checked: 422 and offset reset to 0 on mismatch |
| GET, HEAD | `/books/{id}/files/{index}` | stream/download with `Range`; auth or `?exp=&sig=` |
| POST | `/books/{id}/links` | → `{files: [url], cover: url, expiresAt}` signed links (6 h) for Cast |
| DELETE | `/books/{id}/files` | delete the cloud copy only; the record, progress and stats stay |
| DELETE | `/books/{id}` | delete the book everywhere (tombstone) |

### Web or "Add to cloud" upload (audio file or zip of a book folder)

| Method | Path | Body → Response |
|---|---|---|
| POST | `/uploads` | `{name, size}` → `Upload` |
| GET | `/uploads/{id}` | → `Upload` |
| POST | `/uploads/{id}` | header `Upload-Offset: n`, raw body chunk (≤ 16 MB) → `Upload` |

`Upload` = `{id, name, size, offset, state: "receiving"|"importing"|"done"|"failed", bookId?, error?}`. When `offset == size` the server imports in the background; poll `GET /uploads/{id}` or wait for the `book` live event.

## Sync

```
Change = { kind, id, bookId?, updatedAt, base?, deleted?, data: {...},   // pushed by clients
           seq, deviceId, conflict? }                                     // added by the server
POST /sync  {since, now, changes: [Change]} → {seq, now, changes: [Change]}
GET  /sync?since=N                          → {seq, now, changes: [Change]}
```
- `now` is the client's clock; the server corrects pushed `updatedAt` by the measured offset.
- `data` is merged shallowly into the stored record, so a client may push only the fields it changed (`{state: "FINISHED"}`). An explicit `null` clears a field.
- Last writer wins by `updatedAt` for `book`, `progress`, `book_settings`, `bookmark`, `app_settings`. `history` and `session` are a union by id; a history event never changes once written, a session (which grows while it lasts) is last writer wins. Deletions (`deleted: true`) are kept as tombstones.
- `base` = the highest seq the client had seen for that record. A `progress` push whose base is stale, from another device, more than 60 s away from the stored position, is stored but the loser is kept as `conflict`.
- The response holds every record with `seq > since`, including the caller's own.

| kind | id | data (mirrors the app's Room entities) |
|---|---|---|
| `book` | book UUID | `{title, author, narrator, year, genre, publisher, series, seriesIndex, description, durationMs, seedColor, state, addedAt, lastPlayedAt, queuePosition, hidden, tracks: [{index, fileName, title, durationMs, sizeBytes, sha256}], chapters: [Chapter]}` |
| `progress` | book id | `{trackIndex, positionInTrackMs}` |
| `book_settings` | book id | Settings |
| `bookmark` | UUID | `{trackIndex, positionMs, title, note, createdAt}` (bookId set) |
| `history` | UUID | `{action, trackIndex, positionMs, at}` (bookId set) |
| `session` | UUID | `{bookTitle, bookPath, startedAt, listenedMs}` (bookId set) |
| `app_settings` | `"app"` | device-independent prefs, e.g. `{rewindSmallSec, fwdSmallSec, sleepMinutes, theme, ...}` |

## Live: `GET /live` (WebSocket)

Text frames, JSON, discriminated by `t`. A device with no frame for 15 s is offline.

Client → server:
- `{t: "state", bookId, title?, trackIndex, positionMs, speed, playing, sleepAt?, volume?}`: on every change and every 5 s while playing. `volume` is the device's media volume, 0..1.
- `{t: "ping"}`: every 5 s when nothing else was sent.
- `{t: "cmd", id, target, action, ...}`: `action` is one of
  - `play` `{bookId?, trackIndex?, positionMs?, speed?}`: with a book = load it there and play (handoff)
  - `pause`, `seek` `{trackIndex, positionMs}`, `skip` `{ms}` (±), `speed` `{speed}`, `sleep` `{minutes}` (null = off, -1 = end of chapter), `volume` `{volume}` (0..1)
- `{t: "ack", id, ok, error?}`: reply to a received cmd.

Server → client:
- `{t: "hello", deviceId, now}`
- `{t: "devices", now, devices: [{id, name, kind, online, active, state?: {...state, at}}]}`: on any change. `active` = the device that last started playback; `at` = server time the state arrived (extrapolate the position while playing).
- `{t: "cmd", id, from, action, ...}`: forwarded command; reply with `ack`.
- `{t: "ack", id, ok, error?}`: the target's reply, or `{ok: false, error: "offline"}` from the server.
- `{t: "pause", by, state}`: another device started playing; pause, and if it is the same book take its position.
- `{t: "changed", seq}`: new sync data; pull.
- `{t: "book", id}`: a book's cloud or conversion status changed; refetch it.
