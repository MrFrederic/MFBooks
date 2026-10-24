# MFBooks Server

An optional, self-hosted companion for the MFBooks app, for one owner:

- Sync and backup of progress, bookmarks, book state, per-book settings, history, stats and app settings
- A cloud library: upload books from the app or the browser; they are converted to Opus (32 kb/s) in the background
- A web player with chapters, speed, sleep timer and EQ, and Spotify Connect–style handoff between phone and PC

## Run

```bash
docker run -d --name mfbooks -p 8080:8080 -v mfbooks-data:/data ghcr.io/mrfrederic/mfbooks-server:latest
```

Open `http://<host>:8080` and create the owner account. The server speaks plain HTTP on one port; put your own
reverse proxy or tunnel (for example Cloudflare Tunnel) in front of it for TLS and a public hostname.

| Variable | Default | |
|---|---|---|
| `MFBOOKS_PORT` | `8080` | listening port |
| `MFBOOKS_DATA` | `/data` | database, books and covers |

**Backup**: copy the data volume (one SQLite file plus a folder of files), or use *Settings → Export* for everything but the audio.

**Forgotten password**: click *Forgot password* on the sign-in page; a one-time code (valid 15 minutes) is printed to the server log (`docker logs mfbooks`).

**Linking the phone**: *Devices → Link a phone* shows a QR code and a code for the app (its Server settings arrive with the next app release).

## Develop

```bash
MFBOOKS_DATA=$PWD/.tmp/server ./gradlew :server:run      # API on :8080 (needs ffmpeg on PATH)
cd server/web && npm install && npm run dev             # web player with hot reload, proxied to :8080
./gradlew :server:test                                  # end-to-end test (needs ffmpeg)
docker build -f server/Dockerfile -t mfbooks-server .   # the image
```

The API is described in [API.md](API.md). Code shared with the app (metadata parsers, sync types and merge rules) lives in `shared/`.
