package io.github.maelmbange.thighhigh.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** How many recently played tracks are remembered (the home screen shows fewer). */
private const val MAX_RECENT = 50

/**
 * What was listened to lately, for the home screen: the last tracks played,
 * most recent first, and when each playlist was last played.
 */
class ListeningStore(context: Context) {
    private val prefs = context.getSharedPreferences("listening", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(
        prefs.getString(RECENT, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    )
    /** Track ids, the last one played first, each once. */
    val recent: StateFlow<List<Long>> = _recent.asStateFlow()

    private val _playlistPlayed = MutableStateFlow(loadPlayed())
    /** Playlist id to the time (ms) it was last played, or created when never played. */
    val playlistPlayed: StateFlow<Map<String, Long>> = _playlistPlayed.asStateFlow()

    fun played(trackId: Long) {
        _recent.update { ids -> (listOf(trackId) + ids.filterNot { it == trackId }).take(MAX_RECENT) }
        prefs.edit().putString(RECENT, _recent.value.joinToString(",")).apply()
    }

    fun playlistPlayed(id: String, at: Long = System.currentTimeMillis()) =
        changePlayed { it + (id to at) }

    fun forgetPlaylist(id: String) = changePlayed { it - id }

    private fun changePlayed(transform: (Map<String, Long>) -> Map<String, Long>) {
        _playlistPlayed.update(transform)
        prefs.edit().putString(PLAYLIST_PLAYED, JSONObject(_playlistPlayed.value).toString()).apply()
    }

    private fun loadPlayed(): Map<String, Long> = try {
        val o = JSONObject(prefs.getString(PLAYLIST_PLAYED, null) ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private companion object {
        const val RECENT = "recent"
        const val PLAYLIST_PLAYED = "playlist_played"
    }
}

/** The playlists with the last played first; never played ones keep their creation order after. */
fun List<Playlist>.byLastPlayed(played: Map<String, Long>): List<Playlist> =
    sortedByDescending { played[it.id] ?: 0L }
