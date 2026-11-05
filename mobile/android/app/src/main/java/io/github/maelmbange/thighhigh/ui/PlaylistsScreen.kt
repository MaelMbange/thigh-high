package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
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
    /** The playlists were dragged into this order (their ids). */
    onArrange: (List<String>) -> Unit = {},
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
            PlaylistGrid(playlists, library, onOpen = { onOpen(it.id) }, onCreate = onCreate, onDelete = onDelete, onArrange = onArrange)
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

/**
 * The playlists as a grid of covers, two by row (a trial: the list it
 * replaced may come back), led by a tile that creates one.
 *
 * A long press then a drag moves a playlist: the others make room as it
 * goes, and the order is kept when it is let go. A long press without
 * moving offers to delete it, as before.
 */
@Composable
private fun PlaylistGrid(
    playlists: List<Playlist>,
    library: List<Track>,
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Playlist) -> Unit,
    onArrange: (List<String>) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    val inLibrary = remember(library) { library.mapTo(HashSet()) { it.id } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // The order shown: the playlists' own, or the one being dragged into.
    var order by remember { mutableStateOf(playlists) }
    var dragged by remember { mutableStateOf<String?>(null) }
    // Where the finger is in the grid, and where it took the tile.
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }
    var travelled by remember { mutableStateOf(0f) }
    var swappedWith by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playlists) { if (dragged == null) order = playlists }
    val slop = LocalViewConfiguration.current.touchSlop

    fun tileAt(position: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val x = position.x - item.offset.x
        val y = position.y - item.offset.y
        item.key != "new" && x in 0f..item.size.width.toFloat() && y in 0f..item.size.height.toFloat()
    }

    fun finish() {
        val id = dragged ?: return
        dragged = null
        if (moved) {
            onArrange(order.map { it.id })
        } else {
            deleting = order.firstOrNull { it.id == id }
        }
    }

    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().testTag("playlistGrid").pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    val tile = tileAt(start) ?: return@detectDragGesturesAfterLongPress
                    dragged = tile.key as String
                    moved = false
                    travelled = 0f
                    swappedWith = null
                    finger = start
                    grab = start - Offset(tile.offset.x.toFloat(), tile.offset.y.toFloat())
                },
                onDrag = { change, amount ->
                    val id = dragged ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    finger += amount
                    travelled += amount.getDistance()
                    if (travelled > slop) moved = true
                    // Over another tile: the dragged one takes its place. Until
                    // the grid has moved them, the finger is still over the tile
                    // just swapped with: that one doesn't count again.
                    val target = tileAt(finger)?.key as? String
                    if (target != swappedWith) swappedWith = null
                    if (target != null && target != id && target != swappedWith) {
                        swappedWith = target
                        val from = order.indexOfFirst { it.id == id }
                        val to = order.indexOfFirst { it.id == target }
                        if (from >= 0 && to >= 0) {
                            order = order.toMutableList().apply { add(to, removeAt(from)) }
                        }
                    }
                    // Near an edge, the grid scrolls along.
                    val edge = 64.dp.toPx()
                    val height = grid.layoutInfo.viewportSize.height
                    val step = when {
                        finger.y < edge -> -20f
                        finger.y > height - edge -> 20f
                        else -> 0f
                    }
                    if (step != 0f) scope.launch { grid.scrollBy(step) }
                },
                onDragEnd = { finish() },
                onDragCancel = { finish() },
            )
        },
        state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "new") {
            Column(
                Modifier.clickable { naming = true },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, TextColors.playing.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(40.dp), tint = TextColors.playing)
                }
                Text(
                    stringResource(R.string.playlist_new),
                    color = TextColors.playing,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        items(order, key = { it.id }) { playlist ->
            val lifted = playlist.id == dragged
            BoxWithConstraints(
                (if (lifted) Modifier else Modifier.animateItem())
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) {
                            // Follows the finger from wherever the grid has put it.
                            val tile = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == playlist.id }
                            if (tile != null) {
                                translationX = finger.x - grab.x - tile.offset.x
                                translationY = finger.y - grab.y - tile.offset.y
                            }
                            scaleX = 1.06f
                            scaleY = 1.06f
                            shadowElevation = 16.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                        }
                    }
                    .testTag("playlistTile"),
            ) {
                val side = maxWidth
                Column(
                    // The press of a drag (or of the long press) isn't a tap.
                    Modifier.clickable { if (dragged == null) onOpen(playlist) },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistCover(playlist.trackIds.filter { it in inLibrary }, side, corner = 16.dp)
                    Column {
                        Text(playlist.name, color = TextColors.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.track_count, playlist.trackIds.size, playlist.trackIds.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextColors.info,
                        )
                    }
                }
            }
        }
        if (order.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.playlists_empty),
                    Modifier.fillMaxWidth().padding(16.dp),
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
fun PlaylistRow(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    /** The tracks shown on its cover: those still in the library. */
    coverIds: List<Long> = playlist.trackIds,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { PlaylistCover(coverIds, 48.dp, corner = 10.dp) },
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
    icon: @Composable () -> Unit = { PlaylistCover(tracks.map { it.id }, 56.dp, corner = 10.dp) },
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
