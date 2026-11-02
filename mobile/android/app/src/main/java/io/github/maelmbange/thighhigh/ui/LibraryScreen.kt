package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.genre.GenreScan
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.Track
import kotlin.time.Duration

@Composable
fun LibraryScreen(
    tracks: List<Track>?,
    currentId: Long?,
    filter: Filter?,
    genreScan: GenreScan?,
    onTrackClick: (Track) -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (filter != null) {
            InputChip(
                selected = true,
                onClick = onClearFilter,
                label = { Text("${filter.field.label()} : ${filterValueLabel(filter.value)}") },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.filter_clear), Modifier.size(18.dp)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (genreScan != null) GenreScanBanner(genreScan)
        Box(Modifier.fillMaxSize()) { LibraryList(tracks, currentId, onTrackClick) }
    }
}

@Composable
private fun GenreScanBanner(scan: GenreScan) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val text = if (scan.finished) {
            pluralStringResource(R.plurals.genre_scan_done, scan.total, scan.found, scan.total)
        } else {
            stringResource(R.string.genre_scan_running, scan.done, scan.total)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scan.finished && scan.failed > 0) {
            Text(
                pluralStringResource(R.plurals.genre_scan_failed, scan.failed, scan.failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!scan.finished && scan.total > 0) {
            LinearProgressIndicator(
                progress = { scan.done.toFloat() / scan.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.LibraryList(
    tracks: List<Track>?,
    currentId: Long?,
    onTrackClick: (Track) -> Unit,
) {
        when {
            tracks == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            tracks.isEmpty() -> Text(
                stringResource(R.string.library_empty),
                Modifier.align(Alignment.Center).padding(32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(track, playing = track.id == currentId) { onTrackClick(track) }
                }
            }
        }
}

/** Fixed list colors in the Catppuccin Mocha style, the same for every track. */
private object ListColors {
    val title = Color(0xFFB4BEFE) // Lavender
    val playing = Color(0xFFCBA6F7) // Mauve
    val artist = Color(0xFF89DCEB) // Sky
    val genre = Color(0xFFFAB387) // Peach
    val duration = Color(0xFF7F849C) // Overlay 1
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = if (playing) {
            { Icon(Icons.Default.GraphicEq, null, tint = ListColors.playing) }
        } else {
            null
        },
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            val artist = track.artist ?: stringResource(R.string.unknown_artist)
            Text(
                buildAnnotatedString {
                    append(artist)
                    if (track.genre != null) {
                        append(" · ")
                        withStyle(SpanStyle(color = ListColors.genre)) { append(track.genre) }
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { track.duration?.let { Text(formatDuration(it)) } },
        colors = ListItemDefaults.colors(
            headlineColor = if (playing) ListColors.playing else ListColors.title,
            supportingColor = ListColors.artist,
            trailingIconColor = ListColors.duration,
        ),
    )
}

@Composable
fun MiniPlayer(track: Track, isPlaying: Boolean, onOpen: () -> Unit, onTogglePlay: () -> Unit) {
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist ?: stringResource(R.string.unknown_artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    if (isPlaying) {
                        Icon(Icons.Default.Pause, stringResource(R.string.pause))
                    } else {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.permission_needed),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
        }
    }
}

private fun formatDuration(d: Duration): String {
    val s = d.inWholeSeconds
    return "%d:%02d".format(s / 60, s % 60)
}
