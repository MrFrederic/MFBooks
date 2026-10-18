package app.mfbooks.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Tasker / automation: `app.mfbooks.action.<COMMAND>` with the extras from [Command]. Forwarded to [PlaybackService]
 * as the same custom session command the UI and Android Auto use (one command path).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = Command.fromAction(intent.action) ?: return
        val args = intent.extras?.let(::Bundle) ?: Bundle()
        // Tasker sends everything as strings: coerce the numeric extras we know.
        args.getString(Command.EXTRA_SPEED)?.toFloatOrNull()?.let { args.putFloat(Command.EXTRA_SPEED, it) }
        args.getString(Command.EXTRA_MS)?.toLongOrNull()?.let { args.putLong(Command.EXTRA_MS, it) }
        args.getString(Command.EXTRA_MINUTES)?.toIntOrNull()?.let { args.putInt(Command.EXTRA_MINUTES, it) }
        val pending = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                c.sendCustomCommand(cmd.session(), args).addListener({ c.release(); pending.finish() }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(app))
    }
}
