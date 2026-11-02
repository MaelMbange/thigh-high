package io.github.maelmbange.thighhigh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maelmbange.thighhigh.ui.LibraryScreen
import io.github.maelmbange.thighhigh.ui.PermissionScreen
import io.github.maelmbange.thighhigh.ui.ThighHighTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThighHighTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(viewModel)
                }
            }
        }
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, AUDIO_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    @androidx.compose.runtime.Composable
    private fun App(viewModel: LibraryViewModel) {
        var granted by remember { mutableStateOf(hasAudioPermission()) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> granted = result[AUDIO_PERMISSION] == true || hasAudioPermission() }

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

        if (!granted) {
            PermissionScreen(onGrant = { launcher.launch(REQUESTED_PERMISSIONS) })
            return
        }
        val tracks by viewModel.tracks.collectAsStateWithLifecycle()
        val playback by viewModel.playback.collectAsStateWithLifecycle()
        LibraryScreen(
            tracks = tracks,
            playback = playback,
            onTrackClick = viewModel::play,
            onTogglePlay = viewModel::togglePlayPause,
            onRefresh = viewModel::refresh,
        )
    }

    private companion object {
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
