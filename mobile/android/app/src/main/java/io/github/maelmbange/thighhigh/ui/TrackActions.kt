package io.github.maelmbange.thighhigh.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track

/**
 * What a long press on a track offers: play it next, add it to the queue or
 * to a playlist (picked on a second page of the sheet), or take it out of the
 * playlist it was pressed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: Track,
    playlists: List<Playlist>,
    fromPlaylist: Playlist?,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    fun done(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextColors.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
            AnimatedContent(
                picking,
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * dir / 3 } + fadeOut())
                },
                label = "actions",
            ) { pick ->
                if (!pick) {
                    Column {
                        val playNext = stringResource(R.string.queued_next)
                        val queued = stringResource(R.string.queued_last)
                        val removed = stringResource(R.string.playlist_removed)
                        ActionRow(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.play_next)) {
                            onPlayNext()
                            done(playNext)
                        }
                        ActionRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.add_to_queue)) {
                            onAddToQueue()
                            done(queued)
                        }
                        ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) {
                            picking = true
                        }
                        if (fromPlaylist != null) {
                            ActionRow(Icons.Default.RemoveCircleOutline, stringResource(R.string.remove_from_playlist)) {
                                onRemoveFromPlaylist()
                                done(removed)
                            }
                        }
                    }
                } else {
                    Column {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.add_to_playlist), fontWeight = FontWeight.SemiBold) },
                            leadingContent = {
                                IconButton(onClick = { picking = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            item {
                                ActionRow(Icons.Default.Add, stringResource(R.string.playlist_new), accent = true) { naming = true }
                            }
                            items(playlists, key = { it.id }) { playlist ->
                                val already = track.id in playlist.trackIds
                                val added = stringResource(R.string.playlist_added, playlist.name)
                                ListItem(
                                    modifier = Modifier.clickable(enabled = !already) {
                                        onAddToPlaylist(playlist)
                                        done(added)
                                    },
                                    leadingContent = { PlaylistIcon() },
                                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            if (already) stringResource(R.string.playlist_contains)
                                            else pluralStringResource(R.plurals.track_count, playlist.trackIds.size, playlist.trackIds.size),
                                        )
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        headlineColor = if (already) TextColors.info else TextColors.title,
                                        supportingColor = TextColors.info,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (naming) {
        val created = stringResource(R.string.playlist_created)
        NameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirm = stringResource(R.string.create),
            onConfirm = {
                naming = false
                onCreatePlaylist(it)
                done(created)
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = if (accent) TextColors.playing else TextColors.title) },
        headlineContent = { Text(label) },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = if (accent) TextColors.playing else TextColors.title,
        ),
    )
}

/** The square tile that stands for a playlist. */
@Composable
fun PlaylistIcon(size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Asks for a playlist name: to create one, or to rename it. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
