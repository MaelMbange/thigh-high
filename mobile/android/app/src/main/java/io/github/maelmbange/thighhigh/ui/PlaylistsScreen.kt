package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    onAddTracks: (Playlist, List<Long>) -> Unit,
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
            PlaylistList(playlists, onOpen = { onOpen(it.id) }, onCreate = onCreate, onDelete = onDelete)
        } else {
            PlaylistDetail(
                playlist,
                tracks = remember(playlist, library) { playlist.tracks(library) },
                library = library,
                currentId = currentId,
                isPlaying = isPlaying,
                onBack = { onOpen(null) },
                onPlay = { list, track -> onPlay(playlist, list, track) },
                onShuffle = { onShuffle(playlist, it) },
                onTrackLongClick = { onTrackLongClick(playlist, it) },
                onRemove = { onRemove(playlist, it) },
                onRename = { onRename(playlist, it) },
                onDelete = { onDelete(playlist) },
                onAddTracks = { onAddTracks(playlist, it) },
            )
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<Playlist>,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
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
            PlaylistRow(
                playlist,
                Modifier.animateItem(),
                onClick = { onOpen(playlist) },
                onLongClick = { deleting = playlist },
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
    deleting?.let { playlist ->
        DeletePlaylistDialog(
            playlist,
            onConfirm = {
                deleting = null
                onDelete(playlist)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A playlist in a list: a tap opens it, a long press offers to delete it. */
@Composable
fun PlaylistRow(playlist: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { PlaylistIcon() },
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.track_count, playlist.trackIds.size, playlist.trackIds.size))
        },
        colors = ListItemDefaults.colors(headlineColor = TextColors.title, supportingColor = TextColors.info),
    )
}

/**
 * One playlist's tracks. The favorites use it too, with their own [title],
 * and without renaming or deleting ([editable] false).
 */
@Composable
fun PlaylistDetail(
    playlist: Playlist,
    tracks: List<Track>,
    library: List<Track>,
    currentId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: (List<Track>, Track) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onTrackLongClick: (Track) -> Unit,
    onRemove: (Track) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddTracks: (List<Long>) -> Unit,
    title: String = playlist.name,
    editable: Boolean = true,
    empty: String = stringResource(R.string.playlist_empty),
    icon: @Composable () -> Unit = { PlaylistIcon(56) },
) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            icon()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    title,
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
            if (editable) Box {
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
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListItem(
                    modifier = Modifier.clickable { picking = true },
                    leadingContent = {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, tint = TextColors.playing)
                        }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.playlist_add_tracks), color = TextColors.playing, fontWeight = FontWeight.SemiBold)
                    },
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        empty,
                        Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextColors.info,
                    )
                }
            }
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
        DeletePlaylistDialog(
            playlist,
            onConfirm = { deleting = false; onDelete() },
            onDismiss = { deleting = false },
        )
    }
    if (picking) {
        TrackPickerSheet(
            library = library,
            already = remember(playlist) { playlist.trackIds.toSet() },
            onAdd = {
                picking = false
                onAddTracks(it)
            },
            onDismiss = { picking = false },
        )
    }
}
