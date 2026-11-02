package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.QueueEntry
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Track

/**
 * The play queue, pulled up over the player: what already played (dimmed),
 * the track playing, then what comes next. It follows the finger; dragging
 * its top, or the list once scrolled to the top, takes it back down. Tap a
 * track to jump to it, × to take an upcoming one out.
 *
 * [onDrag] gets the finger's movement (down is positive); [onSettle] its
 * speed when let go.
 */
@Composable
fun QueuePanel(
    shown: () -> Float,
    screenHeight: Float,
    history: List<QueueEntry>,
    current: Track,
    upNext: List<QueueEntry>,
    onDrag: (Float) -> Unit,
    onSettle: (Float) -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val drag by rememberUpdatedState(onDrag)
    val settle by rememberUpdatedState(onSettle)
    val fraction by rememberUpdatedState(shown)
    // The list scrolls first; what it cannot use moves the panel.
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0 && fraction() < 1f) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (fraction() < 1f) {
                    settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val open by remember { derivedStateOf { fraction() > 0f } }
    val listState = rememberLazyListState()
    // Opens on the track playing, the ones already heard just above.
    val currentRow = if (history.isEmpty()) 0 else history.size + 1
    LaunchedEffect(open) { if (open) listState.scrollToItem(currentRow) }
    Box(Modifier.fillMaxSize()) {
        // Dims the player behind; a tap there closes the panel. Only there
        // while the panel is, so the player's own taps get through otherwise.
        if (open) Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fraction() }
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = null, indication = null) { settle(900f) }
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - fraction()) * screenHeight
                    // Nothing drawn at all while closed.
                    alpha = if (fraction() > 0f) 1f else 0f
                }
                .statusBarsPadding()
                .padding(top = 48.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding(),
        ) {
            // The top of the panel: grab it to pull the panel back down.
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { drag(it) },
                        onDragStopped = { settle(it) },
                    )
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextColors.info.copy(alpha = 0.6f))
                )
                Text(
                    stringResource(R.string.queue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColors.title,
                )
                Text(
                    pluralStringResource(R.plurals.track_count, upNext.size, upNext.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextColors.info,
                )
            }

            LazyColumn(Modifier.fillMaxSize().nestedScroll(connection), state = listState) {
                if (history.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.queue_history)) }
                    items(withKeys("h", history), key = { it.first }) { (_, entry) ->
                        QueueRow(entry, Modifier.graphicsLayer { alpha = 0.5f }, onClick = { onPlay(entry.index) })
                    }
                }
                item(key = "now") {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary) },
                        headlineContent = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            ArtistAndGenre(current.artist, current.genre, MaterialTheme.colorScheme.onPrimary)
                        },
                        trailingContent = current.duration?.let { d -> { Text(formatDuration(d)) } },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            headlineColor = MaterialTheme.colorScheme.onPrimary,
                            supportingColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            trailingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                if (upNext.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.queue_empty),
                            Modifier.fillMaxWidth().padding(32.dp),
                            textAlign = TextAlign.Center,
                            color = TextColors.info,
                        )
                    }
                } else {
                    item { SectionLabel(stringResource(R.string.queue)) }
                    items(withKeys("n", upNext), key = { it.first }) { (_, entry) ->
                        // Taking a track out takes two steps, like deleting
                        // one from the library: slide it left, then tap "Retirer".
                        Box(Modifier.animateItem()) {
                            SwipeToReveal(
                                onAction = { onRemove(entry.index) },
                                // The row fades out where it is, open: closing it first made it blink.
                                closeOnAction = false,
                                icon = Icons.Default.RemoveCircleOutline,
                                label = R.string.remove,
                            ) {
                                QueueRow(entry, Modifier, onClick = { onPlay(entry.index) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keys that stay with a track when the queue changes around it: its position
 * in the queue shifts on every removal, which handed the removed row's key
 * (and its open swipe) to the track below it.
 */
private fun withKeys(prefix: String, entries: List<QueueEntry>): List<Pair<String, QueueEntry>> {
    val seen = HashMap<String, Int>()
    return entries.map { e ->
        val id = e.id?.toString() ?: e.title
        val n = seen.merge(id, 1, Int::plus)!!
        "$prefix$id#$n" to e
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = TextColors.info,
    )
}

/** "Artist · genre", the genre in its own color, as in the library list. */
@Composable
private fun ArtistAndGenre(artist: String?, genre: String?, genreColor: androidx.compose.ui.graphics.Color) {
    val name = artist ?: stringResource(R.string.unknown_artist)
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(name)
            if (genre != null) {
                append(" · ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = genreColor)) { append(genre) }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QueueRow(entry: QueueEntry, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { ArtistAndGenre(entry.artist, entry.genre, TextColors.genre) },
        trailingContent = entry.duration?.let { d -> { Text(formatDuration(d)) } },
        colors = ListItemDefaults.colors(
            // Opaque, so the red action stays hidden until the row slides.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            headlineColor = TextColors.title,
            supportingColor = TextColors.artist,
        ),
    )
}
