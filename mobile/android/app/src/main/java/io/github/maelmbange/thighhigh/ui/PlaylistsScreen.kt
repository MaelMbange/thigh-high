package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track

/** The Playlists tab: every playlist, or the tracks of the one opened. */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    library: List<Track>,
    openId: String?,
    currentId: Long?,
    isPlaying: Boolean,
    onOpen: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onPlay: (Playlist, List<Track>, Track) -> Unit,
    onShuffle: (Playlist, List<Track>) -> Unit,
    onTrackLongClick: (Playlist, Track) -> Unit,
    onRemove: (Playlist, Track) -> Unit,
    onRename: (Playlist, String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val open = playlists.firstOrNull { it.id == openId }
    // Opening a playlist slides it in from the right; going back slides it out.
    AnimatedContent(
        open,
        transitionSpec = {
            val forward = targetState != null
            val spec = tween<androidx.compose.ui.unit.IntOffset>(320)
            if (forward) {
                (slideInHorizontally(spec) { it } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(220)))
            } else {
                (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(spec) { it } + fadeOut(tween(220)))
            }
        },
        contentKey = { it?.id },
        label = "playlist",
    ) { playlist ->
        if (playlist == null) {
            PlaylistList(playlists, onOpen = { onOpen(it.id) }, onCreate = onCreate)
        } else {
            PlaylistDetail(
                playlist,
                tracks = remember(playlist, library) { playlist.tracks(library) },
                currentId = currentId,
                isPlaying = isPlaying,
                onBack = { onOpen(null) },
                onPlay = { list, track -> onPlay(playlist, list, track) },
                onShuffle = { onShuffle(playlist, it) },
                onTrackLongClick = { onTrackLongClick(playlist, it) },
                onRemove = { onRemove(playlist, it) },
                onRename = { onRename(playlist, it) },
                onDelete = { onDelete(playlist) },
            )
        }
    }
}

@Composable
private fun PlaylistList(playlists: List<Playlist>, onOpen: (Playlist) -> Unit, onCreate: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                modifier = Modifier.clickable { naming = true },
                leadingContent = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, tint = TextColors.playing)
                    }
                },
                headlineContent = { Text(stringResource(R.string.playlist_new), color = TextColors.playing, fontWeight = FontWeight.SemiBold) },
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            ListItem(
                modifier = Modifier.clickable { onOpen(playlist) }.animateItem(),
                leadingContent = { PlaylistIcon() },
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.track_count, playlist.trackIds.size, playlist.trackIds.size))
                },
                colors = ListItemDefaults.colors(headlineColor = TextColors.title, supportingColor = TextColors.info),
            )
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.playlists_empty),
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextColors.info,
                )
            }
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirm = stringResource(R.string.create),
            onConfirm = {
                naming = false
                onCreate(it)
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    currentId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: (List<Track>, Track) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onTrackLongClick: (Track) -> Unit,
    onRemove: (Track) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            PlaylistIcon(56)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextColors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.track_count, tracks.size, tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextColors.info,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_delete), color = DeleteRed) },
                        onClick = { menu = false; deleting = true },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { onPlay(tracks, tracks.first()) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null)
                Text(stringResource(R.string.play), Modifier.padding(start = 6.dp))
            }
            FilledTonalButton(onClick = { onShuffle(tracks) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, null)
                Text(stringResource(R.string.shuffle_play), Modifier.padding(start = 6.dp))
            }
        }
        if (tracks.isEmpty()) {
            Text(
                stringResource(R.string.playlist_empty),
                Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = TextColors.info,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tracks, key = { it.id }) { track ->
                Box(Modifier.animateItem()) {
                    SwipeToReveal(
                        onAction = { onRemove(track) },
                        icon = Icons.Default.RemoveCircleOutline,
                        label = R.string.remove,
                    ) {
                        TrackRow(
                            track,
                            current = track.id == currentId,
                            animate = isPlaying,
                            onLongClick = { onTrackLongClick(track) },
                        ) { onPlay(tracks, track) }
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.rename),
            initial = playlist.name,
            confirm = stringResource(R.string.save),
            onConfirm = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.playlist_delete)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; onDelete() }) { Text(stringResource(R.string.delete), color = DeleteRed) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
