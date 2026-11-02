package io.github.maelmbange.thighhigh.playback

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Loads the artwork of the notification and lock screen. A track's artwork URI
 * is the audio file itself: its embedded cover is read through MediaStore,
 * like the app's own player does. Any other URI loads as an image.
 */
@OptIn(UnstableApi::class)
class CoverBitmapLoader(context: Context) : BitmapLoader {
    private val resolver = context.contentResolver
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val images = DataSourceBitmapLoader(context)

    override fun supportsMimeType(mimeType: String) = images.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = images.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        if (isAudio(uri)) executor.submit<Bitmap> { resolver.loadThumbnail(uri, Size(512, 512), null) }
        else images.loadBitmap(uri)

    private fun isAudio(uri: Uri) =
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == MediaStore.AUTHORITY &&
            uri.pathSegments.contains("audio")
}
