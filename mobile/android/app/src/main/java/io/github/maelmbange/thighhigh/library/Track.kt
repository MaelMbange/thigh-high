package io.github.maelmbange.thighhigh.library

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlin.time.Duration

/** A downloaded track, as indexed by Android's MediaStore. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val duration: Duration?,
    /** The file on disk, for reading and writing tags. */
    val path: String?,
) {
    val uri: Uri get() = uriOf(id)

    companion object {
        fun uriOf(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
}

/**
 * The track as the player queues it. The artwork is the audio file itself:
 * the session's bitmap loader asks MediaStore for its embedded cover, which
 * the notification and the lock screen then show.
 */
fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setGenre(genre)
                .setArtworkUri(uri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
