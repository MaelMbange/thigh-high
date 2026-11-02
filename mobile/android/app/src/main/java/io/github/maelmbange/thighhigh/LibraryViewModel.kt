package io.github.maelmbange.thighhigh

import io.github.maelmbange.thighhigh.download.DownloadJob
import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.matchesSearch
import io.github.maelmbange.thighhigh.library.scanLibrary
import io.github.maelmbange.thighhigh.library.toMediaItem
import io.github.maelmbange.thighhigh.playback.PlaybackService
import io.github.maelmbange.thighhigh.playback.QueueStore
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

enum class Tab { Library, Playlists, Download }

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
    /** Next/previous follow a random order of the queue. */
    val shuffle: Boolean = false,
    /** Whether there is a track before / after this one (loop included). */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** A track waiting in the queue, at [index] in the player's list. */
data class QueueEntry(val index: Int, val id: Long?, val title: String, val artist: String?)

/** The covers of the tracks before and after the current one, when known. */
data class Neighbors(val previous: Cover? = null, val next: Cover? = null)

/** The current track's cover and the colors taken from it. */
data class Cover(val bitmap: Bitmap?, val palette: CoverPalette, val trackId: Long? = null)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** `null` until the first scan finishes. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()

    /** The artist/genre/album filter, or null to show everything. */
    val filter = MutableStateFlow<Filter?>(null)

    /** What is typed in the search bar above the list. */
    val query = MutableStateFlow("")

    /** The tracks shown (and queued when one is played): the library after the filter and the search. */
    val visibleTracks: StateFlow<List<Track>?> = combine(_tracks, filter, query) { tracks, f, q ->
        tracks?.filter { (f == null || f.matches(it)) && it.matchesSearch(q) }
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

    private val playlistStore = getApplication<ThighHighApp>().playlists
    val playlists: StateFlow<List<Playlist>> = playlistStore.playlists

    /** The playlist opened in the Playlists tab, or null for the list of playlists. */
    val openPlaylistId = MutableStateFlow<String?>(null)

    private val _history = MutableStateFlow<List<QueueEntry>>(emptyList())
    /** What already played from the queue, oldest first, in the order it played. */
    val history: StateFlow<List<QueueEntry>> = _history.asStateFlow()

    private val _neighbors = MutableStateFlow(Neighbors())
    /** The covers on either side of the current one, loaded ahead for the swipe. */
    val neighbors: StateFlow<Neighbors> = _neighbors.asStateFlow()

    /** The ids of the tracks before and after the current one. */
    private val neighborIds = MutableStateFlow<Pair<Long?, Long?>>(null to null)

    /** Recently loaded covers, so a swipe back and forth never waits for one. */
    private val coverCache = android.util.LruCache<Long, Cover>(12)

    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    /** What plays after the current track, in the order it will play (shuffle included). */
    val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()

    /** Which list the queue was built from, so a track tapped in another list starts that list. */
    private var queueSource: String? = null

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
        // yt-dlp is kept up to date in the background (at most daily).
        downloader.autoUpdate()
        // A finished download shows up in the list right away.
        viewModelScope.launch { downloader.added.collect { refresh() } }
        viewModelScope.launch { genreScanner.changed.collect { refresh() } }
        viewModelScope.launch {
            playback.map { it.currentId }.distinctUntilChanged().collect { loadCover(it) }
        }
        viewModelScope.launch {
            neighborIds.collect { (prev, next) ->
                _neighbors.value = Neighbors(coverOf(prev), coverOf(next))
            }
        }
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            c.addListener(listener)
            syncState(c)
            if (c.mediaItemCount == 0) restoreQueue(c)
        }, ContextCompat.getMainExecutor(app))
    }

    /**
     * Puts back the queue left when the app was last closed, paused where it
     * stopped, so the mini player shows the last track and play resumes it.
     */
    private fun restoreQueue(c: MediaController) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val saved = QueueStore(getApplication()).load() ?: return@withContext null
                val byId = scanLibrary(getApplication()).associateBy { it.id }
                val kept = saved.ids.mapNotNull(byId::get)
                val index = kept.indexOfFirst { it.id == saved.ids[saved.index] }
                if (kept.isEmpty()) null else Triple(saved, kept, index)
            } ?: return@launch
            val (saved, kept, index) = restored
            if (c.mediaItemCount != 0) return@launch // something started meanwhile
            c.repeatMode = saved.repeatMode
            c.shuffleModeEnabled = saved.shuffle
            c.setMediaItems(kept.map { it.toMediaItem() }, index.coerceAtLeast(0), if (index >= 0) saved.positionMs else 0L)
        }
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

    /** Plays [track] from the library list as shown, which becomes the queue. */
    fun play(track: Track) {
        playList(visibleTracks.value ?: return, track, LIBRARY)
    }

    /**
     * Plays [track], queueing [list] so next/previous follow it, and opens the
     * player. Tapping the track already playing from the same list only resumes it.
     */
    fun playList(list: List<Track>, track: Track, source: String) {
        playerOpen.value = true
        val c = controller ?: return
        if (_playback.value.currentId == track.id && queueSource == source) {
            if (!c.isPlaying) resume(c)
            return
        }
        queueSource = source
        c.setMediaItems(list.map { it.toMediaItem() }, list.indexOf(track).coerceAtLeast(0), 0L)
        c.prepare()
        c.play()
    }

    /** Plays the list shown in a random order, from a random track. */
    fun shufflePlay() = shufflePlay(visibleTracks.value.orEmpty(), LIBRARY)

    /** Plays [list] in a random order, from a random track. */
    fun shufflePlay(list: List<Track>, source: String) {
        val c = controller ?: return
        if (list.isEmpty()) return
        playerOpen.value = true
        queueSource = source
        c.shuffleModeEnabled = true
        c.setMediaItems(list.map { it.toMediaItem() }, list.indices.random(), 0L)
        c.prepare()
        c.play()
    }

    /** Puts [track] right after the one playing. */
    fun playNext(track: Track) = enqueue(track, next = true)

    /** Puts [track] at the end of the queue. */
    fun addToQueue(track: Track) = enqueue(track, next = false)

    private fun enqueue(track: Track, next: Boolean) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            // Nothing queued yet: it simply plays.
            queueSource = null
            c.setMediaItem(track.toMediaItem())
            c.prepare()
            c.play()
            return
        }
        val index = if (next) c.currentMediaItemIndex + 1 else c.mediaItemCount
        c.addMediaItem(index, track.toMediaItem())
        c.sendCustomCommand(PlaybackService.Queued.COMMAND, PlaybackService.Queued.args(index, next))
        // The queue no longer matches any list.
        queueSource = null
    }

    /** Jumps to a track of the queue. */
    fun playQueued(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0L)
        resume(c)
    }

    fun removeQueued(index: Int) {
        controller?.removeMediaItem(index)
        queueSource = null
    }

    fun createPlaylist(name: String, with: Track? = null): Playlist =
        playlistStore.create(name, listOfNotNull(with?.id))

    fun addToPlaylist(playlist: Playlist, track: Track) = playlistStore.add(playlist.id, listOf(track.id))

    fun removeFromPlaylist(playlist: Playlist, track: Track) = playlistStore.remove(playlist.id, track.id)

    fun renamePlaylist(playlist: Playlist, name: String) = playlistStore.rename(playlist.id, name)

    fun deletePlaylist(playlist: Playlist) {
        if (openPlaylistId.value == playlist.id) openPlaylistId.value = null
        playlistStore.delete(playlist.id)
    }

    /** Turns the random order on or off; the track playing goes on. */
    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** The track waiting for the user to confirm its deletion in a system dialog. */
    private var pendingDelete: Track? = null

    /**
     * Deletes [track] from the phone. Returns the system confirmation dialog to
     * show when Android asks the user first (always from Android 11), or null
     * when the file is already gone.
     */
    fun delete(track: Track): IntentSender? {
        val resolver = getApplication<Application>().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = track
            return MediaStore.createDeleteRequest(resolver, listOf(track.uri)).intentSender
        }
        return try {
            resolver.delete(track.uri, null, null)
            onDeleted(track)
            null
        } catch (e: RecoverableSecurityException) {
            pendingDelete = track
            e.userAction.actionIntent.intentSender
        }
    }

    /** A download whose older version is being deleted: (download, old track, new track). */
    private var pendingReplace: Triple<String, Long, Long>? = null

    /**
     * Deletes the older version of the song [job] downloaded again; playlists
     * and the queue move to the new one. Same dialog as [delete].
     */
    fun replaceOld(job: DownloadJob): IntentSender? {
        val old = job.similar ?: return null
        val new = job.trackId ?: return null
        val track = _tracks.value?.firstOrNull { it.id == old.trackId }
        if (track == null) {
            // Already gone from the phone.
            playlistStore.replaceTrack(old.trackId, new)
            downloader.replaced(job.id)
            return null
        }
        pendingReplace = Triple(job.id, old.trackId, new)
        return delete(track)
    }

    /** The user answered the system dialog opened by [delete]. */
    fun onDeleteConfirmed(confirmed: Boolean) {
        val track = pendingDelete ?: return
        pendingDelete = null
        if (!confirmed) {
            pendingReplace = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 10 only granted access: the file still has to be deleted.
            runCatching { getApplication<Application>().contentResolver.delete(track.uri, null, null) }
        }
        onDeleted(track)
    }

    /** Drops a deleted track from the play queue and the list. */
    private fun onDeleted(track: Track) {
        val replace = pendingReplace?.takeIf { it.second == track.id }
        pendingReplace = null
        val newer = replace?.let { r -> _tracks.value?.firstOrNull { it.id == r.third } }
        controller?.let { c ->
            (0 until c.mediaItemCount)
                .firstOrNull { c.getMediaItemAt(it).mediaId == track.id.toString() }
                ?.let { index ->
                    // A queued old version becomes the new one (the playing one has to stop).
                    if (newer != null && index != c.currentMediaItemIndex) c.replaceMediaItem(index, newer.toMediaItem())
                    else c.removeMediaItem(index)
                }
        }
        if (replace != null) {
            playlistStore.replaceTrack(track.id, replace.third)
            downloader.replaced(replace.first)
        }
        refresh()
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

    /** Always the previous track (a swipe), never the start of this one. */
    fun skipPrevious() = controller?.seekToPreviousMediaItem()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = Repeat.of(c.repeatMode).next().mode
    }

    /** Polled by the player screen for its progress bar. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    private fun syncState(player: Player) {
        _upNext.value = upNextOf(player)
        _history.value = historyOf(player)
        val prevId = player.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            ?.let { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        val nextId = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            ?.let { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        neighborIds.value = prevId to nextId
        _playback.value = PlaybackState(
            currentId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0L),
            repeat = Repeat.of(player.repeatMode),
            shuffle = player.shuffleModeEnabled,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
        )
    }

    /** The tracks before the current one, oldest first, following the shuffle order when it is on. */
    private fun historyOf(player: Player): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val entries = mutableListOf<QueueEntry>()
        var i = player.currentMediaItemIndex
        while (true) {
            i = timeline.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i >= player.mediaItemCount) break
            entries += player.getMediaItemAt(i).toEntry(i)
        }
        return entries.reversed()
    }

    private fun MediaItem.toEntry(index: Int) = QueueEntry(
        index = index,
        id = mediaId.toLongOrNull(),
        title = mediaMetadata.title?.toString() ?: "?",
        artist = mediaMetadata.artist?.toString(),
    )

    /** The tracks after the current one, following the shuffle order when it is on. */
    private fun upNextOf(player: Player): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val entries = mutableListOf<QueueEntry>()
        var i = player.currentMediaItemIndex
        while (true) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i >= player.mediaItemCount) break
            entries += player.getMediaItemAt(i).toEntry(i)
        }
        return entries
    }

    private suspend fun loadCover(id: Long?) {
        _cover.value = coverOf(id) ?: Cover(null, CoverPalette.Default, null)
    }

    /** The cover of track [id] with its colors, from the cache or read once. */
    private suspend fun coverOf(id: Long?): Cover? {
        id ?: return null
        coverCache.get(id)?.let { return it }
        val cover = withContext(Dispatchers.IO) {
            val bitmap = try {
                getApplication<Application>().contentResolver
                    .loadThumbnail(Track.uriOf(id), Size(768, 768), null)
            } catch (_: Exception) {
                null // no embedded cover
            }
            Cover(bitmap, bitmap?.let(CoverPalette::from) ?: CoverPalette.Default, id)
        }
        coverCache.put(id, cover)
        return cover
    }

    private companion object {
        const val LIBRARY = "library"
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        // Releasing the controller does not stop playback: the service keeps playing.
        MediaController.releaseFuture(controllerFuture)
    }
}
