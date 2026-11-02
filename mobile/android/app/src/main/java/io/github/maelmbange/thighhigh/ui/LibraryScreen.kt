package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    tracks: List<Track>?,
    currentId: Long?,
    isPlaying: Boolean,
    filter: Filter?,
    genreScan: GenreScan?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onDelete: (Track) -> Unit,
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
        // Pull the list down to rescan the music folder.
        // Pull the list down to rescan the music folder: a faded banner slides
        // in above the list instead of the usual spinning icon.
        val state = rememberPullToRefreshState()
        val bannerHeight = 36.dp
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = state,
            indicator = { RefreshBanner(state.distanceFraction, refreshing, bannerHeight) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = bannerHeight.toPx() * state.distanceFraction.coerceIn(0f, 1f) }
            ) {
                LibraryList(tracks, currentId, isPlaying, onTrackClick, onDelete)
            }
        }
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
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
    onDelete: (Track) -> Unit,
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
                    SwipeToReveal(onDelete = { onDelete(track) }) {
                        TrackRow(track, current = track.id == currentId, animate = isPlaying) { onTrackClick(track) }
                    }
                }
            }
        }
}

private val ListColors = TextColors

@Composable
private fun TrackRow(track: Track, current: Boolean, animate: Boolean, onClick: () -> Unit) {
    val playing = current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = if (playing) {
            { PlayingBars(animate, ListColors.playing) }
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
            trailingIconColor = ListColors.info,
        ),
    )
}

@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: () -> Long,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    // Fixed Catppuccin colors, like the list, whatever the cover looks like.
    val base = MaterialTheme.colorScheme.surfaceContainer
    val accent = ListColors.playing
    Surface(onClick = onOpen, color = base) {
        Column(
            Modifier.background(
                Brush.horizontalGradient(listOf(lerp(base, accent, 0.30f), lerp(base, accent, 0.08f)))
            )
        ) {
            MiniProgress(durationMs, positionMs)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        color = ListColors.title,
                    )
                    Text(
                        track.artist ?: stringResource(R.string.unknown_artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = ListColors.artist,
                    )
                }
                FilledIconButton(
                    onClick = onTogglePlay,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color.Black),
                ) {
                    if (isPlaying) {
                        Icon(Icons.Default.Pause, stringResource(R.string.pause))
                    } else {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                    }
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.next), tint = TextColors.title)
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

/**
 * The five-bar equalizer icon drawn for the current track, on the same 24-unit
 * grid as the Material GraphicEq icon. The bars sway gently while it plays and
 * keep the icon's own shape when it is paused or stopped.
 */
@Composable
private fun PlayingBars(animate: Boolean, color: Color) {
    // Heights of the GraphicEq bars, in units of its 24-unit grid.
    val rest = floatArrayOf(4f, 12f, 20f, 12f, 4f)
    val time = if (animate) {
        val transition = rememberInfiniteTransition(label = "bars")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "phase",
        ).value
    } else {
        null
    }
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        for (i in rest.indices) {
            // Each bar runs at its own speed and phase so they never move in step.
            val wave = time?.let { 4f * sin(it * (i % 2 + 1) + i * 1.3f) } ?: 0f
            val h = (rest[i] + wave).coerceIn(3f, 22f) * unit
            drawRect(
                color = color,
                topLeft = Offset((3f + 4f * i) * unit, (size.height - h) / 2),
                size = Size(2f * unit, h),
            )
        }
    }
}

/** A plain, thin progress line along the top of the mini player. */
@Composable
private fun MiniProgress(durationMs: Long, positionMs: () -> Long) {
    var position by remember { mutableLongStateOf(positionMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            position = positionMs()
            delay(500)
        }
    }
    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(3.dp).background(ListColors.info.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ListColors.playing))
    }
}

/** The banner shown while the list is pulled down or rescanning. */
@Composable
private fun BoxScope.RefreshBanner(fraction: Float, refreshing: Boolean, height: Dp) {
    val shown = fraction.coerceIn(0f, 1f)
    if (shown <= 0f) return
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(height * shown)
            .background(ListColors.playing.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(
                when {
                    refreshing -> R.string.refresh_running
                    fraction >= 1f -> R.string.refresh_release
                    else -> R.string.refresh_pull
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = ListColors.title.copy(alpha = 0.8f * shown),
            maxLines = 1,
        )
    }
}

/**
 * A list row that slides left under the finger to show a delete action behind
 * it. It snaps open past half the action's width (or on a quick swipe) and
 * closes again otherwise, or once the action is used.
 */
@Composable
private fun SwipeToReveal(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val actionWidth = 96.dp
    val openOffset = -with(LocalDensity.current) { actionWidth.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Drawn only while the row is pulled aside, and only as wide as the gap:
        // a closed row hides nothing behind it, so no red can show at its
        // edges (when the list is pulled down to refresh, for instance).
        if (offset < 0f) Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(with(LocalDensity.current) { (-offset).toDp() })
                .background(Catppuccin.Red)
                .clickable {
                    scope.launch { animate(offset, 0f) { v, _ -> offset = v } }
                    onDelete()
                },
            contentAlignment = Alignment.Center,
        ) {
            // The icon and the label each appear only once the gap is wide
            // enough to hold them, so neither gets cut while the row slides.
            val revealed = with(LocalDensity.current) { (-offset).toDp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (revealed >= 40.dp) Icon(Icons.Default.Delete, null, tint = Catppuccin.Crust)
                if (revealed >= actionWidth - 8.dp) {
                    Text(
                        stringResource(R.string.delete),
                        color = Catppuccin.Crust,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(openOffset, 0f) },
                    onDragStopped = { velocity ->
                        val open = when {
                            velocity < -600f -> true
                            velocity > 600f -> false
                            else -> offset < openOffset / 2
                        }
                        animate(offset, if (open) openOffset else 0f) { v, _ -> offset = v }
                    },
                ),
        ) {
            content()
        }
    }
}
