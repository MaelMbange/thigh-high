package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
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
    var showCover by rememberSaveable { mutableStateOf(false) }
    val background = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxSize()
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

        Box(
            Modifier.fillMaxWidth().weight(1f),
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.titleMedium,
            color = palette.primary,
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
                    Repeat.All -> Icon(Icons.Default.RepeatOn, stringResource(R.string.repeat_all), tint = palette.primary)
                    Repeat.One -> Icon(Icons.Default.RepeatOneOn, stringResource(R.string.repeat_one), tint = palette.primary)
                }
            }
            IconButton(onClick = onPrevious, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.primary, contentColor = Color.Black),
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

@Composable
private fun SeekBar(playback: PlaybackState, palette: CoverPalette, positionMs: () -> Long, onSeek: (Long) -> Unit) {
    var position by remember { mutableLongStateOf(positionMs()) }
    // While the finger is on the slider, show where it is rather than the player.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            position = positionMs()
            delay(250)
        }
    }
    val duration = playback.durationMs
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 16.dp)) {
        Slider(
            value = if (dragging) dragValue else fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                position = (dragValue * duration).toLong()
                dragging = false
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(thumbColor = palette.primary, activeTrackColor = palette.primary),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (dragging) (dragValue * duration).toLong() else position
            Text(formatMs(shown), style = MaterialTheme.typography.bodySmall)
            Text(formatMs(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
