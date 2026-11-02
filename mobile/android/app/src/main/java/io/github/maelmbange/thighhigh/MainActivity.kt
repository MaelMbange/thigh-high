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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
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
import io.github.maelmbange.thighhigh.ui.FilterMenu
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
        if (savedInstanceState == null) handleShare(intent)
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
        handleShare(intent)
    }

    /** "Share" from YouTube (or any app) starts the download right away. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = URL_PATTERN.find(text)?.value ?: return
        viewModel.downloader.start(url)
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
        val tracks by viewModel.tracks.collectAsStateWithLifecycle()
        val visibleTracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
        val filter by viewModel.filter.collectAsStateWithLifecycle()
        val genreScan by viewModel.genreScanner.progress.collectAsStateWithLifecycle()
        var filterSheetOpen by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        val playback by viewModel.playback.collectAsStateWithLifecycle()
        val jobs by viewModel.downloader.jobs.collectAsStateWithLifecycle()
        val cover by viewModel.cover.collectAsStateWithLifecycle()
        val playerOpen by viewModel.playerOpen.collectAsStateWithLifecycle()
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
        val current = tracks?.firstOrNull { it.id == playback.currentId }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (tab) {
                                Tab.Library -> stringResource(R.string.app_name)
                                Tab.Download -> stringResource(R.string.tab_download)
                            },
                            color = TextColors.title,
                        )
                    },
                    actions = {
                        if (tab == Tab.Library && granted) {
                            Box {
                                IconButton(onClick = { filterSheetOpen = true }) {
                                    Icon(Icons.Default.FilterList, stringResource(R.string.filter))
                                }
                                FilterMenu(
                                    expanded = filterSheetOpen,
                                    tracks = tracks.orEmpty(),
                                    current = filter,
                                    onPick = {
                                        viewModel.filter.value = it
                                        filterSheetOpen = false
                                    },
                                    onDismiss = { filterSheetOpen = false },
                                )
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
                    if (current != null) {
                        MiniPlayer(
                            current,
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
                            selected = tab == Tab.Download,
                            onClick = { viewModel.tab.value = Tab.Download },
                            icon = { Icon(Icons.Default.Download, null) },
                            label = { Text(stringResource(R.string.tab_download)) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
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
                            onDelete = { track ->
                                viewModel.delete(track)?.let {
                                    deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                                }
                            },
                            onClearFilter = { viewModel.filter.value = null },
                        )

                    } else {
                        PermissionScreen(onGrant = { launcher.launch(REQUESTED_PERMISSIONS) })
                    }
                    Tab.Download -> DownloadScreen(
                        jobs = jobs,
                        onDownload = viewModel.downloader::start,
                        onCancel = viewModel.downloader::cancel,
                        onDismiss = viewModel.downloader::dismiss,
                    )
                }
            }
        }

        // The full-screen player slides over the tabs; minimizing it (top-left
        // button or Back) only hides it, the music keeps playing.
        AnimatedVisibility(
            visible = playerOpen && current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
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
                    onStop = viewModel::stop,
                    onNext = { viewModel.next() },
                    onPrevious = { viewModel.previous() },
                    onCycleRepeat = viewModel::cycleRepeat,
                    onSeek = { viewModel.seekTo(it) },
                )
            }
        }
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")

        val AUDIO_PERMISSION =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /** Notifications show the playback controls on Android 13+. */
        val REQUESTED_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(AUDIO_PERMISSION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(AUDIO_PERMISSION)
            }
    }
}
