package app.mfbooks.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.images.WebImage
import app.mfbooks.server.Server
import app.mfbooks.server.num
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/*
 * Chromecast (§ item 38). The session's player is Media3's CastPlayer, which switches between ExoPlayer and the
 * receiver (Default Media Receiver) when a Cast session starts/ends. Local files reach the receiver through
 * [MediaServer]. Chapters stay chapter items on the receiver too: each queue item is the whole file with
 * startTime/playbackDuration (Cast's clipping), and [ClipPlayer] turns the receiver's absolute file positions
 * back into chapter-relative ones, so the UI, ProgressSaver and commands see the same playlist either way.
 */

/** Default Media Receiver; Media3's session is the only media session/notification (Cast's own are off). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object CastSupport {
    private const val TAG = "Cast"

    /** Starts loading the CastContext once. False when Cast can't work here (no Play Services, …). */
    fun init(context: Context): Boolean = runCatching {
        val cast = androidx.media3.cast.Cast.getSingletonInstance(context.applicationContext)
        if (cast.needsInitialization()) cast.initialize()
        cast.castContextLoadFailure?.let { Log.i(TAG, "Cast unavailable: $it"); false } ?: true
    }.getOrElse { Log.i(TAG, "Cast unavailable: $it"); false }

    /** CastPlayer over [local], or null when Cast isn't available (then the app just plays locally). */
    fun player(context: Context, local: Player, server: MediaServer): CastPlayer? {
        if (!init(context)) return null
        return runCatching {
            val converter = CastItemConverter(server)
            CastPlayer.Builder(context)
                .setLocalPlayer(local)
                .setRemotePlayer(androidx.media3.cast.RemoteCastPlayer.Builder(context).setMediaItemConverter(converter).build())
                .setTransferCallback(ClipTransfer)
                .build()
        }.onFailure { Log.w(TAG, "CastPlayer failed", it) }.getOrNull()
    }
}

private val MediaItem.clipStartMs get() = clippingConfiguration.startPositionMs
private val MediaItem.clipEndMs get() = clippingConfiguration.endPositionMs // C.TIME_END_OF_SOURCE = Long.MIN_VALUE
private fun MediaItem.clipDurationMs(fallback: Long) = if (clipEndMs == Long.MIN_VALUE) fallback else clipEndMs - clipStartMs
private val Player.isRemote get() = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

/**
 * Chapter-relative view of a remote player whose items are clipped files (see top). Local playback passes through.
 * ponytail: seekTo(i, p) on another item and setMediaItems are the only writes that need the offset.
 */
class ClipPlayer(p: Player) : ForwardingPlayer(p) {
    private fun start(i: Int = currentMediaItemIndex) =
        if (!isRemote) 0L else (if (i == currentMediaItemIndex) currentMediaItem else getMediaItemAt(i))?.clipStartMs ?: 0L

