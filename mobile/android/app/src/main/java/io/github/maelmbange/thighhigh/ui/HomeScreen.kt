package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track

/**
 * The first tab: the favorites and the whole library as two tiles, the last
 * tracks played, then the playlists, the last played first.
 */
@Composable
fun HomeScreen(
    favoritesCount: Int,
    libraryCount: Int?,
    recent: List<Track>,
    playlists: List<Playlist>,
    currentId: Long?,
    isPlaying: Boolean,
    onOpenFavorites: () -> Unit,
    onOpenAll: () -> Unit,
    onPlayRecent: (Track) -> Unit,
    onTrackLongClick: (Track) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeTile(
                    Icons.Default.Favorite,
                    stringResource(R.string.favorites),
                    pluralStringResource(R.plurals.track_count, favoritesCount, favoritesCount),
                    MaterialTheme.colorScheme.primary,
                    Modifier.weight(1f),
                    onOpenFavorites,
                )
                HomeTile(
                    Icons.Default.LibraryMusic,
                    stringResource(R.string.home_all),
                    libraryCount?.let { pluralStringResource(R.plurals.track_count, it, it) }.orEmpty(),
                    MaterialTheme.colorScheme.tertiary,
                    Modifier.weight(1f),
                    onOpenAll,
                )
            }
        }
        item { SectionTitle(stringResource(R.string.home_recent)) }
        if (recent.isEmpty()) {
            item { Hint(stringResource(R.string.home_recent_empty)) }
        }
        items(recent, key = { "recent-${it.id}" }) { track ->
            Box(Modifier.animateItem()) {
                TrackRow(
                    track,
                    current = track.id == currentId,
                    animate = isPlaying,
                    onLongClick = { onTrackLongClick(track) },
                ) { onPlayRecent(track) }
            }
        }
        item {
            SectionTitle(stringResource(R.string.tab_playlists)) {
                TextButton(onClick = { naming = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.playlist_new_short), Modifier.padding(start = 6.dp))
                }
            }
        }
        if (playlists.isEmpty()) {
            item { Hint(stringResource(R.string.home_playlists_empty)) }
        }
        items(playlists, key = { "playlist-${it.id}" }) { playlist ->
            PlaylistRow(
                playlist,
                Modifier.animateItem(),
                onClick = { onOpenPlaylist(playlist) },
                onLongClick = { deleting = playlist },
            )
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            confirm = stringResource(R.string.create),
            onConfirm = {
                naming = false
                onCreatePlaylist(it)
            },
            onDismiss = { naming = false },
        )
    }
    deleting?.let { playlist ->
        DeletePlaylistDialog(
            playlist,
            onConfirm = {
                deleting = null
                onDeletePlaylist(playlist)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A big tile that opens a list: the favorites or every track. */
@Composable
private fun HomeTile(icon: ImageVector, title: String, detail: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .height(96.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, tint = accent)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextColors.title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TextColors.info)
        }
    }
}

@Composable
private fun SectionTitle(text: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextColors.title,
        )
        action()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = TextColors.info,
    )
}

/** The tile standing for the favorites at the top of their list. */
@Composable
fun FavoritesIcon(size: Int = 56) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary)
    }
}
