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
import androidx.compose.material.icons.filled.Close
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

    Box(Modifier.fillMaxSize()) {
        // Dims the player behind; a tap there closes the panel.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fraction() }
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = null, indication = null) { settle(1_000f) }
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (1f - fraction()) * screenHeight }
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

            val listState = rememberLazyListState()
            // Opens on the track playing, the ones already heard just above.
            val currentRow = if (history.isEmpty()) 0 else history.size + 1
            LaunchedEffect(Unit) { listState.scrollToItem(currentRow) }
            LazyColumn(Modifier.fillMaxSize().nestedScroll(connection), state = listState) {
                if (history.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.queue_history)) }
                    items(history, key = { "h${it.index}" }) { entry ->
                        QueueRow(entry, Modifier.graphicsLayer { alpha = 0.5f }, onClick = { onPlay(entry.index) })
                    }
                }
                item(key = "now") {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary) },
                        headlineContent = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Text(current.artist ?: stringResource(R.string.unknown_artist), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            headlineColor = MaterialTheme.colorScheme.onPrimary,
                            supportingColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
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
                    items(upNext, key = { "n${it.index}" }) { entry ->
                        QueueRow(
                            entry,
                            Modifier.animateItem(),
                            onClick = { onPlay(entry.index) },
                            onRemove = { onRemove(entry.index) },
                        )
                    }
                }
            }
        }
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

@Composable
private fun QueueRow(entry: QueueEntry, modifier: Modifier, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(entry.artist ?: stringResource(R.string.unknown_artist), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = onRemove?.let {
            {
                IconButton(onClick = it) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = TextColors.title,
            supportingColor = TextColors.artist,
        ),
    )
}
