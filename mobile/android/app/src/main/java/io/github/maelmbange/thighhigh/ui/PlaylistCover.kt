package io.github.maelmbange.thighhigh.ui

import android.content.Context
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import io.github.maelmbange.thighhigh.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a track's small cover, or null when it has none. Replaced in previews. */
fun interface ThumbnailLoader {
    suspend fun load(context: Context, trackId: Long): ImageBitmap?
}

/** Covers already read, so scrolling the playlists never reads one twice. */
private object Thumbnails : ThumbnailLoader {
    // LruCache takes no nulls: a missing cover is kept as an empty list.
    private val cache = LruCache<Long, List<ImageBitmap>>(64)

    override suspend fun load(context: Context, trackId: Long): ImageBitmap? {
        cache.get(trackId)?.let { return it.firstOrNull() }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(Track.uriOf(trackId), Size(256, 256), null).asImageBitmap()
            } catch (_: Exception) {
                null // no embedded cover, or the file is gone
            }
        }
        cache.put(trackId, listOfNotNull(bitmap))
        return bitmap
    }
}

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader> { Thumbnails }

/**
 * A playlist's picture: the covers of its first four tracks in a 2×2 mosaic,
 * the first one alone when it has fewer, the playlist icon when it is empty.
 */
@Composable
fun PlaylistCover(trackIds: List<Long>, size: Dp, modifier: Modifier = Modifier, corner: Dp = size / 5) {
    val box = modifier.size(size).clip(RoundedCornerShape(corner))
    when {
        trackIds.isEmpty() -> Box(
            box.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.primary)
        }
        trackIds.size < 4 -> CoverTile(trackIds.first(), box)
        else -> Column(box) {
            for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0..1) CoverTile(trackIds[r * 2 + c], Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun CoverTile(trackId: Long, modifier: Modifier) {
    val context = LocalContext.current
    val loader = LocalThumbnailLoader.current
    val cover by produceState<ImageBitmap?>(null, trackId, loader) { value = loader.load(context, trackId) }
    val image = cover
    if (image != null) {
        Image(image, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        }
    }
}
