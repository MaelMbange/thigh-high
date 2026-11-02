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
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    /** Always the previous track: a swipe never just restarts this one. */
    onSkipPrevious: () -> Unit = onPrevious,
    upNext: List<QueueEntry> = emptyList(),
    onPlayQueued: (Int) -> Unit = {},
    onRemoveQueued: (Int) -> Unit = {},
) {
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    if (queueOpen) {
        QueueSheet(upNext, onPlay = onPlayQueued, onRemove = onRemoveQueued, onDismiss = { queueOpen = false })
    }
    val palette = cover.palette
    var showCover by rememberSaveable { mutableStateOf(true) }
    val background = MaterialTheme.colorScheme.background
    // How far the screen is pulled down by the finger. A vertical drag anywhere
    // moves it with the finger; letting go with a downward flick (or past a
    // third of the screen) minimizes, anything else springs back. The drag is
    // read on the outer box, which never moves: read on the moving screen, each
    // step would cancel the next and the screen would barely follow.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    // Only a drag that starts on the cover or above it moves the screen; below
    // it (title, seek bar, buttons) nothing happens. Both in window pixels.
    var screenTop by remember { mutableFloatStateOf(0f) }
    var dragZoneBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var dragAllowed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Changing track slides the picture: it follows the finger (or goes on
    // its own after a button), leaves on one side, and the next track's
    // picture comes in from the other. With nothing before the first track or
    // after the last, the picture only gives a little and springs back.
    val swipe = remember { Animatable(0f) }
    var visualWidth by remember { mutableFloatStateOf(1f) }
    // -1: leaving to the left (next), +1: to the right (previous), 0: none.
    var leaving by remember { mutableIntStateOf(0) }
    // The direction of the last change, for the title; 0 fades it.
    var lastDirection by remember { mutableIntStateOf(0) }
    // The cover drawn: it only changes once the old one has left.
    var shownCover by remember { mutableStateOf(cover) }
    val currentPlayback by rememberUpdatedState(playback)
    fun skip(direction: Int) {
        if (leaving != 0) return
        leaving = direction
        lastDirection = direction
        if (direction < 0) onNext() else onSkipPrevious()
        scope.launch {
            swipe.animateTo(direction * visualWidth, tween(200, easing = FastOutLinearInEasing))
            // The next cover normally arrives within a frame or two; if the
            // track did not change after all, the picture comes back.
            delay(1_500)
            if (leaving == direction) {
                leaving = 0
                swipe.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    LaunchedEffect(cover) {
        if (cover.trackId == shownCover.trackId || leaving == 0) {
            shownCover = cover
            return@LaunchedEffect
        }
        val direction = leaving
        // Wait for the old picture to be off screen, then bring the new one in.
        while (leaving == direction && (swipe.isRunning || abs(swipe.value) < visualWidth * 0.99f)) withFrameNanos { }
        shownCover = cover
        if (leaving != direction) return@LaunchedEffect // gave up meanwhile
        leaving = 0
        swipe.snapTo(-direction * visualWidth)
        swipe.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    }
    val pullDown = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            if (dragAllowed) dragOffset = (dragOffset + delta).coerceAtLeast(0f)
        },
        onDragStopped = { velocity ->
            if (!dragAllowed) return@draggable
            val dismiss = when {
                velocity > 800f -> true
                velocity < -300f -> false
                else -> dragOffset > screenHeight / 3
            }
            if (dismiss) {
                onMinimize()
            } else {
                animate(dragOffset, 0f) { value, _ -> dragOffset = value }
            }
        },
    )
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
            .then(pullDown)
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
            IconButton(onClick = onToggleShuffle) {
                if (playback.shuffle) Icon(Icons.Default.ShuffleOn, stringResource(R.string.shuffle_on), tint = palette.primary)
                else Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle_off))
            }
            IconButton(onClick = { queueOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue))
            }
            IconButton(onClick = { showCover = !showCover }) {
                if (showCover) Icon(Icons.Default.GraphicEq, stringResource(R.string.show_scope))
                else Icon(Icons.Default.Image, stringResource(R.string.show_cover))
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { visualWidth = it.width.toFloat() }
                // Swipe left for the next track, right for the previous one.
                .pointerInput(Unit) {
                    val velocity = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { velocity.resetTracking() },
                        onDragEnd = {
                            val v = velocity.calculateVelocity().x
                            val offset = swipe.value
                            val threshold = visualWidth / 4
                            val p = currentPlayback
                            when {
                                p.hasNext && offset < 0 && (offset < -threshold || v < -1_000f) -> skip(-1)
                                p.hasPrevious && offset > 0 && (offset > threshold || v > 1_000f) -> skip(1)
                                else -> scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.7f)) }
                        },
                    ) { change, dx ->
                        change.consume()
                        if (leaving != 0) return@detectHorizontalDragGestures
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val p = currentPlayback
                        val next = swipe.value + dx
                        val blocked = (next > 0 && !p.hasPrevious) || (next < 0 && !p.hasNext)
                        // At either end the picture resists, a tenth of the way at most.
                        val target = if (blocked) {
                            (swipe.value + dx * 0.25f).coerceIn(-visualWidth * 0.1f, visualWidth * 0.1f)
                        } else {
                            next
                        }
                        scope.launch { swipe.snapTo(target) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
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
                        translationX = swipe.value
                        val f = (abs(swipe.value) / visualWidth).coerceIn(0f, 1f)
                        alpha = 1f - f * 0.6f
                        // A slight tilt back as it moves away, like a card.
                        scaleX = 1f - f * 0.08f
                        scaleY = 1f - f * 0.08f
                    },
                label = "visual",
            ) { coverShown ->
                if (coverShown) {
                    CoverArt(shownCover, Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover"))
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

        // How hard the bass hits right now, 0 to 1; the buttons swell with it.
        val bass = rememberBassLevel()

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCycleRepeat, Modifier.pulse(bass, 0.5f)) {
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
                Modifier.size(56.dp).pulse(bass, 1.5f),
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
            IconButton(onClick = { if (playback.hasNext) skip(-1) }, Modifier.size(56.dp).pulse(bass, 1.5f), enabled = playback.hasNext) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
            }
            IconButton(onClick = onStop, Modifier.pulse(bass, 0.5f)) {
                Icon(Icons.Default.Stop, stringResource(R.string.stop))
            }
        }
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

/**
 * Grows the control with the bass: [weight] 3 for play, 1.5 for its
 * neighbours, 0.5 for the outer buttons. Read in the draw layer, so the beat
 * never recomposes the screen.
 */
private fun Modifier.pulse(bass: androidx.compose.runtime.State<Float>, weight: Float) = graphicsLayer {
    val scale = 1f + PULSE_PER_UNIT * weight * bass.value
    scaleX = scale
    scaleY = scale
}

/**
 * The bass level of what is playing, 0 to 1: the sound is low-passed around
 * 150 Hz, its loudness compared with the recent peak so quiet and loud tracks
 * both move, rising at once and falling back smoothly.
 */
@Composable
private fun rememberBassLevel(): androidx.compose.runtime.State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
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
            level.floatValue = smoothed
        }
    }
    return level
}
