package io.github.maelmbange.thighhigh.playback

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player

/** The queue as it was last left: what to play again after the app was closed. */
data class SavedQueue(
    val ids: List<Long>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
)

/**
 * Keeps the play queue across restarts, so the headset's play button (or the
 * system's "resume" card) starts the music again where it stopped, even after
 * Android ended the app.
 */
class QueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE)

    fun save(player: Player) {
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        prefs.edit {
            putString(IDS, ids.joinToString(","))
            putInt(INDEX, player.currentMediaItemIndex)
            putLong(POSITION, player.currentPosition.coerceAtLeast(0L))
            putInt(REPEAT, player.repeatMode)
            putBoolean(SHUFFLE, player.shuffleModeEnabled)
        }
    }

    fun load(): SavedQueue? {
        val ids = prefs.getString(IDS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }
        if (ids.isNullOrEmpty()) return null
        return SavedQueue(
            ids = ids,
            index = prefs.getInt(INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(POSITION, 0L),
            repeatMode = prefs.getInt(REPEAT, Player.REPEAT_MODE_OFF),
            shuffle = prefs.getBoolean(SHUFFLE, false),
        )
    }

    private companion object {
        const val IDS = "ids"
        const val INDEX = "index"
        const val POSITION = "position"
        const val REPEAT = "repeat"
        const val SHUFFLE = "shuffle"
    }
}
