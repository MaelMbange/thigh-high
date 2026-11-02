package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.Repeat
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.playback.AudioTap
import kotlin.math.max
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
    onSeek: (Long) -> Unit,
) {
    val palette = cover.palette
    var showCover by rememberSaveable { mutableStateOf(true) }
    val background = MaterialTheme.colorScheme.background
    // How far the screen is pulled down by the finger. Dragging the top of the
    // screen moves it with the finger; letting go with a downward flick (or
    // past a third of the screen) minimizes, anything else springs back.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    val pullDown = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> dragOffset = (dragOffset + delta).coerceAtLeast(0f) },
        onDragStopped = { velocity ->
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
    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height.toFloat() }
            .graphicsLayer { translationY = dragOffset }
            // Swallow every touch that no control takes, so taps never reach the
            // library and tabs drawn underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .background(Brush.verticalGradient(listOf(lerp(background, palette.primary, 0.35f), background)))
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().then(pullDown), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.minimize), Modifier.size(32.dp))
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { showCover = !showCover }) {
                if (showCover) Icon(Icons.Default.GraphicEq, stringResource(R.string.show_scope))
                else Icon(Icons.Default.Image, stringResource(R.string.show_cover))
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).then(pullDown),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(showCover, label = "visual") { coverShown ->
                if (coverShown) {
                    CoverArt(cover, Modifier.fillMaxWidth().aspectRatio(1f))
                } else {
                    Oscilloscope(palette, Modifier.fillMaxWidth().aspectRatio(1f))
                }
            }
        }

        Text(
            track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextColors.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.titleMedium,
            color = TextColors.artist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        SeekBar(playback, palette, positionMs, onSeek)

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCycleRepeat) {
                when (playback.repeat) {
                    Repeat.Off -> Icon(Icons.Default.Repeat, stringResource(R.string.repeat_off))
                    Repeat.All -> Icon(Icons.Default.RepeatOn, stringResource(R.string.repeat_all), tint = Catppuccin.Green)
                    Repeat.One -> Icon(Icons.Default.RepeatOneOn, stringResource(R.string.repeat_one), tint = Catppuccin.Green)
                }
            }
            IconButton(onClick = onPrevious, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.tertiary, contentColor = Color.Black),
            ) {
                if (playback.isPlaying) Icon(Icons.Default.Pause, stringResource(R.string.pause), Modifier.size(40.dp))
                else Icon(Icons.Default.PlayArrow, stringResource(R.string.play), Modifier.size(40.dp))
            }
            IconButton(onClick = onNext, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, stringResource(R.string.stop))
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
