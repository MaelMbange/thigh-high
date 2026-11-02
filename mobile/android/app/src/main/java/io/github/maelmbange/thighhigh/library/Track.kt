package io.github.maelmbange.thighhigh.library

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
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
