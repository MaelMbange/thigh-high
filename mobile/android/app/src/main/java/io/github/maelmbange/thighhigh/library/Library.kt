package io.github.maelmbange.thighhigh.library

import android.content.Context
import android.os.Build
import io.github.maelmbange.thighhigh.genre.cleanGenre
import android.provider.MediaStore
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where downloaded music lives on the phone: `Music/thigh-high`, the same
 * place as `<Music folder>/thigh-high` in the terminal app.
 */
const val MUSIC_RELATIVE_PATH = "Music/thigh-high/"

/** What MediaStore writes when a tag is missing. */
private val PLACEHOLDER_ARTISTS = setOf(MediaStore.UNKNOWN_STRING, "")

/** Lists the tracks in [MUSIC_RELATIVE_PATH], sorted by title like the terminal app. */
fun scanLibrary(context: Context): List<Track> {
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.GENRE) else emptyArray()
    // Only the folder itself, not sub-folders: the terminal app doesn't recurse either.
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
    val tracks = mutableListOf<Track>()
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        arrayOf(MUSIC_RELATIVE_PATH),
        null,
    )?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        @Suppress("DEPRECATION")
        val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        // Android 10 has no genre column: those tracks show as unknown genre.
        val genre = c.getColumnIndex("genre")
        while (c.moveToNext()) {
            val fallback = c.getString(name)?.substringBeforeLast('.') ?: "?"
            tracks += Track(
                id = c.getLong(id),
                title = c.getString(title)?.takeIf { it.isNotBlank() } ?: fallback,
                artist = c.getString(artist)?.takeUnless { it in PLACEHOLDER_ARTISTS },
                album = c.getString(album)?.takeUnless { it in PLACEHOLDER_ARTISTS },
                genre = if (genre >= 0) cleanGenre(c.getString(genre)) else null,
                duration = c.getLong(duration).takeIf { it > 0 }?.milliseconds,
                path = c.getString(data),
            )
        }
    }
    return tracks.sortedBy { it.title.lowercase() }
}
