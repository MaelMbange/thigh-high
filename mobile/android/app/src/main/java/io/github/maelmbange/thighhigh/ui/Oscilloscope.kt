package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.playback.AudioTap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The waveform being heard, drawn like the terminal app's scope: the line is
 * plotted on a grid of small dots (the terminal uses braille characters,
 * 2 × 4 dots each), going from the cover's second color near silence through
 * its main color to the third at the peaks. Quiet tracks are scaled up to stay visible.
 */
/**
 * Three clear steps instead of a muddy blend: the second color near silence,
 * the main color in between, the third at the peaks.
 */
private fun traceColor(palette: CoverPalette, loudness: Float): androidx.compose.ui.graphics.Color {
    val t = sqrt(loudness.coerceIn(0f, 1f)) // lift the quiet parts so all three colors show
    return if (t < 0.5f) lerp(palette.secondary, palette.primary, t * 2f)
    else lerp(palette.primary, palette.tertiary, (t - 0.5f) * 2f)
}

/** Samples on screen (~8 ms at 44.1 kHz): few enough that each wave is wide. */
private const val SHOWN = 352

/** Points averaged on each side of a sample. */
private const val SMOOTH = 3

@Composable
fun Oscilloscope(palette: CoverPalette, modifier: Modifier = Modifier) {
    val raw = remember { FloatArray(AudioTap.WINDOW) }
    val samples = remember { FloatArray(SHOWN) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw on every frame
        AudioTap.waveform(raw)
        // Calmer than the raw signal: the most recent third of the window
        // (wider waves on screen), each point averaged with its neighbours
        // to drop the high-pitched jitter that filled the screen with spikes.
        val offset = raw.size - SHOWN
        for (i in 0 until SHOWN) {
            var sum = 0f
            for (j in -SMOOTH..SMOOTH) sum += raw[(offset + i + j).coerceIn(0, raw.size - 1)]
            samples[i] = sum / (2 * SMOOTH + 1)
        }
        val peak = max(samples.maxOf { abs(it) }, 0.25f)

        // Dot grid: braille cells are about twice as tall as wide, so dots
        // sit closer together across than down; pairs of columns form a cell.
        val dotX = 3.dp.toPx()
        val dotY = 3.6.dp.toPx()
        val cellGap = 1.2.dp.toPx()
        val cols = (size.width / (dotX + cellGap / 2)).toInt()
        val rows = (size.height / dotY).toInt()
        if (cols < 2 || rows < 2) return@Canvas
        val lit = BooleanArray(cols * rows)
        val loud = FloatArray(cols * rows)

        fun row(s: Float) = ((1f - (s / peak * 0.9f + 1f) / 2f) * (rows - 1)).roundToInt().coerceIn(0, rows - 1)
        fun col(i: Int) = (i.toFloat() / (samples.size - 1) * (cols - 1)).roundToInt()

        // Rasterize each segment between consecutive samples, as the terminal canvas does.
        for (i in 0 until samples.size - 1) {
            val loudness = (max(abs(samples[i]), abs(samples[i + 1])) / peak).coerceIn(0f, 1f)
            var x0 = col(i)
            var y0 = row(samples[i])
            val x1 = col(i + 1)
            val y1 = row(samples[i + 1])
            val dx = abs(x1 - x0)
            val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                val k = y0 * cols + x0
                lit[k] = true
                loud[k] = max(loud[k], loudness)
                if (x0 == x1 && y0 == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x0 += sx }
                if (e2 <= dx) { err += dx; y0 += sy }
            }
        }

        // Thicker trace: every lit dot also lights the one below it, and dots are large.
        for (k in lit.indices.reversed()) {
            if (lit[k] && k + cols < lit.size && !lit[k + cols]) {
                lit[k + cols] = true
                loud[k + cols] = loud[k]
            }
        }
        val radius = 1.35.dp.toPx()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val k = r * cols + c
                if (!lit[k]) continue
                // Each pair of dot columns is one braille cell, with a small gap after it.
                val x = c * dotX + (c / 2) * cellGap + radius
                val y = r * dotY + dotY / 2
                drawCircle(traceColor(palette, loud[k]), radius, Offset(x, y))
            }
        }
    }
}
