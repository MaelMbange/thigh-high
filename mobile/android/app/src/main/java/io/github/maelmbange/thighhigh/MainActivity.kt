package io.github.maelmbange.thighhigh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import io.github.maelmbange.thighhigh.ui.TextColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import io.github.maelmbange.thighhigh.ui.SettingsSheet
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maelmbange.thighhigh.ui.DownloadScreen
import io.github.maelmbange.thighhigh.download.parseLinks
import io.github.maelmbange.thighhigh.ui.PlaylistsScreen
import io.github.maelmbange.thighhigh.ui.TrackActionsSheet
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import io.github.maelmbange.thighhigh.ui.FilterSheet
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import io.github.maelmbange.thighhigh.ui.LibraryScreen
import io.github.maelmbange.thighhigh.ui.MiniPlayer
import io.github.maelmbange.thighhigh.ui.PermissionScreen
import io.github.maelmbange.thighhigh.ui.PlayerScreen
import io.github.maelmbange.thighhigh.ui.ThighHighTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            // The whole interface follows the cover of the track that plays.
            val cover by viewModel.cover.collectAsStateWithLifecycle()
            ThighHighTheme(cover.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(viewModel)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and other apps can bring the bar back; hide it again on return.
        if (hasFocus) hideNavigationBar()
    }

    /**
     * Hides the phone's navigation bar. A swipe up from the bottom edge shows it
     * for a moment, then it hides again by itself.
     */
    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * The media notification opens the player; "Share" from YouTube (or any
     * app) starts the download right away.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) {
            viewModel.playerOpen.value = true
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val links = parseLinks(text).filter { it.startsWith("http") }
        if (links.isEmpty()) return
        links.forEach(viewModel.downloader::start)
        viewModel.tab.value = Tab.Download
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, AUDIO_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(viewModel: LibraryViewModel) {
        var granted by remember { mutableStateOf(hasAudioPermission()) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> granted = result[AUDIO_PERMISSION] == true || hasAudioPermission() }

        // Android shows its own "delete this file?" dialog; its answer comes back here.
        val deleteLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result -> viewModel.onDeleteConfirmed(result.resultCode == RESULT_OK) }

        LaunchedEffect(Unit) {
            if (!granted) launcher.launch(REQUESTED_PERMISSIONS)
        }
        // Rescan whenever the app comes back to the foreground (new files copied in).
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            granted = hasAudioPermission()
            if (granted) viewModel.refresh()
        }
        LaunchedEffect(granted) {
            if (granted) viewModel.refresh()
        }

        val tab by viewModel.tab.collectAsStateWithLifecycle()
        val pagerState = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
        // The bar picks a tab: slide to it. A swipe lands on one: the bar follows.
        LaunchedEffect(tab) {
            if (pagerState.targetPage != tab.ordinal) pagerState.animateScrollToPage(tab.ordinal)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { viewModel.tab.value = Tab.entries[it] }
        }
        val tracks by viewModel.tracks.collectAsStateWithLifecycle()
        val visibleTracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
        val filter by viewModel.filter.collectAsStateWithLifecycle()
        val query by viewModel.query.collectAsStateWithLifecycle()
        val genreScan by viewModel.genreScanner.progress.collectAsStateWithLifecycle()
        var filterSheetOpen by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        val playback by viewModel.playback.collectAsStateWithLifecycle()
        val jobs by viewModel.downloader.jobs.collectAsStateWithLifecycle()
        val ytdlp by viewModel.downloader.ytdlp.collectAsStateWithLifecycle()
        val cover by viewModel.cover.collectAsStateWithLifecycle()
        val playerOpen by viewModel.playerOpen.collectAsStateWithLifecycle()
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()
        val openPlaylistId by viewModel.openPlaylistId.collectAsStateWithLifecycle()
        val upNext by viewModel.upNext.collectAsStateWithLifecycle()
        val history by viewModel.history.collectAsStateWithLifecycle()
        val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
        val neighbors by viewModel.neighbors.collectAsStateWithLifecycle()
        var settingsOpen by remember { mutableStateOf(false) }
        val pulseSource by viewModel.pulseSource.collectAsStateWithLifecycle()
        // The track whose actions sheet is open, and the playlist it was pressed in.
        var actionTrack by remember { mutableStateOf<Pair<Track, Playlist?>?>(null) }
        val current = tracks?.firstOrNull { it.id == playback.currentId }
        // Kept so the mini player can fold away with its last track shown.
        var lastTrack by remember { mutableStateOf<Track?>(null) }
        if (current != null) lastTrack = current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (tab) {
                                Tab.Library -> stringResource(R.string.app_name)
                                Tab.Playlists -> stringResource(R.string.tab_playlists)
                                Tab.Download -> stringResource(R.string.tab_download)
                            },
                            color = TextColors.title,
                        )
                    },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.settings))
                        }
                        if (tab == Tab.Library && granted) {
                            // A dot on the button while a filter is on.
                            IconButton(onClick = { filterSheetOpen = true }) {
                                BadgedBox(badge = { if (filter != null) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                                    Icon(Icons.Default.FilterList, stringResource(R.string.filter))
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detect_genres)) },
                                        enabled = genreScan?.finished != false,
                                        onClick = {
                                            menuOpen = false
                                            viewModel.detectMissingGenres()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    // The mini player unfolds above the tabs when a track is loaded.
                    AnimatedVisibility(
                        visible = current != null,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        val track = current ?: lastTrack ?: return@AnimatedVisibility
                        MiniPlayer(
                            track,
                            playback.isPlaying,
                            durationMs = playback.durationMs,
                            positionMs = viewModel::positionMs,
                            onNext = { viewModel.next() },
                            onOpen = { viewModel.playerOpen.value = true },
                            onTogglePlay = viewModel::togglePlayPause,
                        )
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.Library,
                            onClick = { viewModel.tab.value = Tab.Library },
                            icon = { Icon(Icons.Default.LibraryMusic, null) },
                            label = { Text(stringResource(R.string.tab_library)) },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Playlists,
                            onClick = {
                                // Tapping the tab again goes back to the list of playlists.
                                if (tab == Tab.Playlists) viewModel.openPlaylistId.value = null
                                viewModel.tab.value = Tab.Playlists
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            label = { Text(stringResource(R.string.tab_playlists)) },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Download,
                            onClick = { viewModel.tab.value = Tab.Download },
                            icon = { Icon(Icons.Default.Download, null) },
                            label = { Text(stringResource(R.string.tab_download)) },
                        )
                    }
                }
            },
        ) { padding ->
            // The tabs sit side by side: a swipe left or right moves to the
            // next one, and tapping a tab in the bar slides there too.
            HorizontalPager(
                pagerState,
                Modifier.padding(padding),
                key = { Tab.entries[it] },
            ) { page ->
              val tab = Tab.entries[page]
              Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.Library -> if (granted) {
                        LibraryScreen(
                            tracks = visibleTracks,
                            currentId = playback.currentId,
                            isPlaying = playback.isPlaying,
                            filter = filter,
                            genreScan = genreScan,
                            refreshing = refreshing,
                            onRefresh = viewModel::pullRefresh,
                            onTrackClick = viewModel::play,
                            onTrackLongClick = { actionTrack = it to null },
                            onDelete = { track ->
                                viewModel.delete(track)?.let {
                                    deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                                }
                            },
                            onClearFilter = { viewModel.filter.value = null },
                            query = query,
                            onQueryChange = { viewModel.query.value = it },
                            onShufflePlay = viewModel::shufflePlay,
                        )

                    } else {
                        PermissionScreen(onGrant = { launcher.launch(REQUESTED_PERMISSIONS) })
                    }
                    Tab.Playlists -> {
                        BackHandler(enabled = openPlaylistId != null && pagerState.currentPage == Tab.Playlists.ordinal) {
                            viewModel.openPlaylistId.value = null
                        }
                        PlaylistsScreen(
                            playlists = playlists,
                            library = tracks.orEmpty(),
                            openId = openPlaylistId,
                            currentId = playback.currentId,
                            isPlaying = playback.isPlaying,
                            onOpen = { viewModel.openPlaylistId.value = it },
                            onCreate = { viewModel.createPlaylist(it) },
                            onPlay = { p, list, track -> viewModel.playList(list, track, "playlist:${p.id}") },
                            onShuffle = { p, list -> viewModel.shufflePlay(list, "playlist:${p.id}") },
                            onTrackLongClick = { p, track -> actionTrack = track to p },
                            onRemove = viewModel::removeFromPlaylist,
                            onRename = viewModel::renamePlaylist,
                            onDelete = viewModel::deletePlaylist,
                        )
                    }
                    Tab.Download -> DownloadScreen(
                        jobs = jobs,
                        onDownload = viewModel.downloader::start,
                        onCancel = viewModel.downloader::cancel,
                        onDismiss = viewModel.downloader::dismiss,
                        ytdlp = ytdlp,
                        onUpdateYtDlp = viewModel.downloader::update,
                        onDownloadAnyway = viewModel.downloader::downloadAnyway,
                        onKeepBoth = viewModel.downloader::keepBoth,
                        onReplaceOld = { job ->
                            viewModel.replaceOld(job)?.let {
                                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                            }
                        },
                    )
                }
              }
            }
        }

        if (settingsOpen) {
            SettingsSheet(
                pulseSource = pulseSource,
                onPulseSource = viewModel::setPulseSource,
                onDismiss = { settingsOpen = false },
            )
        }

        if (filterSheetOpen) {
            FilterSheet(
                tracks = tracks.orEmpty(),
                current = filter,
                onPick = { viewModel.filter.value = it },
                onDismiss = { filterSheetOpen = false },
            )
        }

        actionTrack?.let { (track, from) ->
            TrackActionsSheet(
                track = track,
                playlists = playlists,
                fromPlaylist = from,
                onPlayNext = { viewModel.playNext(track) },
                onAddToQueue = { viewModel.addToQueue(track) },
                onAddToPlaylist = { viewModel.addToPlaylist(it, track) },
                onCreatePlaylist = { viewModel.createPlaylist(it, with = track) },
                onRemoveFromPlaylist = { from?.let { viewModel.removeFromPlaylist(it, track) } },
                onDismiss = { actionTrack = null },
            )
        }

        // The full-screen player slides over the tabs; minimizing it (top-left
        // button or Back) only hides it, the music keeps playing.
        // Opening rises with a soft spring; minimizing sinks it toward the
        // mini player, shrinking and fading a little on the way.
        AnimatedVisibility(
            visible = playerOpen && current != null,
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } +
                fadeIn(tween(200)),
            exit = slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { it } +
                scaleOut(tween(340), targetScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(tween(340, delayMillis = 60)),
        ) {
            current?.let { track ->
                BackHandler { viewModel.playerOpen.value = false }
                PlayerScreen(
                    track = track,
                    playback = playback,
                    cover = cover,
                    positionMs = viewModel::positionMs,
                    onMinimize = { viewModel.playerOpen.value = false },
                    onTogglePlay = viewModel::togglePlayPause,
                    onNext = { viewModel.next() },
                    onPrevious = { viewModel.previous() },
                    onSkipPrevious = { viewModel.skipPrevious() },
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onSeek = { viewModel.seekTo(it) },
                    upNext = upNext,
                    history = history,
                    neighbors = neighbors,
                    onPlayQueued = viewModel::playQueued,
                    onRemoveQueued = viewModel::removeQueued,
                    lyrics = lyrics,
                    onLyricsShown = { viewModel.lyricsShown.value = it },
                    onRetryLyrics = viewModel::retryLyrics,
                    pulseSource = pulseSource,
                )
            }
        }
    }

    companion object {
        /** Sent by the media notification and the lock screen. */
        const val ACTION_OPEN_PLAYER = "io.github.maelmbange.thighhigh.OPEN_PLAYER"


        private val AUDIO_PERMISSION =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /** Notifications show the playback controls on Android 13+. */
        private val REQUESTED_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(AUDIO_PERMISSION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(AUDIO_PERMISSION)
            }
    }
}
