package io.github.maelmbange.thighhigh.lyrics

import android.content.Context
import android.util.Log
import io.github.maelmbange.thighhigh.library.Track
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Lyrics"

/** LRCLIB: a free, open-source lyrics database, no account or key. */
private const val API = "https://lrclib.net/api"

/** A track with no lyrics is looked up again after this long (someone may have added them). */
private const val RETRY_NOT_FOUND_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Finds lyrics on LRCLIB and keeps them in the app's files, one per track,
 * so each track is looked up once and its lyrics then show offline.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }

    private val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** The lyrics of [track]; throws [IOException] when offline and none were kept. */
    suspend fun load(track: Track): Lyrics = withContext(Dispatchers.IO) {
        val file = File(dir, "${track.id}.json")
        cached(file)?.let { return@withContext it }
        val found = lookUp(track)
        runCatching { file.writeText(found.toJson().toString()) }
        found
    }

    private fun cached(file: File): Lyrics? {
        if (!file.exists()) return null
        val lyrics = runCatching { lyricsFromJson(JSONObject(file.readText())) }.getOrNull() ?: return null
        val stale = lyrics == Lyrics.NotFound && System.currentTimeMillis() - file.lastModified() > RETRY_NOT_FOUND_MS
        return lyrics.takeUnless { stale }
    }

    private fun lookUp(track: Track): Lyrics {
        val seconds = track.duration?.inWholeSeconds
        val (title, artist) = searchTerms(track.title, track.artist)
        // The exact record first: LRCLIB matches the length within a couple of seconds.
        if (artist != null && seconds != null) {
            val params = mutableListOf("track_name" to title, "artist_name" to artist, "duration" to seconds.toString())
            track.album?.let { params += "album_name" to it }
            get("/get", params)?.let { lyricsOf(JSONObject(it)) }?.takeIf { it != Lyrics.NotFound }?.let { return it }
        }
        // Then a search, the title with the artist, then everything as free text.
        val searches = listOfNotNull(
            artist?.let { listOf("track_name" to title, "artist_name" to it) },
            listOf("q" to listOfNotNull(artist, title).joinToString(" ")),
            listOf("q" to track.title),
        ).distinct()
        for (params in searches) {
            val body = get("/search", params) ?: continue
            bestMatch(JSONArray(body), seconds)?.let { return lyricsOf(it) }
        }
        return Lyrics.NotFound
    }

    /** The response body, or null for "not found". */
    private fun get(path: String, params: List<Pair<String, String>>): String? {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URL("$API$path?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            // LRCLIB asks apps to say who they are.
            connection.setRequestProperty("User-Agent", "thigh-high/$version (https://github.com/MaelMbange/thigh-high)")
            return when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("LRCLIB answered $code").also { Log.w(TAG, it.message!!) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
