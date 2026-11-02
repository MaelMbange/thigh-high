package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.LyricsState
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.lyrics.LyricLine
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import io.github.maelmbange.thighhigh.lyrics.indexAt
import kotlinx.coroutines.delay

/**
 * The playing track's lyrics, where the cover was. Synced lyrics follow the
 * music, the sung line centred and lit in the cover's color; a tap on a line
 * jumps there. The list moves by itself only, so the player's swipes (down to
 * close, up for the queue, sideways to skip) keep working over it.
 */
@Composable
fun LyricsView(
    state: LyricsState,
    palette: CoverPalette,
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fadingEdges().testTag("lyrics"), contentAlignment = Alignment.Center) {
        Crossfade(state, label = "lyrics") { s ->
            when (s) {
                LyricsState.Loading -> Message(stringResource(R.string.lyrics_loading)) {
                    CircularProgressIndicator(color = palette.primary)
                }
                LyricsState.Offline -> Message(stringResource(R.string.lyrics_offline)) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.lyrics_retry), color = palette.primary) }
                }
                is LyricsState.Ready -> when (val l = s.lyrics) {
                    is Lyrics.Synced -> SyncedLyrics(l.lines, palette, positionMs, onSeek)
                    is Lyrics.Plain -> PlainLyrics(l.text, positionMs, durationMs)
                    Lyrics.Instrumental -> Message(stringResource(R.string.lyrics_instrumental))
                    Lyrics.NotFound -> Message(stringResource(R.string.lyrics_not_found))
                }
            }
        }
    }
}

/** Lines sung this soon are lit already: the eye reads a little ahead of the voice. */
private const val LEAD_MS = 250L

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, palette: CoverPalette, positionMs: () -> Long, onSeek: (Long) -> Unit) {
    var current by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines) {
        while (true) {
            current = lines.indexAt(positionMs() + LEAD_MS)
            delay(100)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        listState.animateScrollToItem(current.coerceAtLeast(0))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The sung line sits a little above the middle.
        val top = maxHeight * 0.38f
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = top, bottom = maxHeight - top),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { i, line ->
                val sung = i == current
                val color by animateColorAsState(if (sung) palette.primary else TextColors.title, tween(300), label = "line")
                val alpha by animateFloatAsState(
                    when {
                        sung -> 1f
                        i < current -> 0.35f
                        else -> 0.6f
                    },
                    tween(300),
                    label = "alpha",
                )
                val scale by animateFloatAsState(if (sung) 1.06f else 1f, tween(300), label = "scale")
                Text(
                    line.text.ifBlank { "♪" },
                    color = color,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable { onSeek(line.timeMs) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Unsynced lyrics scroll along at the pace of the track. */
@Composable
private fun PlainLyrics(text: String, positionMs: () -> Long, durationMs: Long) {
    val scroll = rememberScrollState()
    LaunchedEffect(durationMs) {
        while (true) {
            if (durationMs > 0 && scroll.maxValue > 0) {
                val target = (scroll.maxValue * (positionMs().toFloat() / durationMs)).toInt()
                scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue), tween(900))
            } else {
                delay(500)
            }
        }
    }
    Text(
        text,
        color = TextColors.title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(scroll, enabled = false).padding(vertical = 48.dp, horizontal = 8.dp),
    )
}

@Composable
private fun Message(text: String, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        extra()
        Text(text, color = TextColors.info, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}

/** The text fades out at the top and bottom instead of being cut. */
private fun Modifier.fadingEdges() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = size.height * 0.15f
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                edge / size.height to Color.Black,
                1f - edge / size.height to Color.Black,
                1f to Color.Transparent,
                startY = 0f,
                endY = size.height,
            ),
            topLeft = Offset.Zero,
            size = size,
            blendMode = BlendMode.DstIn,
        )
    }
