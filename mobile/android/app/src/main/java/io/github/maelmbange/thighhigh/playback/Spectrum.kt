package io.github.maelmbange.thighhigh.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The music's spectrum in [bars] bands, lowest on the left: each bar follows
 * its own range of frequencies (bass, then voices, then cymbals), so a note
 * lights one place on the bar instead of a pattern repeated along it.
 *
 * Bands are spaced by octaves, as the ear hears pitch, but never narrower
 * than one FFT bin: the lowest bars, where bins are coarse, get one bin each,
 * so no two bars ever show the same thing.
 */
class Spectrum(private val bars: Int, private val size: Int = 2048) {
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val hann = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private var edges = IntArray(0)
    private var tilt = FloatArray(0)
    private var edgesRate = 0
    /** The loudest band lately, in dB: the top of the bar, easing down in quiet passages. */
    private var reference = MIN_REFERENCE_DB

    /** The first FFT bin of each band, and one past the last band. */
    fun bandEdges(sampleRate: Int): IntArray {
        if (sampleRate != edgesRate) {
            edgesRate = sampleRate
            val binHz = sampleRate.toFloat() / size
            val top = minOf(HIGH_HZ, sampleRate * 0.45f)
            val e = IntArray(bars + 1)
            for (b in 0..bars) {
                val f = LOW_HZ * (top / LOW_HZ).pow(b.toFloat() / bars)
                val bin = (f / binHz).roundToInt().coerceAtLeast(1)
                e[b] = if (b == 0) bin else max(bin, e[b - 1] + 1)
            }
            edges = e
            // Music is louder low down: lift the highs so cymbals move as much as the bass.
            tilt = FloatArray(bars) { b ->
                val center = (e[b] + e[b + 1]) / 2f * binHz
                TILT_DB_PER_OCTAVE * (ln(center / 1000f) / ln(2f))
            }
        }
        return edges
    }

    /**
     * Fills [out] with each band's level, 0 to 1, from [window] (the latest
     * [size] samples). Silence, or nothing playing, gives zeros.
     */
    fun levels(window: FloatArray, playing: Boolean, sampleRate: Int, out: FloatArray) {
        val e = bandEdges(sampleRate)
        if (!playing) {
            out.fill(0f)
            return
        }
        for (i in 0 until size) {
            re[i] = (window.getOrNull(i) ?: 0f) * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        var loudest = Float.NEGATIVE_INFINITY
        for (b in 0 until bars) {
            var power = 0f
            val from = e[b]
            val to = minOf(e[b + 1], size / 2)
            for (k in from until to) power += re[k] * re[k] + im[k] * im[k]
            val db = 10f * log10(power / max(1, to - from) + 1e-12f) + tilt[b]
            out[b] = db
            loudest = max(loudest, db)
        }
        // Rises with a loud band at once, eases down slowly: a quiet passage stays lower.
        reference = max(loudest, reference - REFERENCE_FALL_DB).coerceAtLeast(MIN_REFERENCE_DB)
        for (b in 0 until bars) out[b] = ((out[b] - (reference - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
    }

    private companion object {
        const val LOW_HZ = 40f
        const val HIGH_HZ = 16_000f
        /** From the loudest band down to this many dB below, the bar goes from full to empty. */
        const val RANGE_DB = 42f
        /**
         * The top of the bar never drops below this (a sine about 34 dB under
         * full scale), so near silence stays near empty instead of filling up.
         */
        const val MIN_REFERENCE_DB = 20f
        const val TILT_DB_PER_OCTAVE = 3f
        const val REFERENCE_FALL_DB = 0.08f
    }
}

/** In-place radix-2 FFT; the size must be a power of two. */
internal fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }
    var len = 2
    while (len <= n) {
        val angle = -2 * PI / len
        val wRe = cos(angle).toFloat()
        val wIm = sin(angle).toFloat()
        var i = 0
        while (i < n) {
            var uRe = 1f
            var uIm = 0f
            for (k in 0 until len / 2) {
                val a = i + k
                val b = a + len / 2
                val vRe = re[b] * uRe - im[b] * uIm
                val vIm = re[b] * uIm + im[b] * uRe
                re[b] = re[a] - vRe
                im[b] = im[a] - vIm
                re[a] += vRe
                im[a] += vIm
                val nextRe = uRe * wRe - uIm * wIm
                uIm = uRe * wIm + uIm * wRe
                uRe = nextRe
            }
            i += len
        }
        len = len shl 1
    }
}
