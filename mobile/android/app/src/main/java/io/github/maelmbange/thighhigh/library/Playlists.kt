package io.github.maelmbange.thighhigh.library

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** The id of the favorites, kept as a playlist that is never listed with the others. */
const val FAVORITES_ID = "favorites"

/** A named list of tracks, by MediaStore id, in the order they were added. */
data class Playlist(val id: String, val name: String, val trackIds: List<Long>) {
    /** The playlist's tracks still in the library (deleted ones are skipped). */
    fun tracks(library: List<Track>): List<Track> {
        val byId = library.associateBy { it.id }
        return trackIds.mapNotNull(byId::get)
    }
}

/**
 * The playlists, kept in a small JSON file of the app's private storage. Every
 * change is written at once; the file is tiny, so this stays on the caller's thread.
 */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")

    private val _playlists = MutableStateFlow(load())
    /** In creation order. */
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun create(name: String, trackIds: List<Long> = emptyList()): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim(), trackIds.distinct())
        change { it + playlist }
        return playlist
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim()) }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Adds tracks at the end, skipping those already in the playlist. */
    fun add(id: String, trackIds: List<Long>) = edit(id) { p ->
        p.copy(trackIds = p.trackIds + trackIds.filterNot { it in p.trackIds })
    }

    fun remove(id: String, trackId: Long) = edit(id) { p -> p.copy(trackIds = p.trackIds - trackId) }

    /** [old] was replaced by a new version of the same song: playlists keep their place for it. */
    fun replaceTrack(old: Long, new: Long) = change { list ->
        list.map { p ->
            if (old !in p.trackIds) p
            else p.copy(trackIds = p.trackIds.map { if (it == old) new else it }.distinct())
        }
    }

    private fun edit(id: String, change: (Playlist) -> Playlist) = change { list ->
        // The favorites come into being with their first track.
        val all = if (id == FAVORITES_ID && list.none { it.id == id }) list + Playlist(id, "", emptyList()) else list
        all.map { if (it.id == id) change(it) else it }
    }

    private fun change(transform: (List<Playlist>) -> List<Playlist>) {
        _playlists.update(transform)
        save(_playlists.value)
    }

    private fun load(): List<Playlist> = try {
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val ids = o.getJSONArray("tracks")
            Playlist(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) })
        }
    } catch (_: Exception) {
        emptyList() // no file yet
    }

    private fun save(list: List<Playlist>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", JSONArray(p.trackIds))
            )
        }
        // Written beside, then moved over: a crash mid-write never loses the playlists.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }
}
