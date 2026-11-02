package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.Cover
import io.github.maelmbange.thighhigh.PlaybackState
import io.github.maelmbange.thighhigh.QueueEntry
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.Repeat
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.playback.AudioTap
import kotlin.math.abs
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.platform.LocalDensity
import io.github.maelmbange.thighhigh.Neighbors
import kotlinx.coroutines.Job
import kotlin.math.max
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    track: Track,
    playback: PlaybackState,
    cover: Cover,
    positionMs: () -> Long,
    onMinimize: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    /** Always the previous track: a swipe never just restarts this one. */
    onSkipPrevious: () -> Unit = onPrevious,
    neighbors: Neighbors = Neighbors(),
    history: List<QueueEntry> = emptyList(),
    upNext: List<QueueEntry> = emptyList(),
    onPlayQueued: (Int) -> Unit = {},
    onRemoveQueued: (Int) -> Unit = {},
) {
    val palette = cover.palette
    var showCover by rememberSaveable { mutableStateOf(true) }
    val background = MaterialTheme.colorScheme.background
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // How far the screen is pulled down by the finger. A vertical drag moves it
    // with the finger; letting go with a downward flick (or past a third of the
    // screen) minimizes, anything else springs back. The drag is read on the
    // outer box, which never moves: read on the moving screen, each step would
    // cancel the next and the screen would barely follow.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    // Only a drag down that starts on the cover or above it moves the screen;
    // below it (title, seek bar, buttons) nothing happens. Window pixels.
    var screenTop by remember { mutableFloatStateOf(0f) }
    var dragZoneBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var dragAllowed by remember { mutableStateOf(false) }

    // The "À suivre" panel: 0 hidden, 1 fully up. A drag up anywhere on the
    // player pulls it up with the finger.
    var queueShown by remember { mutableFloatStateOf(0f) }
    var queueJob by remember { mutableStateOf<Job?>(null) }
    fun settleQueue(velocity: Float) {
        val open = when {
            velocity < -800f -> true
            velocity > 800f -> false
            else -> queueShown > 0.35f
        }
        queueJob?.cancel()
        queueJob = scope.launch {
            // Soft and unhurried, carrying on at the finger's speed when it lets go.
            animate(
                queueShown,
                if (open) 1f else 0f,
                initialVelocity = -velocity / screenHeight,
                animationSpec = spring(dampingRatio = 1f, stiffness = QUEUE_STIFFNESS),
            ) { v, _ -> queueShown = v }
        }
    }
    BackHandler(enabled = queueShown > 0f) { settleQueue(OPEN_VELOCITY) }

    // What the current vertical drag does, decided by its first movement.
    var verticalMode by remember { mutableIntStateOf(MODE_NONE) }
    val vertical = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            if (verticalMode == MODE_NONE) {
                verticalMode = when {
                    queueShown > 0f || delta < 0f -> MODE_QUEUE
                    dragAllowed -> MODE_DISMISS
                    else -> MODE_IGNORED
                }
                if (verticalMode == MODE_QUEUE) queueJob?.cancel()
            }
            when (verticalMode) {
                MODE_QUEUE -> queueShown = (queueShown - delta / screenHeight).coerceIn(0f, 1f)
                MODE_DISMISS -> dragOffset = (dragOffset + delta).coerceAtLeast(0f)
            }
        },
        onDragStarted = { verticalMode = MODE_NONE },
        onDragStopped = { velocity ->
            when (verticalMode) {
                MODE_QUEUE -> settleQueue(velocity)
                MODE_DISMISS -> {
                    val dismiss = when {
                        velocity > 800f -> true
                        velocity < -300f -> false
                        else -> dragOffset > screenHeight / 3
                    }
                    if (dismiss) onMinimize() else animate(dragOffset, 0f) { value, _ -> dragOffset = value }
                }
            }
            verticalMode = MODE_NONE
        },
    )

    // Changing track moves a strip of three pictures, previous / current /
    // next, loaded ahead: the neighbour is already there under the finger, so
    // the change never waits for a cover. With nothing before the first track
    // or after the last, the strip only gives a little and springs back.
    var visualWidth by remember { mutableFloatStateOf(1f) }
    val gap = with(density) { 24.dp.toPx() }
    var swipe by remember { mutableFloatStateOf(0f) }
    var swipeJob by remember { mutableStateOf<Job?>(null) }
    // The direction of the last change, for the title; 0 fades it.
    var lastDirection by remember { mutableIntStateOf(0) }
    // The pictures drawn. They are held still while the strip moves, and
    // swapped in one frame when it lands.
    var shownCover by remember { mutableStateOf(cover) }
    var shownNeighbors by remember { mutableStateOf(neighbors) }
    var moving by remember { mutableStateOf(false) }
    // A cover change after a swipe is instant; one from the music going on by
    // itself crossfades.
    var instantSwap by remember { mutableStateOf(false) }
    val currentPlayback by rememberUpdatedState(playback)
    val next by rememberUpdatedState(onNext)
    val skipPrevious by rememberUpdatedState(onSkipPrevious)

    LaunchedEffect(cover) {
        if (cover.trackId != shownCover.trackId) instantSwap = false
        shownCover = cover
    }
    LaunchedEffect(neighbors, moving) { if (!moving) shownNeighbors = neighbors }

    fun springBack() {
        swipeJob?.cancel()
        swipeJob = scope.launch {
            animate(swipe, 0f, animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> swipe = v }
            moving = false
        }
    }

    /** -1: on to the next track (the strip goes left), +1: back to the previous one. */
    fun skip(direction: Int, velocity: Float = 0f) {
        swipeJob?.cancel()
        moving = true
        lastDirection = direction
        val page = visualWidth + gap
        swipeJob = scope.launch {
            animate(
                swipe,
                direction * page,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            ) { v, _ -> swipe = v }
            // Landed on the neighbour: it becomes the current picture where it
            // stands, and the strip is back at rest, in the same frame.
            val target = if (direction < 0) shownNeighbors.next else shownNeighbors.previous
            instantSwap = true
            if (target != null) shownCover = target
            swipe = 0f
            if (direction < 0) next() else skipPrevious()
            moving = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height.toFloat() }
            .onGloballyPositioned { screenTop = it.positionInWindow().y }
            // Note where each touch starts, before any gesture sees it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                        dragAllowed = screenTop + down.position.y <= dragZoneBottom
                    }
                }
            }
            // The drag covers the whole screen, which also keeps taps from
            // reaching the library and tabs drawn underneath. Nothing here may
            // consume touches: gestures still under their touch slop (a slow
            // drag) give up as soon as anything else consumes the movement.
            .then(vertical)
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragOffset }
            .background(Brush.verticalGradient(listOf(lerp(background, palette.primary, 0.35f), background)))
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.minimize), Modifier.size(32.dp))
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { showCover = !showCover }) {
                if (showCover) Icon(Icons.Default.GraphicEq, stringResource(R.string.show_scope))
                else Icon(Icons.Default.Image, stringResource(R.string.show_cover))
            }
        }

        // How hard the bass hits right now, 0 to 1; the buttons and the cover swell with it.
        val bass = rememberBassLevel()

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { visualWidth = it.width.toFloat() }
                // Swipe left for the next track, right for the previous one.
                .pointerInput(Unit) {
                    val velocity = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            swipeJob?.cancel()
                            moving = true
                            velocity.resetTracking()
                        },
                        onDragEnd = {
                            val v = velocity.calculateVelocity().x
                            val threshold = visualWidth / 4
                            val p = currentPlayback
                            when {
                                p.hasNext && swipe < 0 && (swipe < -threshold || v < -1_000f) -> skip(-1, v)
                                p.hasPrevious && swipe > 0 && (swipe > threshold || v > 1_000f) -> skip(1, v)
                                else -> springBack()
                            }
                        },
                        onDragCancel = { springBack() },
                    ) { change, dx ->
                        change.consume()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val p = currentPlayback
                        val moved = swipe + dx
                        val blocked = (moved > 0 && !p.hasPrevious) || (moved < 0 && !p.hasNext)
                        // At either end the picture resists, a tenth of the way at most.
                        swipe = if (blocked) {
                            (swipe + dx * 0.25f).coerceIn(-visualWidth * 0.1f, visualWidth * 0.1f)
                        } else {
                            moved
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val page = visualWidth + gap
            // The neighbours, drawn only while the strip moves.
            if (swipe != 0f) {
                listOf(-1 to shownNeighbors.previous, 1 to shownNeighbors.next).forEach { (side, neighbour) ->
                    if (neighbour != null) {
                        CoverArt(
                            neighbour,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    translationX = swipe + side * page
                                    val f = 1f - (abs(translationX) / page).coerceIn(0f, 1f)
                                    alpha = 0.4f + 0.6f * f
                                    scaleX = 0.92f + 0.08f * f
                                    scaleY = 0.92f + 0.08f * f
                                },
                        )
                    }
                }
            }
            Crossfade(
                showCover,
                Modifier
                    // The pull-down zone ends at the bottom of the cover itself,
                    // not of the space around it. Measured at rest: the pull-down
                    // shift, if any, is taken out.
                    .onGloballyPositioned {
                        dragZoneBottom = it.positionInWindow().y + it.size.height - dragOffset
                    }
                    .graphicsLayer {
                        translationX = swipe
                        val f = (abs(swipe) / page).coerceIn(0f, 1f)
                        alpha = 1f - f * 0.6f
                        // The cover swells gently with the bass, like the play button.
                        val beat = if (showCover) 1f + PULSE_PER_UNIT * COVER_PULSE * bass.at(COVER_DELAY_MS) else 1f
                        scaleX = (1f - f * 0.08f) * beat
                        scaleY = (1f - f * 0.08f) * beat
                    },
                label = "visual",
            ) { coverShown ->
                if (coverShown) {
                    Crossfade(
                        shownCover,
                        animationSpec = if (instantSwap) snap() else tween(300),
                        label = "cover",
                    ) { c ->
                        CoverArt(c, Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover"))
                    }
                } else {
                    Oscilloscope(palette, Modifier.fillMaxWidth().aspectRatio(1f))
                }
            }
        }

        // The title and artist slide the same way as the picture.
        AnimatedContent(
            track,
            contentKey = { it.id },
            transitionSpec = {
                val d = lastDirection
                if (d == 0) {
                    fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                } else {
                    (slideInHorizontally(tween(320)) { -d * it / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(200)) { d * it / 3 } + fadeOut(tween(160)))
                }
            },
            label = "title",
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextColors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shown.artist ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextColors.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        SeekBar(playback, palette, positionMs, onSeek)

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCycleRepeat, Modifier.pulse(bass, 0.5f, OUTER_DELAY_MS)) {
                when (playback.repeat) {
                    Repeat.Off -> Icon(Icons.Default.Repeat, stringResource(R.string.repeat_off))
                    Repeat.All -> Icon(Icons.Default.RepeatOn, stringResource(R.string.repeat_all), tint = palette.primary)
                    Repeat.One -> Icon(Icons.Default.RepeatOneOn, stringResource(R.string.repeat_one), tint = palette.primary)
                }
            }
            IconButton(
                onClick = {
                    // In its first seconds, the previous track; later, back to the start.
                    if (playback.hasPrevious && positionMs() < 3_000) skip(1) else onPrevious()
                },
                Modifier.size(56.dp).pulse(bass, 1.5f, NEIGHBOR_DELAY_MS),
            ) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp).pulse(bass, 3f),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.tertiary, contentColor = Color.Black),
            ) {
                if (playback.isPlaying) Icon(Icons.Default.Pause, stringResource(R.string.pause), Modifier.size(40.dp))
                else Icon(Icons.Default.PlayArrow, stringResource(R.string.play), Modifier.size(40.dp))
            }
            IconButton(onClick = { if (playback.hasNext) skip(-1) }, Modifier.size(56.dp).pulse(bass, 1.5f, NEIGHBOR_DELAY_MS), enabled = playback.hasNext) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
            }
            IconButton(onClick = onToggleShuffle, Modifier.pulse(bass, 0.5f, OUTER_DELAY_MS)) {
                if (playback.shuffle) Icon(Icons.Default.ShuffleOn, stringResource(R.string.shuffle_on), tint = palette.primary)
                else Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle_off))
            }
        }

        // A faded strip at the bottom, like the list's refresh banner: tap it
        // or drag up to see what comes next.
        UpNextBanner(
            Modifier.graphicsLayer { alpha = 1f - queueShown },
            onClick = { settleQueue(-OPEN_VELOCITY) },
        )
        }

        // Always composed, off screen while closed, so opening never waits
        // for the list to be built.
        run {
            QueuePanel(
                shown = { queueShown },
                screenHeight = screenHeight,
                history = history,
                current = track,
                upNext = upNext,
                onDrag = { delta ->
                    queueJob?.cancel()
                    queueShown = (queueShown - delta / screenHeight).coerceIn(0f, 1f)
                },
                onSettle = ::settleQueue,
                onPlay = onPlayQueued,
                onRemove = onRemoveQueued,
            )
        }
    }
}

