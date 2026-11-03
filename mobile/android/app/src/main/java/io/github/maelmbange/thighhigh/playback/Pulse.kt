package io.github.maelmbange.thighhigh.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** What the player's buttons and cover swell with. */
enum class PulseSource {
    /** Below ~150 Hz: bass line and kick. */
    Bass,
    /** The hits of the drums (kick, snare, cymbals), not their loudness. */
    Drums,
    /** ~300 Hz to 3 kHz, where voices sit (guitars and piano too). */
    Voice,
    /** Above ~5 kHz: cymbals, hi-hats, sibilants. */
    Highs,
    /** The whole track's loudness. */
    All,
}

/**
 * Turns the audio heard into a level from 0 to 1, following [source]. Fed one
 * window per frame; keeps its filters running across windows. Loud and quiet
 * tracks both move: the level is compared with the recent peak.
 */
class PulseDetector(private val source: PulseSource) {
    private var low = 0f
    private var high = 0f
    private var lastSample = 0f
    private var peak = MIN_PEAK
    private var smoothed = 0f
    // Drums: the recent average of each band, to spot what rises above it.
    private var lowAverage = 0f
    private var highAverage = 0f

    fun level(window: FloatArray, playing: Boolean, sampleRate: Int): Float {
        if (!playing) {
            // The reference peak eases down too, so playback resumes at full swing.
            peak = (peak * 0.995f).coerceAtLeast(MIN_PEAK)
            smoothed *= 0.85f
            return smoothed
        }
        // Each frame's window is the latest audio, overlapping the previous
        // one: the filters start over on it rather than carry on from where
        // the last window ended, which would filter the same sound twice.
        low = window.firstOrNull() ?: 0f
        high = low
        lastSample = low
        val target = when (source) {
            PulseSource.Bass -> normalize(rms(window) { x -> lowPass(x, 150f, sampleRate) })
            PulseSource.Voice -> normalize(rms(window) { x -> bandPass(x, 300f, 3_000f, sampleRate) })
            PulseSource.Highs -> normalize(rms(window) { x -> x - lowPass(x, 5_000f, sampleRate) })
            PulseSource.All -> normalize(rms(window) { it })
            PulseSource.Drums -> drums(window, sampleRate)
        }
        // Rises at once, falls back smoothly (faster for drums: hits are short).
        val fall = if (source == PulseSource.Drums) 0.7f else 0.85f
        smoothed = if (target > smoothed) target else smoothed * fall + target * (1 - fall)
        return smoothed
    }

    private inline fun rms(window: FloatArray, filter: (Float) -> Float): Float {
        var energy = 0f
        for (x in window) {
            val y = filter(x)
            energy += y * y
        }
        return sqrt(energy / window.size)
    }

    private fun normalize(rms: Float): Float {
        peak = max(rms, peak * 0.995f).coerceAtLeast(MIN_PEAK)
        return (rms / peak).coerceIn(0f, 1f).let { it * it }
    }

    private fun lowPass(x: Float, cutoff: Float, rate: Int): Float {
        low += alpha(cutoff, rate) * (x - low)
        return low
    }

    private fun bandPass(x: Float, from: Float, to: Float, rate: Int): Float {
        high += alpha(to, rate) * (x - high) // below `to`
        low += alpha(from, rate) * (high - low) // below `from`, of that
        return high - low
    }

    /**
     * A drum hit is a sudden rise in energy: low (kick) or high (snare,
     * cymbals). Only the rise above each band's recent average counts, so a
     * held bass note or a pad does not move anything.
     */
    private fun drums(window: FloatArray, rate: Int): Float {
        var lowEnergy = 0f
        var highEnergy = 0f
        val a = alpha(150f, rate)
        for (x in window) {
            low += a * (x - low)
            // First difference: a cheap high-pass that keeps the snap of hits.
            val h = x - lastSample
            lastSample = x
            lowEnergy += low * low
            highEnergy += h * h
        }
        val lowRms = sqrt(lowEnergy / window.size)
        val highRms = sqrt(highEnergy / window.size)
        val rise = max(0f, lowRms - lowAverage * 1.2f) + 0.6f * max(0f, highRms - highAverage * 1.2f)
        lowAverage = lowAverage * 0.9f + lowRms * 0.1f
        highAverage = highAverage * 0.9f + highRms * 0.1f
        return normalize(rise)
    }

    private fun alpha(cutoff: Float, rate: Int) = 1f - exp(-2f * PI.toFloat() * cutoff / rate)

    private companion object {
        const val MIN_PEAK = 0.02f
    }
}
