package io.github.maelmbange.thighhigh.download

import io.github.maelmbange.thighhigh.playback.fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sketch of how a piece of music sounds over time, to recognise the same
 * track in two different files (another upload, another format, another
 * loudness): the energy of 16 frequency bands, frame after frame, each band
 * centred and scaled so only its shape counts.
 *
 * Built from the same 30 s mono excerpt the genre detection decodes.
 */
class Fingerprint private constructor(
    /** [band][frame], each band with mean 0 and deviation 1. */
    private val bands: Array<FloatArray>,
) {
    val frames: Int get() = bands.firstOrNull()?.size ?: 0

    /**
     * How alike two sketches are, from about 0 (unrelated) to 1 (the same
     * sound). The excerpts may start up to [maxShiftFrames] frames apart
     * (two uploads rarely have the same length), so every shift is tried
     * and the best one kept.
     */
    fun similarity(other: Fingerprint, maxShiftFrames: Int = MAX_SHIFT): Float {
        val n = min(frames, other.frames)
        if (n < MIN_FRAMES) return 0f
        var best = -1f
        for (shift in -maxShiftFrames..maxShiftFrames) {
            val start = max(0, -shift)
            val end = min(frames, other.frames - shift)
            val overlap = end - start
            // At least two thirds of the excerpt must overlap.
            if (overlap < n * 2 / 3) continue
            var sum = 0.0
            for (b in bands.indices) {
                val x = bands[b]
                val y = other.bands[b]
                var dot = 0.0
                for (t in start until end) dot += x[t] * y[t + shift]
                sum += dot / overlap
            }
            best = max(best, (sum / bands.size).toFloat())
        }
        return best
    }

    companion object {
        const val FRAME = 1024
        const val HOP = 512
        const val BANDS = 16
        /** ±3 s at 16 kHz with a 512-sample hop. */
        const val MAX_SHIFT = 94
        private const val MIN_FRAMES = 64

        /** At or above this, two files are taken for the same track (the same audio re-encoded scores ~0.75+, different songs ~0.3). It only leads to a question, never to a deletion by itself. */
        const val SAME_TRACK = 0.6f

        /** Sketches [samples], mono at [sampleRate] (16 kHz for the genre excerpt). */
        fun of(samples: FloatArray, sampleRate: Int = 16_000): Fingerprint {
            val frames = if (samples.size < FRAME) 0 else (samples.size - FRAME) / HOP + 1
            val edges = bandEdges(sampleRate)
            val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
            val re = FloatArray(FRAME)
            val im = FloatArray(FRAME)
            val out = Array(BANDS) { FloatArray(frames) }
            for (f in 0 until frames) {
                val offset = f * HOP
                for (i in 0 until FRAME) {
                    re[i] = samples[offset + i] * window[i]
                    im[i] = 0f
                }
                fft(re, im)
                for (b in 0 until BANDS) {
                    var e = 0.0
                    for (k in edges[b] until edges[b + 1]) e += re[k] * re[k] + im[k] * im[k]
                    out[b][f] = ln(e + 1e-9).toFloat()
                }
            }
            // Smoothed over ~130 ms: two files rarely line up to the frame, and
            // the shape of the music matters, not each 30 ms detail.
            val smoothed = Array(BANDS) { smooth(out[it], SMOOTH) }
            for (band in smoothed) normalize(band)
            return Fingerprint(smoothed)
        }

        /** FFT bins bounding 16 bands spaced evenly in pitch, 60 Hz to 7 kHz. */
        private fun bandEdges(sampleRate: Int): IntArray {
            val binHz = sampleRate.toDouble() / FRAME
            val low = 60.0
            val high = min(7_000.0, sampleRate / 2.0 - binHz)
            val edges = IntArray(BANDS + 1) { i ->
                (low * (high / low).pow(i.toDouble() / BANDS) / binHz).toInt().coerceAtLeast(1)
            }
            // Every band at least one bin wide.
            for (i in 1..BANDS) if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1
            return edges
        }

        private const val SMOOTH = 4

        private fun smooth(x: FloatArray, width: Int): FloatArray {
            val out = FloatArray(x.size)
            var sum = 0f
            for (i in x.indices) {
                sum += x[i]
                if (i >= width) sum -= x[i - width]
                out[i] = sum / min(i + 1, width)
            }
            return out
        }

        private fun normalize(x: FloatArray) {
            if (x.isEmpty()) return
            val mean = x.average()
            var variance = 0.0
            for (v in x) variance += (v - mean) * (v - mean)
            val sd = sqrt(variance / x.size).takeIf { it > 1e-6 } ?: 1.0
            for (i in x.indices) x[i] = ((x[i] - mean) / sd).toFloat()
        }
    }
}
