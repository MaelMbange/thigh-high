package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.filled.Lyrics
import io.github.maelmbange.thighhigh.LyricsState
import io.github.maelmbange.thighhigh.cast.CastTrack
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.layout.layout
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
import androidx.compose.runtime.key
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import io.github.maelmbange.thighhigh.playback.PulseDetector
import io.github.maelmbange.thighhigh.playback.PulseSource
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
import kotlin.math.exp
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
    /** A track of the library by id: the titles beside the current one, for the swipe. */
    trackOf: (Long) -> Track? = { null },
    history: List<QueueEntry> = emptyList(),
    upNext: List<QueueEntry> = emptyList(),
    onPlayQueued: (Int) -> Unit = {},
    onRemoveQueued: (Int) -> Unit = {},
    lyrics: LyricsState = LyricsState.Loading,
    onLyricsShown: (Boolean) -> Unit = {},
    onRetryLyrics: () -> Unit = {},
    /** What the buttons and the cover swell with. */
    pulseSource: PulseSource = PulseSource.Bass,
    /** Whether a track is in the favorites; the heart in the top bar switches the one shown. */
    isFavorite: (Long) -> Boolean = { false },
    onToggleFavorite: (Track) -> Unit = {},
) {
    // The pictures drawn. They are held still while the strip moves, and
    // swapped in one frame when it lands; the colors follow the picture
    // shown, so they change with it rather than when the new cover is read.
    var shownCover by remember { mutableStateOf(cover) }
    val palette = shownCover.palette
    var showCover by rememberSaveable { mutableStateOf(true) }
    // The lyrics are looked up only while they are on screen.
    androidx.compose.runtime.DisposableEffect(showCover) {
        onLyricsShown(!showCover)
        onDispose { onLyricsShown(false) }
    }
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
    var shownNeighbors by remember { mutableStateOf(neighbors) }
    // The title drawn, swapped with the picture so both change together.
    var shownTrack by remember { mutableStateOf(track) }
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
    // Until the player reaches the track shown (a moment after a swipe), the
    // seek bar and the lyrics show its start and its length from the library,
    // not the end of the one before.
    val settled = playback.currentId == shownTrack.id
    val shownPlayback = if (settled) playback
        else playback.copy(durationMs = shownTrack.duration?.inWholeMilliseconds ?: 0L)
    val settledNow by rememberUpdatedState(settled)
    val readPosition by rememberUpdatedState(positionMs)
    val shownPosition: () -> Long = remember { { if (settledNow) readPosition() else 0L } }
    // When the music goes on by itself, the title waits for the new cover so
    // both crossfade together (or gives up waiting after half a second).
    LaunchedEffect(track.id, shownCover.trackId) {
        if (moving) return@LaunchedEffect
        if (shownCover.trackId != track.id) delay(500)
        shownTrack = track
    }

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
            target?.trackId?.let(trackOf)?.let { shownTrack = it }
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
            // The bottom inset is left to the "À suivre" strip, which runs
            // down to the screen's edge behind the navigation bar.
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.minimize), Modifier.size(32.dp))
            }
            Box(Modifier.weight(1f))
            // Keyed by track: it bounces when tapped, not when the track changes.
            key(shownTrack.id) {
                FavoriteButton(isFavorite(shownTrack.id), palette.primary) { onToggleFavorite(shownTrack) }
            }
            IconButton(onClick = { showCover = !showCover }) {
                if (showCover) Icon(Icons.Default.Lyrics, stringResource(R.string.show_lyrics))
                else Icon(Icons.Default.Image, stringResource(R.string.show_cover))
            }
        }

        // How hard the bass hits right now, 0 to 1; the buttons and the cover swell with it.
        val bass = rememberBassLevel(pulseSource)

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
                    Swap(shownCover, instant = instantSwap, key = { it.trackId to it.bitmap }) { c, m ->
                        CoverArt(c, m.fillMaxWidth().aspectRatio(1f).then(if (c === shownCover) Modifier.testTag("cover") else Modifier))
                    }
                } else {
                    // In place of the oscilloscope (kept in Oscilloscope.kt for later).
                    LyricsView(
                        lyrics,
                        palette,
                        shownPosition,
                        shownPlayback.durationMs,
                        onSeek,
                        onRetryLyrics,
                        Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
            }
        }

        // The title and artist ride the same strip as the picture: they move
        // with the finger, the neighbours' titles come in beside them, and
        // they swap in the very frame the picture does.
        Box(Modifier.fillMaxWidth()) {
            val page = visualWidth + gap
            if (swipe != 0f) {
                listOf(-1 to shownNeighbors.previous, 1 to shownNeighbors.next).forEach { (side, neighbour) ->
                    neighbour?.trackId?.let(trackOf)?.let { t ->
                        TrackTitle(
                            t,
                            Modifier.graphicsLayer {
                                translationX = swipe + side * page
                                alpha = 1f - (abs(translationX) / page).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
            Swap(shownTrack, instant = instantSwap, key = { it.id }) { t, m ->
                TrackTitle(
                    t,
                    m.graphicsLayer {
                        translationX = swipe
                        alpha *= 1f - (abs(swipe) / page).coerceIn(0f, 1f)
                    },
                )
            }
        }

        SeekBar(shownPlayback, palette, shownPosition, onSeek)

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
        Modifier
            // Room between the controls and the strip.
            .padding(top = 20.dp)
            // Edge to edge: out past the screen's side padding.
            .layout { measurable, constraints ->
                val bleed = SCREEN_PADDING.roundToPx()
                val width = constraints.maxWidth + 2 * bleed
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            // The caller's fade applies to the full width, not to the narrower slot.
            .then(modifier)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(TextColors.playing.copy(alpha = 0.06f), TextColors.playing.copy(alpha = 0.18f))
                )
            )
            .clickable(onClick = onClick)
            // Down to the bottom of the screen; the label stays above the navigation bar.
            .navigationBarsPadding()
            .height(44.dp),
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
private fun SeekBar(
    playback: PlaybackState,
    palette: CoverPalette,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    /** False for a broadcast heard live: the bars dance, the finger moves nothing. */
    seekable: Boolean = true,
) {
    var position by remember { mutableLongStateOf(positionMs()) }
    // While the finger is on the bar, show where it is rather than the player.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // Read again at once when the track changes, then every quarter second.
    LaunchedEffect(playback.durationMs) {
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
                // Taller than the bars at rest, so the ones near the dot can rise.
                .height(72.dp)
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
                    detectTapGestures { offset ->
                        if (currentDuration > 0) {
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            position = (f * currentDuration).toLong()
                            seek(position)
                        }
                    }
                }
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
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
            val maxHeight = 34.dp.toPx()
            val ceiling = size.height - 4.dp.toPx()
            val playedX = size.width * fraction
            val sigma = SEEK_REACH_BARS / 2f
            for (i in 0 until n) {
                val x = i * slot + (slot - barWidth) / 2
                // The bars swing harder around the dot, fading out a few bars away.
                val d = (x + barWidth / 2 - playedX) / slot
                val boost = 1f + (SEEK_GAIN - 1f) * exp(-(d * d) / (2f * sigma * sigma))
                val level = bars[i] * boost
                val h = (minHeight + level * (maxHeight - minHeight)).coerceAtMost(ceiling)
                val color = if (x + barWidth / 2 <= playedX) lerp(palette.secondary, palette.tertiary, level.coerceAtMost(1f))
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

/** The player's side margin, which the "À suivre" strip reaches past. */
private val SCREEN_PADDING = 16.dp

/** How much more the bars right under the position dot move (Mael's pick in the preview). */
private const val SEEK_GAIN = 3.25f

/** How many bars on each side of the dot the swell reaches before fading out. */
private const val SEEK_REACH_BARS = 6.5f

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
 * How hard [source] hits in what is playing, 0 to 1, every frame (the
 * instrument picked in the settings; the bass by default).
 */
@Composable
private fun rememberBassLevel(source: PulseSource): BassTrail {
    val level = remember { BassTrail() }
    LaunchedEffect(source) {
        val window = FloatArray(1024)
        val detector = PulseDetector(source)
        while (true) withFrameNanos {
            val playing = AudioTap.waveform(window)
            level.push(System.nanoTime(), detector.level(window, playing, AudioTap.rate))
        }
    }
    return level
}

/** The heart of the top bar: filled when the track is a favorite, with a little bounce when it changes. */
@Composable
private fun FavoriteButton(favorite: Boolean, color: Color, onToggle: () -> Unit) {
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(favorite) {
        if (first) { first = false; return@LaunchedEffect }
        bounce.snapTo(if (favorite) 1.35f else 0.8f)
        bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
    }
    IconButton(onClick = onToggle) {
        Icon(
            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
            Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
            tint = if (favorite) color else LocalContentColor.current,
        )
    }
}

@Composable
private fun TrackTitle(track: Track, modifier: Modifier) = TrackTitle(track.title, track.artist, modifier)

@Composable
private fun TrackTitle(title: String, artist: String?, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextColors.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.titleMedium,
            color = TextColors.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shows [value], crossfading when it changes by itself (the music going on).
 * After a swipe ([instant]) the new value replaces the old one in the same
 * frame: a Crossfade, even with no duration, still drew the old one at full
 * strength for one frame, which flashed it back right after the strip landed.
 * [content] gets the modifier carrying the fade.
 */
@Composable
private fun <T> Swap(value: T, instant: Boolean, key: (T) -> Any? = { it }, content: @Composable (T, Modifier) -> Unit) {
    var current by remember { mutableStateOf(value) }
    var previous by remember { mutableStateOf<T?>(null) }
    // How far the new value has faded in, 0 to 1.
    var progress by remember { mutableFloatStateOf(1f) }
    if (key(value) != key(current)) {
        // Decided while composing, so the very first frame is already right.
        previous = if (instant) null else current
        progress = if (instant) 1f else 0f
    }
    current = value
    LaunchedEffect(key(current)) {
        if (previous != null) {
            animate(progress, 1f, animationSpec = tween(300)) { v, _ -> progress = v }
            previous = null
        }
    }
    Box {
        previous?.let { content(it, Modifier.graphicsLayer { alpha = 1f - progress }) }
        content(current, Modifier.graphicsLayer { alpha = progress })
    }
}

/**
 * A broadcast heard live: the player's screen, cover, lyrics and dancing
 * bars, without its controls (the broadcaster chooses what plays). Below,
 * stop listening or pick another device.
 */
@Composable
internal fun LivePlayerScreen(
    from: String,
    track: CastTrack?,
    playing: Boolean,
    positionMs: () -> Long,
    onStop: () -> Unit,
    onSwitch: () -> Unit,
    pulseSource: PulseSource = PulseSource.Bass,
    /** The broadcaster's radios: when there are some, live and each radio can be picked. */
    radios: List<io.github.maelmbange.thighhigh.cast.CastChannel> = emptyList(),
    channel: Int = io.github.maelmbange.thighhigh.cast.CastNative.LIVE,
    onTune: (Int) -> Unit = {},
) {
    val palette = track?.palette ?: CoverPalette.Default
    val background = MaterialTheme.colorScheme.background
    var showCover by rememberSaveable { mutableStateOf(true) }
    val bass = rememberBassLevel(pulseSource)
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(background, palette.primary, 0.35f), background)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = SCREEN_PADDING),
    ) {
        // No way back from here but the two buttons below: the screen stays
        // until the listening stops or another device is picked.
        BackHandler {}
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Text(
                stringResource(R.string.cast_live_from, from) + if (playing) "" else " · " + stringResource(R.string.cast_paused),
                Modifier.weight(1f).testTag("castLiveFrom"),
                color = palette.primary,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { showCover = !showCover }, enabled = track != null) {
                if (showCover) Icon(Icons.Default.Lyrics, stringResource(R.string.show_lyrics))
                else Icon(Icons.Default.Image, stringResource(R.string.show_cover))
            }
        }
        if (radios.isNotEmpty()) {
            val live = io.github.maelmbange.thighhigh.cast.CastChannel(
                io.github.maelmbange.thighhigh.cast.CastNative.LIVE,
                stringResource(R.string.cast_live),
            )
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().testTag("castChannels"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf(live) + radios, key = { it.id }) { option ->
                    androidx.compose.material3.FilterChip(
                        selected = option.id == channel,
                        onClick = { onTune(option.id) },
                        label = { Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.primary.copy(alpha = 0.35f),
                        ),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (track == null) {
                Text(stringResource(R.string.cast_waiting), color = TextColors.info)
            } else {
                Crossfade(
                    showCover,
                    Modifier.graphicsLayer {
                        val beat = if (showCover) 1f + PULSE_PER_UNIT * COVER_PULSE * bass.at(COVER_DELAY_MS) else 1f
                        scaleX = beat
                        scaleY = beat
                    },
                    label = "liveVisual",
                ) { coverShown ->
                    if (coverShown) {
                        Swap(track, instant = false, key = { it.title to it.cover }) { t, m ->
                            CoverArt(Cover(t.cover, t.palette), m.fillMaxWidth().aspectRatio(1f).testTag("cover"))
                        }
                    } else {
                        LyricsView(
                            LyricsState.Ready(track.lyrics ?: Lyrics.NotFound),
                            palette,
                            positionMs,
                            track.durationMs,
                            {},
                            {},
                            Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                }
            }
        }
        if (track != null) {
            Swap(track, instant = false, key = { it.title to it.artist }) { t, m ->
                TrackTitle(t.title, t.artist.ifEmpty { null }, m)
            }
            SeekBar(
                PlaybackState(isPlaying = playing, durationMs = track.durationMs),
                palette,
                positionMs,
                onSeek = {},
                seekable = false,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().pulse(bass, 1f),
            colors = ButtonDefaults.buttonColors(containerColor = palette.tertiary, contentColor = Color.Black),
        ) {
            Text(stringResource(R.string.cast_stop_listening))
        }
        OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.cast_switch_channel), color = TextColors.title)
        }
    }
}
