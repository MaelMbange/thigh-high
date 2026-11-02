package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.QueueEntry
import io.github.maelmbange.thighhigh.R

/** What plays next, in order: tap to jump to a track, × to take it out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(upNext: List<QueueEntry>, onPlay: (Int) -> Unit, onRemove: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.queue),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextColors.title,
            )
            Text(
                pluralStringResource(R.plurals.track_count, upNext.size, upNext.size),
                Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextColors.info,
            )
            if (upNext.isEmpty()) {
                Text(
                    stringResource(R.string.queue_empty),
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = TextColors.info,
                )
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(upNext, key = { _, e -> e.index to e.id }) { position, entry ->
                    ListItem(
                        modifier = Modifier.clickable { onPlay(entry.index) }.animateItem(),
                        leadingContent = { Text("${position + 1}", color = TextColors.info) },
                        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(entry.artist ?: stringResource(R.string.unknown_artist), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(entry.index) }) {
                                Icon(Icons.Default.Close, stringResource(R.string.remove))
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            headlineColor = TextColors.title,
                            supportingColor = TextColors.artist,
                        ),
                    )
                }
            }
        }
    }
}
