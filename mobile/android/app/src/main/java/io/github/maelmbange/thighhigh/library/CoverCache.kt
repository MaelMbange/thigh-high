package io.github.maelmbange.thighhigh.library

import android.content.Context
import android.util.LruCache
import android.util.Size
import io.github.maelmbange.thighhigh.ui.CoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colors of each track's cover, for the library list. Covers are read
 * once at thumbnail size and only their palette is kept.
 */
object CoverColors {
    /** A [Result] with a null palette means the track has no cover. */
    private val cache = LruCache<Long, Result>(1000)

    class Result(val palette: CoverPalette?)

    fun cached(id: Long): Result? = cache.get(id)

    suspend fun load(context: Context, id: Long): CoverPalette? {
        cache.get(id)?.let { return it.palette }
        val palette = withContext(Dispatchers.IO) {
            try {
                context.contentResolver
                    .loadThumbnail(Track.uriOf(id), Size(96, 96), null)
                    .let(CoverPalette::from)
            } catch (_: Exception) {
                null
            }
        }
        cache.put(id, Result(palette))
        return palette
    }
}
