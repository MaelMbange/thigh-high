package io.github.maelmbange.thighhigh

import io.github.maelmbange.thighhigh.playback.PulseSource
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
import io.github.maelmbange.thighhigh.library.FAVORITES_ID
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.ListeningStore
import io.github.maelmbange.thighhigh.library.byLastPlayed
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import io.github.maelmbange.thighhigh.lyrics.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { Library, Playlists, Download }

/** What the first tab shows: the home panel, every track, or the favorites. */
enum class HomeView { Home, All, Favorites }

/** How many recently played tracks the home screen lists. */
const val RECENT_SHOWN = 5

/** The source of a track played from the recently played on the home screen. */
const val RECENT = "recent"

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
data class QueueEntry(
    val index: Int,
    val id: Long?,
    val title: String,
    val artist: String?,
    val genre: String? = null,
    val duration: kotlin.time.Duration? = null,
)

/** The covers of the tracks before and after the current one, when known. */
data class Neighbors(val previous: Cover? = null, val next: Cover? = null)

/** The current track's cover and the colors taken from it. */
data class Cover(val bitmap: Bitmap?, val palette: CoverPalette, val trackId: Long? = null)

/** Where the playing track's lyrics stand. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
    /** No connection, and the lyrics were never kept: try again later. */
    data object Offline : LyricsState
}

/** How long the queue shown stays as expected after a removal, before the player's state is read again. */
private const val QUEUE_SETTLE_MS = 700L

