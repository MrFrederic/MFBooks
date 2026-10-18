package app.mfbooks.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import app.mfbooks.R
import app.mfbooks.data.Settings
import kotlin.math.abs

/** A mapped action (§5.6): a command, plus milliseconds for [Command.SEEK_BY]. */
data class MappedAction(val command: Command, val ms: Long = 0)

/** "FWD:30" / "REWIND:30" / "NEXT_CHAPTER" … → [MappedAction]; null if unknown. */
fun parseAction(s: String): MappedAction? {
    val (name, arg) = s.split(':', limit = 2).let { it[0] to it.getOrNull(1)?.toLongOrNull() }
    return when (name) {
        "FWD" -> MappedAction(Command.SEEK_BY, (arg ?: 30) * 1000)
        "REWIND" -> MappedAction(Command.SEEK_BY, -(arg ?: 30) * 1000)
        else -> Command.entries.firstOrNull { it.name == name }?.let { MappedAction(it, arg?.times(1000) ?: 0) }
    }
}

/** Resolve the small/big skip commands to a signed delta, so every seconds skip is one code path. */
fun MappedAction.skipMs(s: Settings): Long? = when (command) {
    Command.SEEK_BY -> ms
    Command.REWIND_SMALL -> -s.rewindSmallSec * 1000L
    Command.REWIND_BIG -> -s.rewindBigSec * 1000L
    Command.FWD_SMALL -> s.fwdSmallSec * 1000L
    Command.FWD_BIG -> s.fwdBigSec * 1000L
    else -> null
}

fun skipIcon(deltaMs: Long): Int = when (abs(deltaMs) / 1000) {
    5L -> if (deltaMs < 0) CommandButton.ICON_SKIP_BACK_5 else CommandButton.ICON_SKIP_FORWARD_5
    10L -> if (deltaMs < 0) CommandButton.ICON_SKIP_BACK_10 else CommandButton.ICON_SKIP_FORWARD_10
    15L -> if (deltaMs < 0) CommandButton.ICON_SKIP_BACK_15 else CommandButton.ICON_SKIP_FORWARD_15
    30L -> if (deltaMs < 0) CommandButton.ICON_SKIP_BACK_30 else CommandButton.ICON_SKIP_FORWARD_30
    else -> if (deltaMs < 0) CommandButton.ICON_SKIP_BACK else CommandButton.ICON_SKIP_FORWARD
}

val SPEED_CYCLE = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

fun nextCycleSpeed(current: Float): Float {
    val i = SPEED_CYCLE.indices.minBy { abs(SPEED_CYCLE[it] - current) }
    return SPEED_CYCLE[(i + 1) % SPEED_CYCLE.size]
}

fun nextSleepCycle(m: SleepMode): SleepMode = when (m) {
    SleepMode.Off -> SleepMode.Duration(15)
    is SleepMode.Duration -> when {
        m.minutes < 30 -> SleepMode.Duration(30)
        m.minutes < 60 -> SleepMode.Duration(60)
        else -> SleepMode.EndOfChapter
    }
    SleepMode.EndOfChapter -> SleepMode.Off
}

private fun speedIcon(speed: Float) = when (speed) {
    0.5f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
    1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
    1.5f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
    2f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
    else -> CommandButton.ICON_PLAYBACK_SPEED
}

fun formatSpeed(speed: Float) = "%.2f".format(speed).trimEnd('0').trimEnd('.', ',') + "×"

/** Media button preferences for AA, notification, lock screen, Wear (§6.5, §7.4). */
fun mediaButtons(ctx: Context, s: Settings, speed: Float, sleep: SleepMode): List<CommandButton> {
    fun slotButton(action: String, back: Boolean): CommandButton {
        val a = parseAction(action)
        val skip = a?.skipMs(s)
        val icon = when {
            skip != null -> skipIcon(skip)
            back -> CommandButton.ICON_PREVIOUS
            else -> CommandButton.ICON_NEXT
        }
        val label = when {
            skip != null -> ctx.getString(if (skip < 0) R.string.pb_rewind_sec else R.string.pb_forward_sec, abs(skip) / 1000)
            else -> ctx.getString(if (back) R.string.pb_previous else R.string.pb_next)
        }
        return CommandButton.Builder(icon)
            .setPlayerCommand(if (back) Player.COMMAND_SEEK_TO_PREVIOUS else Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName(label)
            .setSlots(if (back) CommandButton.SLOT_BACK else CommandButton.SLOT_FORWARD)
            .build()
    }
    val sleepOn = sleep != SleepMode.Off
    val buttons = mutableListOf(
        slotButton(s.prevAction, back = true),
        slotButton(s.nextAction, back = false),
        CommandButton.Builder(speedIcon(speed)).setSessionCommand(Command.CYCLE_SPEED.session())
            .setDisplayName(ctx.getString(R.string.pb_speed, formatSpeed(speed))).setSlots(CommandButton.SLOT_OVERFLOW).build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (sleepOn) R.drawable.pb_ic_sleep_on else R.drawable.pb_ic_sleep)
            .setSessionCommand(Command.SLEEP_CYCLE.session())
            .setDisplayName(ctx.getString(sleepLabel(sleep)))
            .setSlots(CommandButton.SLOT_OVERFLOW).build(),
        CommandButton.Builder(CommandButton.ICON_BOOKMARK_UNFILLED).setSessionCommand(Command.ADD_BOOKMARK.session())
            .setDisplayName(ctx.getString(R.string.pb_add_bookmark)).setSlots(CommandButton.SLOT_OVERFLOW).build(),
    )
    if (parseAction(s.nextAction)?.skipMs(s) != null)
        buttons += CommandButton.Builder(CommandButton.ICON_NEXT).setSessionCommand(Command.NEXT_CHAPTER.session())
            .setDisplayName(ctx.getString(R.string.pb_next_chapter)).setSlots(CommandButton.SLOT_OVERFLOW).build()
    return buttons
}

private fun sleepLabel(m: SleepMode) = when (m) {
    SleepMode.Off -> R.string.pb_sleep_off
    SleepMode.EndOfChapter -> R.string.pb_sleep_eoc
    is SleepMode.Duration -> when (m.minutes) { 15 -> R.string.pb_sleep_15; 30 -> R.string.pb_sleep_30; 60 -> R.string.pb_sleep_60; else -> R.string.pb_sleep_on }
}
