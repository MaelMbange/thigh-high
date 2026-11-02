package io.github.maelmbange.thighhigh.genre

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import io.github.maelmbange.thighhigh.library.Track
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "GenreTagger"

/** The genre already in a file's tags, ignoring placeholders. */
fun existingGenre(path: String): String? = try {
    MediaMetadataRetriever().run {
        try {
            setDataSource(path)
            cleanGenre(extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
        } finally {
            release()
        }
    }
} catch (_: Exception) {
    null
}

/**
 * Listens to 30 s from the middle of the file and writes the genre into its
 * tags, like the terminal app. Returns the genre, or null when nothing stands
 * out (the file is then left alone).
 */
fun tagGenre(path: String, durationMs: Long = 0): String? {
    val genre = Genre.detect(decodeExcerpt(path, durationMs)) ?: return null
    Genre.saveGenre(path, genre)
    return genre
}

data class GenreScan(val done: Int, val total: Int, val found: Int, val failed: Int) {
    val finished get() = done == total
}

/** Analyses every track of the library that has no genre (the terminal app's `g`). */
/** How long the result of a scan stays on screen. */
private const val RESULT_SHOWN_MS = 12_000L

class GenreScanner(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "genre scan failed", e)
        }
    )

    private val _progress = MutableStateFlow<GenreScan?>(null)
    /** null when no scan has run yet. */
    val progress: StateFlow<GenreScan?> = _progress.asStateFlow()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits when tags changed and the library should be read again. */
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    fun scan(tracks: List<Track>) {
        if (_progress.value?.finished == false) return
        val todo = tracks.filter { it.genre == null && it.path != null }
        var state = GenreScan(0, todo.size, 0, 0)
        _progress.value = state
        scope.launch {
            for (track in todo) {
                val path = track.path!!
                val genre = try {
                    tagGenre(path, track.duration?.inWholeMilliseconds ?: 0)
                } catch (e: Throwable) {
                    // Typically a file another app put there, which Android won't let us edit.
                    Log.w(TAG, "could not tag $path", e)
                    state = state.copy(failed = state.failed + 1)
                    null
                }
                if (genre != null) {
                    rescan(path)
                    state = state.copy(found = state.found + 1)
                    _changed.tryEmit(Unit)
                }
                state = state.copy(done = state.done + 1)
                _progress.value = state
            }
            // The result stays up a moment, then the banner goes away by itself.
            delay(RESULT_SHOWN_MS)
            _progress.compareAndSet(state, null)
        }
    }

    /** Has Android read the new tags. */
    private suspend fun rescan(path: String) = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(app, arrayOf(path), null) { _, _ -> cont.resume(Unit) }
    }
}
