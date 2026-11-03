package io.github.maelmbange.thighhigh.playback

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseDetectorTest {
    private val rate = 48_000

    /** A tone played window after window; returns the detector's average level once settled. */
    private fun steady(source: PulseSource, freq: Float, amp: Float = 0.5f, windows: Int = 60): Float {
        val d = PulseDetector(source)
        var t = 0
        var sum = 0f
        repeat(windows) { i ->
            val w = FloatArray(1024) { amp * sin(2 * PI * freq * (t + it) / rate).toFloat() }
            t += w.size
            val l = d.level(w, true, rate)
            if (i >= windows / 2) sum += l
        }
        return sum / (windows - windows / 2)
    }

    /** Relative response of [source] to a tone in its band against one outside it, same loudness. */
    private fun selective(source: PulseSource, inBand: Float, outBand: Float) {
        // Each detector adapts to its own peak, so compare a mix: in-band loud, then out-of-band only.
        val d = PulseDetector(source)
        var t = 0
        fun feed(freq: Float, n: Int): Float {
            var last = 0f
            repeat(n) {
                val w = FloatArray(1024) { 0.5f * sin(2 * PI * freq * (t + it) / rate).toFloat() }
                t += w.size
                last = d.level(w, true, rate)
            }
            return last
        }
        val inside = feed(inBand, 30)
        val outside = feed(outBand, 30)
        assertTrue("$source: in band $inside, out of band $outside", inside > 0.5f && outside < inside * 0.4f)
    }

    @Test fun bassFollowsLowNotes() = selective(PulseSource.Bass, 60f, 2_000f)

    @Test fun voiceFollowsTheMids() = selective(PulseSource.Voice, 1_000f, 40f)

    @Test fun highsFollowCymbals() = selective(PulseSource.Highs, 10_000f, 200f)

    @Test fun drumsIgnoreAHeldNote() {
        val level = steady(PulseSource.Drums, 60f)
        assertTrue("held note gave $level", level < 0.2f)
    }

    @Test fun drumsReactToHits() {
        val d = PulseDetector(PulseSource.Drums)
        var hits = 0f
        repeat(40) { i ->
            // A short 60 Hz thump every 10 windows (~200 ms), silence otherwise.
            val hit = i % 10 == 0
            val w = FloatArray(1024) { if (hit) 0.8f * sin(2 * PI * 60 * it / rate).toFloat() else 0f }
            val l = d.level(w, true, rate)
            if (hit && i > 0) hits = maxOf(hits, l)
        }
        assertTrue("hits reached $hits", hits > 0.5f)
    }

    @Test fun silentWhenPaused() {
        val d = PulseDetector(PulseSource.All)
        d.level(FloatArray(1024) { 0.5f }, true, rate)
        var l = 1f
        repeat(30) { l = d.level(FloatArray(1024), false, rate) }
        assertTrue(l < 0.01f)
    }
}
