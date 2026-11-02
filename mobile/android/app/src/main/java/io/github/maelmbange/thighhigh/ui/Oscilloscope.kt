package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.playback.AudioTap
import kotlin.math.abs
import kotlin.math.max

/**
 * The waveform being heard, redrawn every frame. Like the terminal app's
 * scope, the trace runs from the secondary color near silence to the
 * tertiary at its peaks, and quiet tracks are scaled up to stay visible.
 */
@Composable
fun Oscilloscope(palette: CoverPalette, modifier: Modifier = Modifier) {
    val samples = remember { FloatArray(AudioTap.WINDOW) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw on every frame
        AudioTap.waveform(samples)
        val peak = max(samples.maxOf { abs(it) }, 0.25f)
        val mid = size.height / 2
        val step = size.width / (samples.size - 1)
        fun y(s: Float) = mid - s / peak * 0.9f * mid

        // A soft glow under the trace.
        val path = Path().apply {
            moveTo(0f, y(samples[0]))
            for (i in 1 until samples.size) lineTo(i * step, y(samples[i]))
        }
        drawPath(path, palette.primary.copy(alpha = 0.25f), style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))

        val width = 2.5.dp.toPx()
        for (i in 0 until samples.size - 1) {
            val loudness = max(abs(samples[i]), abs(samples[i + 1])) / peak
            drawLine(
                color = lerp(palette.secondary, palette.tertiary, loudness.coerceIn(0f, 1f)),
                start = Offset(i * step, y(samples[i])),
                end = Offset((i + 1) * step, y(samples[i + 1])),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}
