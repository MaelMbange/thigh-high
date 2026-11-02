package io.github.maelmbange.thighhigh

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.scanLibrary
import io.github.maelmbange.thighhigh.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { Library, Download }

data class PlaybackState(
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** `null` until the first scan finishes. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** The screen picked in the bottom bar; kept here so it survives rotation. */
    val tab = MutableStateFlow(Tab.Library)

    val downloader = getApplication<ThighHighApp>().downloader

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

    /** Plays [track], queueing the whole list so next/previous follow it. */
    fun play(track: Track) {
        val c = controller ?: return
        val list = _tracks.value ?: return
        if (_playback.value.currentId == track.id) {
            if (!c.isPlaying) c.play()
            return
        }
        c.setMediaItems(list.map { it.toMediaItem() }, list.indexOf(track), 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun syncState(player: Player) {
        _playback.update {
            PlaybackState(
                currentId = player.currentMediaItem?.mediaId?.toLongOrNull(),
                isPlaying = player.isPlaying,
            )
        }
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