/** How stiff the queue panel's spring is: low, so it rises and falls smoothly. */
private const val QUEUE_STIFFNESS = 140f

/** The speed of a tap on the banner (or Back), in pixels per second: just enough to cross the threshold. */
private const val OPEN_VELOCITY = 900f

private const val MODE_NONE = 0
private const val MODE_DISMISS = 1
private const val MODE_QUEUE = 2
private const val MODE_IGNORED = 3

@Composable
private fun UpNextBanner(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(TextColors.playing.copy(alpha = 0.04f), TextColors.playing.copy(alpha = 0.16f))
                )
            )
            .clickable(onClick = onClick)
            .height(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp), tint = TextColors.title.copy(alpha = 0.8f))
            Text(
                stringResource(R.string.queue),
                Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = TextColors.title.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CoverArt(cover: Cover, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    if (cover.bitmap != null) {
        Image(
            cover.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp), tint = cover.palette.primary)
        }
    }
}

/**
 * The seek bar as a row of bars dancing with the music: played bars in the
 * cover's second to third color as they rise, the rest dimmed. The position
 * dot stays on the center line. Tap or drag anywhere to seek.
 */
@Composable
private fun SeekBar(playback: PlaybackState, palette: CoverPalette, positionMs: () -> Long, onSeek: (Long) -> Unit) {
    var position by remember { mutableLongStateOf(positionMs()) }
    // While the finger is on the bar, show where it is rather than the player.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = positionMs()
            delay(250)
        }
    }
    val duration = playback.durationMs
    val fraction = dragFraction
        ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val levels = rememberBarLevels()
    val currentDuration by rememberUpdatedState(duration)
    val seek by rememberUpdatedState(onSeek)

    Column(Modifier.padding(top = 16.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (currentDuration > 0) {
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            position = (f * currentDuration).toLong()
                            seek(position)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { f ->
                                position = (f * currentDuration).toLong()
                                seek(position)
                            }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
        ) {
            val bars = levels.value
            val n = bars.size
            val slot = size.width / n
            val barWidth = slot * 0.62f
            val mid = size.height / 2
            val minHeight = 3.dp.toPx()
            val maxHeight = size.height - 14.dp.toPx()
            fun height(i: Int) = minHeight + bars[i] * (maxHeight - minHeight)
            val playedX = size.width * fraction
            for (i in 0 until n) {
                val x = i * slot + (slot - barWidth) / 2
                val h = height(i)
                val color = if (x + barWidth / 2 <= playedX) lerp(palette.secondary, palette.tertiary, bars[i])
                else palette.secondary.copy(alpha = 0.25f)
                drawRoundRect(
                    color,
                    topLeft = Offset(x, mid - h / 2),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
            // The position dot stays on the center line while the bars move.
            val radius = 6.dp.toPx()
            val dot = Offset(playedX.coerceIn(radius, size.width - radius), mid)
            drawCircle(Color.White, radius, dot)
            drawCircle(palette.tertiary, radius * 0.6f, dot)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragFraction?.let { (it * duration).toLong() } ?: position
            Text(formatMs(shown), style = MaterialTheme.typography.bodySmall, color = TextColors.info)
            Text(formatMs(duration), style = MaterialTheme.typography.bodySmall, color = TextColors.info)
        }
    }
}

/** Bars across the seek bar. */
private const val BAR_COUNT = 48

/**
 * One level per bar, 0 to 1, updated every frame from the audio being heard:
 * each bar follows the loudness of its slice of the current waveform, so the
 * row ripples with the music, jumps on kicks and settles when paused.
 */
@Composable
private fun rememberBarLevels(): androidx.compose.runtime.State<FloatArray> {
    val levels = remember { mutableStateOf(FloatArray(BAR_COUNT)) }
    LaunchedEffect(Unit) {
        val window = FloatArray(2048)
        val smoothed = FloatArray(BAR_COUNT)
        while (true) withFrameNanos {
            val playing = AudioTap.waveform(window)
            val chunk = window.size / BAR_COUNT
            var loudest = 0f
            val rms = FloatArray(BAR_COUNT) { b ->
                var e = 0f
                for (j in b * chunk until (b + 1) * chunk) e += window[j] * window[j]
                sqrt(e / chunk).also { loudest = max(loudest, it) }
            }
            // Relative to the loudest slice, so quiet tracks still move; scaled by
            // the overall level so a quiet passage stays lower than a loud one.
            val overall = (loudest / 0.3f).coerceIn(0f, 1f)
            for (b in 0 until BAR_COUNT) {
                val target = if (!playing || loudest == 0f) 0f
                else (rms[b] / loudest) * (0.35f + 0.65f * overall)
                // Rise at once, fall back smoothly.
                smoothed[b] = if (target > smoothed[b]) target else smoothed[b] * 0.86f + target * 0.14f
            }
            levels.value = smoothed.copyOf()
        }
    }
    return levels
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Each weight unit adds this much size at the strongest bass (play = 3 units). */
private const val PULSE_PER_UNIT = 0.04f

/** The cover's pulse weight: big as it is, it only breathes (about 2.5 %). */
private const val COVER_PULSE = 0.6f

/*
 * Like a stone dropped in water: the play button takes the beat first, and it
 * spreads from there, a little later and a little softer the further out.
 */
private const val NEIGHBOR_DELAY_MS = 70L
private const val COVER_DELAY_MS = 110L
private const val OUTER_DELAY_MS = 140L

/**
 * The recent bass levels, so a control can follow the beat as it was a few
 * milliseconds ago. Reading [at] in a draw layer redraws it every frame.
 */
internal class BassTrail {
    private val frame = androidx.compose.runtime.mutableLongStateOf(0L)
    // About half a second of frames, even at 120 Hz.
    private val times = LongArray(64)
    private val values = FloatArray(64)
    private var count = 0
    private var head = -1

    fun push(timeNs: Long, value: Float) {
        head = (head + 1) % times.size
        times[head] = timeNs
        values[head] = value
        if (count < times.size) count++
        frame.longValue = timeNs
    }

    /** The level [delayMs] ago, interpolated between frames. */
    fun at(delayMs: Long): Float {
        val now = frame.longValue
        if (count == 0) return 0f
        val target = now - delayMs * 1_000_000L
        var i = head
        repeat(count - 1) {
            val prev = (i - 1 + times.size) % times.size
            if (times[prev] <= target) {
                val span = (times[i] - times[prev]).coerceAtLeast(1L)
                val t = ((target - times[prev]).toFloat() / span).coerceIn(0f, 1f)
                return values[prev] + (values[i] - values[prev]) * t
            }
            i = prev
        }
        return values[i]
    }
}

/**
 * Grows the control with the bass: [weight] 3 for play, 1.5 for its
 * neighbours, 0.5 for the outer buttons. Read in the draw layer, so the beat
 * never recomposes the screen.
 */
private fun Modifier.pulse(bass: BassTrail, weight: Float, delayMs: Long = 0L) = graphicsLayer {
    val scale = 1f + PULSE_PER_UNIT * weight * bass.at(delayMs)
    scaleX = scale
    scaleY = scale
}

/**
 * The bass level of what is playing, 0 to 1: the sound is low-passed around
 * 150 Hz, its loudness compared with the recent peak so quiet and loud tracks
 * both move, rising at once and falling back smoothly.
 */
@Composable
private fun rememberBassLevel(): BassTrail {
    val level = remember { BassTrail() }
    LaunchedEffect(Unit) {
        val window = FloatArray(1024)
        var peak = 0.05f
        var smoothed = 0f
        // One-pole low-pass at ~150 Hz for 48 kHz audio.
        val alpha = 0.02f
        while (true) withFrameNanos {
            val playing = AudioTap.waveform(window)
            var y = 0f
            var energy = 0f
            for (x in window) {
                y += alpha * (x - y)
                energy += y * y
            }
            val rms = if (playing) sqrt(energy / window.size) else 0f
            peak = max(rms, peak * 0.995f).coerceAtLeast(0.02f)
            val target = (rms / peak).coerceIn(0f, 1f).let { it * it }
            smoothed = if (target > smoothed) target else smoothed * 0.85f + target * 0.15f
            level.push(System.nanoTime(), smoothed)
        }
    }
    return level
}