    override fun getCurrentPosition() = if (!isRemote) super.getCurrentPosition() else (super.getCurrentPosition() - start()).coerceAtLeast(0)
    override fun getContentPosition() = if (!isRemote) super.getContentPosition() else (super.getContentPosition() - start()).coerceAtLeast(0)
    override fun getBufferedPosition() = if (!isRemote) super.getBufferedPosition() else (super.getBufferedPosition() - start()).coerceAtLeast(0)
    override fun getContentBufferedPosition() = if (!isRemote) super.getContentBufferedPosition() else (super.getContentBufferedPosition() - start()).coerceAtLeast(0)
    override fun getDuration() = if (!isRemote) super.getDuration() else currentMediaItem?.clipDurationMs(super.getDuration() - start()) ?: super.getDuration()
    override fun getContentDuration() = getDuration()
    override fun seekTo(positionMs: Long) = super.seekTo(positionMs + start())
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = super.seekTo(mediaItemIndex, positionMs + start(mediaItemIndex))
    // The sleep timer fades and resets player volume; on Cast that would be the TV's volume (reset = 100 %). ponytail: no fade on Cast.
    override fun setVolume(volume: Float) { if (!isRemote) super.setVolume(volume) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
        super.setMediaItems(mediaItems, startIndex, if (!isRemote) startPositionMs else startPositionMs + (mediaItems.getOrNull(startIndex)?.clipStartMs ?: 0))
}

/** Moves the queue between ExoPlayer (chapter-relative positions) and the receiver (absolute file positions). */
private object ClipTransfer : CastPlayer.TransferCallback {
    override fun transferState(from: Player, to: Player) {
        val items = List(from.mediaItemCount) { from.getMediaItemAt(it) }
        if (items.isEmpty()) return
        val i = from.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        val clip = items[i].clipStartMs
        val pos = from.currentPosition.coerceAtLeast(0)
        val target = when {
            to.isRemote && !from.isRemote -> pos + clip
            from.isRemote && !to.isRemote -> (pos - clip).coerceAtLeast(0)
            else -> pos
        }
        to.setMediaItems(items, i, target)
        to.playbackParameters = from.playbackParameters
        to.playWhenReady = from.playWhenReady
        to.prepare()
        from.stop()
    }
}

/** Our MediaItems ↔ Cast queue items: URL from [MediaServer], clipping as startTime/playbackDuration. */
private class CastItemConverter(private val server: MediaServer) : MediaItemConverter {
    private val items = ConcurrentHashMap<String, MediaItem>() // contentUrl → the item it came from

    override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
        val uri = item.localConfiguration?.uri ?: error("no uri")
        // a distinct URL per chapter: Media3 tracks remote items by content id, and every chapter is the same file
        val url = if (Server.isServerUrl(uri.toString())) ServerLinks.get(uri.toString()) ?: error("no signed link yet for $uri")
            else server.url(uri, item.mediaId.substringAfterLast('/')) ?: error("no Wi-Fi address to serve from")
        items[url] = item
        val md = item.mediaMetadata
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_AUDIOBOOK_CHAPTER).apply {
            putString(MediaMetadata.KEY_TITLE, md.title?.toString() ?: "")
            md.albumTitle?.let { putString(MediaMetadata.KEY_BOOK_TITLE, it.toString()) }
            md.title?.let { putString(MediaMetadata.KEY_CHAPTER_TITLE, it.toString()) }
            (md.artist ?: md.albumArtist)?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
            (md.artworkUri ?: md.extras?.getString(ItemExtras.BOOK_ID)?.let(::coverUri))
                ?.let { a -> server.url(a, "cover")?.let { addImage(WebImage(Uri.parse(it))) } }
        }
        val info = MediaInfo.Builder(url).setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MediaServer.mimeOf(uri.toString()))
            .setMetadata(meta)
            .build()
        return MediaQueueItem.Builder(info).apply {
            val start = item.clipStartMs
            if (start > 0) setStartTime(start / 1000.0)
            if (item.clipEndMs != Long.MIN_VALUE) setPlaybackDuration((item.clipEndMs - start) / 1000.0)
        }.build()
    }

    override fun toMediaItem(q: MediaQueueItem): MediaItem {
        val id = q.media?.contentId ?: q.media?.contentUrl
        return items[id] ?: MediaItem.Builder().setUri(id).setMediaId(id ?: "").build()
    }
}

/** O4: the receiver fetches cloud books straight from the server through signed, short-lived links (it can't send our token). */
object ServerLinks {
    private val links = ConcurrentHashMap<String, Pair<String, Long>>() // our track URL → (signed URL, expiry)

    suspend fun prefetch(serverId: String) = runCatching {
        val r = Server.post("/books/$serverId/links").jsonObject
        val exp = r.num("expiresAt")?.toLong() ?: return@runCatching
        r["files"]?.jsonArray?.forEach { u ->
            val signed = u.jsonPrimitive.content
            signed.substringAfter("/files/").substringBefore('?').toIntOrNull()?.let { links[Server.fileUrl(serverId, it)] = signed to exp }
        }
    }

