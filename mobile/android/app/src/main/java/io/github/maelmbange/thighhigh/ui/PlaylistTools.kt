package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.matchesSearch

/**
 * Deleting a playlist asks twice: the user's choice and order of its tracks
 * would be lost. The tracks themselves stay in the app.
 */
@Composable
fun DeletePlaylistDialog(playlist: Playlist, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    val count = playlist.trackIds.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (step == 1) stringResource(R.string.playlist_delete_title, playlist.name)
                else stringResource(R.string.playlist_delete_really),
            )
        },
        text = {
            Text(
                if (step == 1) pluralStringResource(R.plurals.playlist_delete_confirm, count, count)
                else stringResource(R.string.playlist_delete_really_text, playlist.name),
            )
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = { step = 2 }) { Text(stringResource(R.string.go_on), color = DeleteRed) }
            } else {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.playlist_delete_forever), color = DeleteRed) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Picks tracks of the library to add to a playlist, from inside it: tick as
 * many as wanted, then "Ajouter". Those already in it are shown ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPickerSheet(
    library: List<Track>,
    already: Set<Long>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // In the order ticked, which is the order they are added in.
    val picked = remember { mutableStateListOf<Long>() }
    val shown = remember(library, query) { library.filter { it.matchesSearch(query) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.playlist_add_tracks),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColors.title,
                )
                Button(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) {
                    Text(
                        if (picked.isEmpty()) stringResource(R.string.picker_add)
                        else stringResource(R.string.picker_add_count, picked.size),
                    )
                }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.search_clear)) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { track ->
                    val inside = track.id in already
                    val checked = inside || track.id in picked
                    fun toggle() {
                        if (inside) return
                        if (track.id in picked) picked.remove(track.id) else picked.add(track.id)
                    }
                    ListItem(
                        modifier = Modifier.clickable(enabled = !inside) { toggle() },
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (inside) stringResource(R.string.playlist_contains)
                                else track.artist ?: stringResource(R.string.unknown_artist),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle() },
                                enabled = !inside,
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            headlineColor = if (inside) TextColors.info else TextColors.title,
                            supportingColor = if (inside) TextColors.info else TextColors.artist,
                        ),
                    )
                }
            }
        }
    }
}
