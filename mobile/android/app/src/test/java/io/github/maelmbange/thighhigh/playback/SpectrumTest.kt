package io.github.maelmbange.thighhigh.playback

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    private val rate = 44_100

    private fun sine(hz: Double, amplitude: Float = 0.5f) =
        FloatArray(2048) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun levels(window: FloatArray, spectrum: Spectrum = Spectrum(48)) =
        FloatArray(48).also { spectrum.levels(window, true, rate, it) }

    /** The band whose bins hold [hz]. */
    private fun barOf(hz: Double): Int {
        val bin = (hz * 2048 / rate).toInt()
        val edges = Spectrum(48).bandEdges(rate)
        return (0 until 48).first { bin >= edges[it] && bin < edges[it + 1] }.let { it }
    }

    @Test fun aNoteLightsOnePlaceNotAPatternAlongTheBar() {
        for (hz in listOf(80.0, 440.0, 3_000.0, 9_000.0)) {
            val out = levels(sine(hz))
            val peak = out.indices.maxBy { out[it] }
            assertTrue("$hz Hz peaks at bar $peak, expected near ${barOf(hz)}", kotlin.math.abs(peak - barOf(hz)) <= 1)
            // Far from the note, the bars stay low: nothing repeated further along.
            val far = out.indices.filter { kotlin.math.abs(it - peak) > 4 }.maxOf { out[it] }
            assertTrue("$hz Hz: a far bar reaches $far", far < 0.35f)
        }
    }

    @Test fun bassIsLeftHighsAreRight() {
        val low = levels(sine(60.0)).let { o -> o.indices.maxBy { o[it] } }
        val high = levels(sine(12_000.0)).let { o -> o.indices.maxBy { o[it] } }
        assertTrue(low < 8)
        assertTrue(high > 40)
    }

    @Test fun bandsNeverShareBins() {
        val edges = Spectrum(48).bandEdges(rate)
        for (b in 0 until 48) assertTrue(edges[b + 1] > edges[b])
    }

    @Test fun silenceAndPauseAreEmpty() {
        assertEquals(0f, levels(FloatArray(2048)).max(), 0f)
        val out = FloatArray(48) { 1f }
        Spectrum(48).levels(sine(440.0), false, rate, out)
        assertEquals(0f, out.max(), 0f)
    }
}