    fun get(uri: String) = links[uri]?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.first
}

/**
 * Tiny HTTP/1.1 file server for the receiver: GET/HEAD with Range, one thread per connection, bound to the Wi-Fi
 * (or Ethernet) address only, every path behind a random token. Serves only URIs the app registered via [url].
 */
class MediaServer(private val context: Context) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val ids = ConcurrentHashMap<String, Uri>()
    private val byUri = ConcurrentHashMap<Uri, String>()
    @Volatile private var socket: ServerSocket? = null

    /** URL the receiver can fetch [uri] from ([tag] only makes the URL unique); starts the server. null = no LAN address. */
    @Synchronized fun url(uri: Uri, tag: String): String? {
        val s = socket ?: start() ?: return null
        val id = byUri.getOrPut(uri) { ids.size.toString().also { ids[it] = uri } }
        val host = s.inetAddress.hostAddress
        val name = Uri.encode(uri.lastPathSegment?.substringAfterLast('/') ?: "file")
        return "http://$host:${s.localPort}/$token/$id/$tag/$name"
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun start(): ServerSocket? {
        val addr = lanAddress() ?: return null
        val s = ServerSocket(0, 16, addr)
        socket = s
        thread(name = "cast-http", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: SocketException) { break }
                thread(name = "cast-http-conn", isDaemon = true) { c.use { runCatching { serve(it) } } }
            }
        }
        Log.i("Cast", "serving on ${addr.hostAddress}:${s.localPort}")
        return s
    }

    private fun lanAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }.firstNotNullOfOrNull { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val input = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val out = c.getOutputStream()
        while (true) { // keep-alive: the receiver issues several range requests on one connection
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val (method, path) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            var range: String? = null
            while (true) {
                val h = input.readLine() ?: return
                if (h.isEmpty()) break
                if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
            }
            val parts = path.trimStart('/').split('/')
            val uri = if (parts.getOrNull(0) == token) ids[parts.getOrNull(1)] else null
            if (uri == null || (method != "GET" && method != "HEAD")) { status(out, "404 Not Found", 0, null); continue }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: run { status(out, "404 Not Found", 0, null); continue }
            pfd.use { fd ->
                val ch = FileInputStream(fd.fileDescriptor).channel
                val size = ch.size()
                val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                var from = 0L; var to = size - 1
                if (m != null) {
                    val (a, b) = m.destructured
                    if (a.isEmpty()) from = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                    else { from = a.toLong(); if (b.isNotEmpty()) to = minOf(b.toLong(), size - 1) }
                }
                if (from >= size) { status(out, "416 Range Not Satisfiable", 0, null, "Content-Range: bytes */$size\r\n"); return@use }
                val len = to - from + 1
                val head = buildString {
                    append(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${mimeOf(uri.toString())}\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
                    if (m != null) append("Content-Range: bytes $from-$to/$size\r\n")
                    append("Access-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n")
                }
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                if (method == "GET") {
                    val buf = ByteBuffer.allocate(64 shl 10)
                    var pos = from
                    while (pos <= to) {
                        buf.clear(); buf.limit(minOf(buf.capacity().toLong(), to - pos + 1).toInt())
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        out.write(buf.array(), 0, n); pos += n
                    }
                }
                out.flush()
            }
        }
    }

    private fun status(out: OutputStream, s: String, len: Int, body: String?, extra: String = "") {
        out.write("HTTP/1.1 $s\r\nContent-Length: $len\r\n$extra\r\n".toByteArray(Charsets.ISO_8859_1))
        body?.let { out.write(it.toByteArray()) }
        out.flush()
    }

    companion object {
        fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase().substringBefore('?')) {
            "mp3", "mpga" -> "audio/mpeg"
            "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "webm", "weba" -> "audio/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> if (name.startsWith("content://app.mfbooks.covers")) "image/jpeg" else "application/octet-stream"
        }
    }
}
