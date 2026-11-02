package io.github.maelmbange.thighhigh

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.scanLibrary
import io.github.maelmbange.thighhigh.playback.PlaybackService
import io.github.maelmbange.thighhigh.ui.CoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { Library, Download }

/** Off → whole list → current track, like the terminal app's loop key. */
enum class Repeat(val mode: Int) {
    Off(Player.REPEAT_MODE_OFF),
    All(Player.REPEAT_MODE_ALL),
    One(Player.REPEAT_MODE_ONE);

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(mode: Int) = entries.firstOrNull { it.mode == mode } ?: Off
    }
}

data class PlaybackState(
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val repeat: Repeat = Repeat.Off,
)

/** The current track's cover and the colors taken from it. */
data class Cover(val bitmap: Bitmap?, val palette: CoverPalette)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** `null` until the first scan finishes. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()

    /** The artist/genre/album filter, or null to show everything. */
    val filter = MutableStateFlow<Filter?>(null)

    /** The tracks shown (and queued when one is played): the library after the filter. */
    val visibleTracks: StateFlow<List<Track>?> = combine(_tracks, filter) { tracks, f ->
        if (f == null) tracks else tracks?.filter(f::matches)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val _cover = MutableStateFlow(Cover(null, CoverPalette.Default))
    val cover: StateFlow<Cover> = _cover.asStateFlow()

    /** The screen picked in the bottom bar; kept here so it survives rotation. */
    val tab = MutableStateFlow(Tab.Library)

    /** Whether the full-screen player is shown over the tabs. */
    val playerOpen = MutableStateFlow(false)

    val downloader = getApplication<ThighHighApp>().downloader

    val genreScanner = getApplication<ThighHighApp>().genreScanner

    private var controller: MediaController? = null
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncState(player)
    }

    init {
        // A finished download shows up in the list right away.
        viewModelScope.launch { downloader.added.collect { refresh() } }
        viewModelScope.launch { genreScanner.changed.collect { refresh() } }
        viewModelScope.launch {
            playback.map { it.currentId }.distinctUntilChanged().collect { loadCover(it) }
        }
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            c.addListener(listener)
            syncState(c)
        }, ContextCompat.getMainExecutor(app))
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _tracks.value = scanLibrary(getApplication())
        }
    }

    private val _refreshing = MutableStateFlow(false)

    /** True while a rescan started by pulling the list down is running. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Rescans the music folder for the pull-to-refresh gesture. */
    fun pullRefresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                _tracks.value = scanLibrary(getApplication())
                // A scan can end within a frame; keep the banner up long enough
                // to be seen and for the gesture to settle back.
                delay((700 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Plays [track], queueing the whole list so next/previous follow it, and opens the player. */
    fun play(track: Track) {
        playerOpen.value = true
        val c = controller ?: return
        val list = visibleTracks.value ?: return
        if (_playback.value.currentId == track.id) {
            if (!c.isPlaying) resume(c)
            return
        }
        c.setMediaItems(list.map { it.toMediaItem() }, list.indexOf(track), 0L)
        c.prepare()
        c.play()
    }

    /** Detects the genre of every track that has none (the terminal app's `g`). */
    fun detectMissingGenres() {
        _tracks.value?.let(genreScanner::scan)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else resume(c)
    }

    /**
     * Stops at once and goes back to the start of the track. A real stop
     * (not pause + seek) drops the audio already buffered, which a seek
     * while paused would otherwise play for a split second.
     */
    fun stop() {
        val c = controller ?: return
        c.stop()
        c.seekTo(0L)
    }

    /** After [stop] the player is idle and must be prepared again. */
    private fun resume(c: Player) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun next() = controller?.seekToNext()

    /** Back to the start of the track, or the previous one in its first seconds. */
    fun previous() = controller?.seekToPrevious()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = Repeat.of(c.repeatMode).next().mode
    }

    /** Polled by the player screen for its progress bar. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    private fun syncState(player: Player) {
        _playback.value = PlaybackState(
            currentId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0L),
            repeat = Repeat.of(player.repeatMode),
        )
    }

    private suspend fun loadCover(id: Long?) {
        val cover = withContext(Dispatchers.IO) {
            val bitmap = id?.let {
                try {
                    getApplication<Application>().contentResolver
                        .loadThumbnail(Track.uriOf(it), Size(768, 768), null)
                } catch (_: Exception) {
                    null // no embedded cover
                }
            }
            Cover(bitmap, bitmap?.let(CoverPalette::from) ?: CoverPalette.Default)
        }
        _cover.value = cover
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        // Releasing the controller does not stop playback: the service keeps playing.
        MediaController.releaseFuture(controllerFuture)
    }
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        )
        .build()
