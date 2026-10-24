@file:OptIn(UnstableApi::class)

package app.mfbooks.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import app.mfbooks.server.LiveDevice
import app.mfbooks.server.Live
import app.mfbooks.server.Server
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/** Cloud books stream from the server (O2): auth header, a disk cache, and 45 minutes read ahead. */
object Streaming {
    private var cache: SimpleCache? = null

    @Synchronized private fun cache(context: Context) = cache ?: SimpleCache(File(context.cacheDir, "stream"),
        LeastRecentlyUsedCacheEvictor(512L shl 20), StandaloneDatabaseProvider(context)).also { cache = it }

    /** content:// and file:// as before; http(s) goes through the cache with the device token (read per request, so re-pairing just works). */
    fun dataSource(context: Context): DataSource.Factory {
        val http = DataSource.Factory {
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(Server.authHeader()?.let { mapOf("Authorization" to it) } ?: emptyMap()).createDataSource()
        }
        return DefaultDataSource.Factory(context, CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR))
    }

    /**
     * 45 minutes ahead (10 MB at the server's 32 kb/s), capped at 16 MB of memory, so a local 128 kb/s file reads ~17 min ahead.
     * ponytail: one load control for both; a per-source one if local playback ever needs a different policy.
     */
    fun loadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(45 * 60_000, 46 * 60_000, 2_500, 5_000)
        .setTargetBufferBytes(16 shl 20).setPrioritizeTimeOverSizeThresholds(false).build()
}

/** What [RemotePlayer] asks the service to do; `null` = handled. */
interface RemoteOps {
    fun command(action: String, args: Map<String, Any?> = emptyMap())
    fun next()
    fun previous()
}

/**
 * Stands in for a book playing on another device (W5), the way Media3's CastPlayer stands in for a Chromecast: while it is
 * the session's player, the mini player, notification, lock screen, Bluetooth buttons and Android Auto show that device
 * and every control becomes a live command. The playlist is the same chapter items the phone would play.
 */
class RemotePlayer(looper: Looper, val bookId: String, private val items: List<MediaItem>, private val spans: List<ItemSpan>,
                   device: LiveDevice, private val ops: RemoteOps, private val skipBackMs: Long, private val skipFwdMs: Long) : SimpleBasePlayer(looper) {
    var device = device
        private set

    fun update(d: LiveDevice) { device = d; invalidateState() }

    /** (chapter index, position in it) now. */
    fun position(): Pair<Int, Long> {
        val s = device.state ?: return 0 to 0L
        return locateInTrack(spans, s.trackIndex, s.positionNow(Live.serverNow()))
    }

    override fun getState(): State {
        val s = device.state
        val (idx, pos) = position()
        val speed = s?.speed ?: 1f
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                COMMAND_PLAY_PAUSE, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SET_SPEED_AND_PITCH, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
            ).build())
            .setPlayWhenReady(s?.playing == true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(items.mapIndexed { i, m -> MediaItemData.Builder(m.mediaId).setMediaItem(m).setDurationUs(spans[i].durationMs * 1000).build() })
            .setCurrentMediaItemIndex(idx)
            .setContentPositionMs(if (s?.playing == true) PositionSupplier.getExtrapolating(pos, speed) else PositionSupplier.getConstant(pos))
            .setPlaybackParameters(PlaybackParameters(speed))
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build())
            .setSeekBackIncrementMs(skipBackMs).setSeekForwardIncrementMs(skipFwdMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        ops.command(if (playWhenReady) "play" else "pause")
        device.state?.let { s -> device = device.copy(state = s.copy(playing = playWhenReady, positionMs = s.positionNow(Live.serverNow()), at = Live.serverNow())) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT -> ops.next()
            COMMAND_SEEK_TO_PREVIOUS -> ops.previous()
            else -> seekChapter(mediaItemIndex, positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    fun seekChapter(index: Int, positionMs: Long) {
        val sp = spans.getOrNull(index) ?: return
        val track = sp.trackIndex; val pos = sp.startInTrackMs + positionMs.coerceAtLeast(0)
        ops.command("seek", mapOf("trackIndex" to track, "positionMs" to pos))
        device.state?.let { s -> device = device.copy(state = s.copy(trackIndex = track, positionMs = pos, at = Live.serverNow())) }
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        ops.command("speed", mapOf("speed" to playbackParameters.speed))
        return Futures.immediateVoidFuture()
    }
}