/** Settings key: the instrument the player's pulse follows. */
private const val PULSE_SOURCE = "pulse_source"

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

    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _pulseSource = MutableStateFlow(
        PulseSource.entries.firstOrNull { it.name == settings.getString(PULSE_SOURCE, null) } ?: PulseSource.Bass
    )
    /** What the player's buttons and cover swell with (Settings). */
    val pulseSource: StateFlow<PulseSource> = _pulseSource.asStateFlow()

    fun setPulseSource(source: PulseSource) {
        _pulseSource.value = source
        settings.edit().putString(PULSE_SOURCE, source.name).apply()
    }

    val downloader = getApplication<ThighHighApp>().downloader

    val genreScanner = getApplication<ThighHighApp>().genreScanner

    private val playlistStore = getApplication<ThighHighApp>().playlists

    private val listening = ListeningStore(app)

    /** The playlists (favorites aside), the last played first. */
    val playlists: StateFlow<List<Playlist>> = combine(playlistStore.playlists, listening.playlistPlayed) { list, played ->
        list.filterNot { it.id == FAVORITES_ID }.byLastPlayed(played)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        playlistStore.playlists.value.filterNot { it.id == FAVORITES_ID }.byLastPlayed(listening.playlistPlayed.value),
    )

    private fun favoritesOf(list: List<Playlist>) =
        list.firstOrNull { it.id == FAVORITES_ID } ?: Playlist(FAVORITES_ID, "", emptyList())

    /** The favorite tracks, kept as a playlist of their own. */
    val favorites: StateFlow<Playlist> = playlistStore.playlists.map(::favoritesOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, favoritesOf(playlistStore.playlists.value))

    /** The last tracks played, most recent first, as many as the home screen shows. */
    val recent: StateFlow<List<Track>> = combine(listening.recent, _tracks) { ids, tracks ->
        val byId = tracks.orEmpty().associateBy { it.id }
        ids.mapNotNull(byId::get).take(RECENT_SHOWN)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the first tab shows. */
    val homeView = MutableStateFlow(HomeView.Home)

    /** The playlist opened in the Playlists tab, or null for the list of playlists. */
    val openPlaylistId = MutableStateFlow<String?>(null)

    /** Whether the open playlist was opened from the home screen: back then returns there. */
    val playlistFromHome = MutableStateFlow(false)

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
        set(value) {
            if (field == value) return
            field = value
            // Kept, so after a restart the same list carries on (and tapping
            // its track again only resumes it).
            queueStore.saveSource(value)
        }

    private val queueStore = QueueStore(app)

    private var controller: MediaController? = null
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync()

    private val lyricsRepository = LyricsRepository(app)

    /** Broadcasting on the local network ("Diffuser" / "S'abonner"). */
    val cast = io.github.maelmbange.thighhigh.cast.CastController(
        app,
        viewModelScope,
        position = { controller?.currentPosition ?: 0L },
        isPlaying = { controller?.isPlaying == true },
        onListenStart = { controller?.pause() },
    )

    /** Whether the broadcast screen is shown. */
    val castOpen = MutableStateFlow(false)

    /** Starts broadcasting what plays, from the track playing now. */
    fun startBroadcast() {
        val id = playback.value.currentId
        cast.startBroadcast(_tracks.value?.firstOrNull { it.id == id }, _cover.value.takeIf { it.trackId == id }?.bitmap)
    }

    /** Whether the lyrics are on screen: only then are they looked up. */
    val lyricsShown = MutableStateFlow(false)

    /** Bumped by "Réessayer" after a failed lookup. */
    private val lyricsRetry = MutableStateFlow(0)

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    /** The lyrics of the playing track. */
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    fun retryLyrics() = lyricsRetry.update { it + 1 }

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
        // A track counts as listened to once it actually plays.
        viewModelScope.launch {
            playback.map { it.currentId to it.isPlaying }.distinctUntilChanged().collect { (id, playing) ->
                if (id != null && playing && listening.recent.value.firstOrNull() != id) listening.played(id)
            }
        }
        viewModelScope.launch {
            val current = combine(playback.map { it.currentId }.distinctUntilChanged(), _tracks) { id, tracks ->
                tracks?.firstOrNull { it.id == id }
            }.distinctUntilChanged()
            combine(current, lyricsShown, lyricsRetry) { track, shown, _ -> track.takeIf { shown } }
                .collectLatest { track ->
                    if (track == null) return@collectLatest
                    _lyrics.value = LyricsState.Loading
                    _lyrics.value = try {
                        LyricsState.Ready(lyricsRepository.load(track))
                    } catch (e: java.io.IOException) {
                        LyricsState.Offline
                    } catch (e: org.json.JSONException) {
                        LyricsState.Ready(Lyrics.NotFound)
                    }
                }
        }
        // Listeners hear the new track's title, cover and lyrics as it starts.
        viewModelScope.launch {
            combine(_cover, _tracks) { cover, tracks -> cover to tracks }.collect { (cover, tracks) ->
                if (cast.ui.value !is io.github.maelmbange.thighhigh.cast.CastUi.Broadcasting) return@collect
                val id = playback.value.currentId ?: return@collect
                if (cover.trackId != id) return@collect
                cast.setTrack(tracks?.firstOrNull { it.id == id }, cover.bitmap)
            }
        }
        // The playlists (favorites first) can be broadcast as radios.
        viewModelScope.launch {
            val favoritesName = getApplication<Application>().getString(io.github.maelmbange.thighhigh.R.string.favorites)
            combine(playlists, favorites, _tracks) { lists, favs, tracks -> Triple(lists, favs, tracks.orEmpty()) }
                .collect { (lists, favs, tracks) ->
                    cast.setRadioSources(
                        (listOf(favs.copy(name = favoritesName)) + lists).map {
                            io.github.maelmbange.thighhigh.cast.RadioSource(it.id, it.name, it.tracks(tracks))
                        },
                    )
                }
        }
        // Playing our own music again ends listening to someone else's.
        viewModelScope.launch {
            playback.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (playing && cast.isListening) cast.stopListening()
            }
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
                val saved = queueStore.load() ?: return@withContext null
                val byId = scanLibrary(getApplication()).associateBy { it.id }
                val kept = saved.ids.mapNotNull(byId::get)
                val index = kept.indexOfFirst { it.id == saved.ids[saved.index] }
                if (kept.isEmpty()) null else Triple(saved, kept, index)
            } ?: return@launch
            val (saved, kept, index) = restored
            if (c.mediaItemCount != 0) return@launch // something started meanwhile
            c.repeatMode = saved.repeatMode
            c.shuffleModeEnabled = saved.shuffle
            queueSource = saved.source
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
     * player, in order: a random order stays for the "Mélanger" buttons. Tapping
     * the track already playing from the same list only resumes it, and so does
     * tapping it among the recently played (the playlist it came from goes on).
     */
    fun playList(list: List<Track>, track: Track, source: String) {
        playerOpen.value = true
        val c = controller ?: return
        if (_playback.value.currentId == track.id && (queueSource == source || source == RECENT)) {
            if (!c.isPlaying) resume(c)
            return
        }
        queueSource = source
        markPlaylistPlayed(source)
        c.shuffleModeEnabled = false
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
        markPlaylistPlayed(source)
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
        val c = controller ?: return
        // Shown gone at once, and kept so: the controller's guess of the new
        // queue and the service's answer arrive one after the other and could
        // show the track again for a frame.
        val expected = _upNext.value.filterNot { it.index == index }
            .map { if (it.index > index) it.copy(index = it.index - 1) else it }
        _upNext.value = expected
        // The queue shown is frozen while the change settles: the service can
        // pass through a state where the order is briefly another one, which
        // drew the list wrong for a frame.
        queueFrozen?.cancel()
        queueFrozen = viewModelScope.launch {
            delay(QUEUE_SETTLE_MS)
            queueFrozen = null
            controller?.let {
                _upNext.value = upNextOf(it)
                _history.value = historyOf(it)
            }
        }
        c.removeMediaItem(index)
        queueSource = null
    }

    /** Set while the queue shown waits for a removal to settle: see [removeQueued]. */
    private var queueFrozen: kotlinx.coroutines.Job? = null

    /** A playlist started from [source] moves to the top of the list. */
    private fun markPlaylistPlayed(source: String) {
        source.removePrefix(PLAYLIST).takeIf { it != source }?.let { listening.playlistPlayed(it) }
    }

    /** A new playlist is listed first, as if just played. */
    fun createPlaylist(name: String, with: Track? = null): Playlist =
        playlistStore.create(name, listOfNotNull(with?.id)).also { listening.playlistPlayed(it.id) }

    fun addToPlaylist(playlist: Playlist, track: Track) = playlistStore.add(playlist.id, listOf(track.id))

    /** Adds the tracks picked from inside the playlist, at its end. */
    fun addTracks(playlist: Playlist, trackIds: List<Long>) = playlistStore.add(playlist.id, trackIds)

    fun isFavorite(track: Track) = track.id in favorites.value.trackIds

    /** Adds [track] to the favorites, or takes it out. */
    fun toggleFavorite(track: Track) {
        if (isFavorite(track)) playlistStore.remove(FAVORITES_ID, track.id)
        else playlistStore.add(FAVORITES_ID, listOf(track.id))
    }

    fun removeFromPlaylist(playlist: Playlist, track: Track) = playlistStore.remove(playlist.id, track.id)

    fun renamePlaylist(playlist: Playlist, name: String) = playlistStore.rename(playlist.id, name)

    fun deletePlaylist(playlist: Playlist) {
        if (openPlaylistId.value == playlist.id) openPlaylistId.value = null
        // Only the list goes: its tracks stay on the phone and in the library.
        playlistStore.delete(playlist.id)
        listening.forgetPlaylist(playlist.id)
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

    fun next() = controller?.let { it.seekToNext(); expectTrack(it) }

    /** Back to the start of the track, or the previous one in its first seconds. */
    fun previous() = controller?.let { it.seekToPrevious(); expectTrack(it) }

    /** Always the previous track (a swipe), never the start of this one. */
    fun skipPrevious() = controller?.let { it.seekToPreviousMediaItem(); expectTrack(it) }

    /** The track a skip just moved to, held while the service catches up: see [expectTrack]. */
    private var expectedId: Long? = null
    private var expectedUntil = 0L
    private var expectedCheck: kotlinx.coroutines.Job? = null

    /**
     * Right after a skip, the controller already shows the new track, but the
     * service can still report the one before for a moment: the player then
     * flashed the old cover, title and colors. For a short while, a state
     * naming another track is taken as stale and skipped; the player's state
     * is read again once that time is over.
     */
    private fun expectTrack(c: Player) {
        expectedId = c.currentMediaItem?.mediaId?.toLongOrNull()
        expectedUntil = android.os.SystemClock.uptimeMillis() + QUEUE_SETTLE_MS
        expectedCheck?.cancel()
        expectedCheck = viewModelScope.launch {
            delay(QUEUE_SETTLE_MS)
            expectedId = null
            controller?.let(::syncState)
        }
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = Repeat.of(c.repeatMode).next().mode
    }

    /** Polled by the player screen for its progress bar. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    private fun syncState(player: Player) {
        val expected = expectedId
        if (expected != null && android.os.SystemClock.uptimeMillis() < expectedUntil &&
            player.currentMediaItem?.mediaId?.toLongOrNull() != expected
        ) return
        if (queueFrozen == null) {
            _upNext.value = upNextOf(player)
            _history.value = historyOf(player)
        }
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

    private fun MediaItem.toEntry(index: Int): QueueEntry {
        val id = mediaId.toLongOrNull()
        // The library knows the length (the queue only has it for the playing track).
        val track = id?.let { i -> _tracks.value?.firstOrNull { it.id == i } }
        return QueueEntry(
            index = index,
            id = id,
            title = mediaMetadata.title?.toString() ?: "?",
            artist = mediaMetadata.artist?.toString(),
            genre = track?.genre ?: mediaMetadata.genre?.toString(),
            duration = track?.duration,
        )
    }

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
        /** Source of a queue built from a playlist, followed by its id. */
        const val PLAYLIST = "playlist:"
    }

    override fun onCleared() {
        cast.stopAll()
        controller?.removeListener(listener)
        // Releasing the controller does not stop playback: the service keeps playing.
        MediaController.releaseFuture(controllerFuture)
    }
}
