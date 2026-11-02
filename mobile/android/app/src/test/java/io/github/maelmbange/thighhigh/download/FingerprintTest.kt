package io.github.maelmbange.thighhigh.download

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {
    private val rate = 16_000

    /** A made-up "song": notes changing every quarter second, with drums. */
    private fun song(seed: Int, seconds: Int = 34): FloatArray {
        val rnd = Random(seed)
        val notes = List(seconds * 4) { 110.0 * Math.pow(2.0, rnd.nextInt(36) / 12.0) }
        val kicks = List(seconds * 4) { rnd.nextFloat() < 0.5f }
        return FloatArray(seconds * rate) { i ->
            val q = i / (rate / 4)
            val t = i.toDouble() / rate
            val tone = 0.4 * sin(2 * PI * notes[q] * t) + 0.2 * sin(2 * PI * notes[q] * 2 * t)
            val inQ = (i % (rate / 4)).toDouble() / rate
            val kick = if (kicks[q]) 0.6 * sin(2 * PI * 55 * t) * Math.exp(-inQ * 30) else 0.0
            (tone + kick).toFloat()
        }
    }

    /** The same audio, as another upload would give it: shifted, quieter, with noise. */
    private fun reupload(x: FloatArray, shiftSamples: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        return FloatArray(x.size - shiftSamples) { i -> x[i + shiftSamples] * 0.6f + (rnd.nextFloat() - 0.5f) * 0.05f }
    }

    @Test fun sameTrackFromAnotherUploadMatches() {
        val a = song(1)
        val s = Fingerprint.of(a).similarity(Fingerprint.of(reupload(a, shiftSamples = rate * 3 / 2, seed = 7)))
        println("similarity $s"); assertTrue("similarity $s", s >= Fingerprint.SAME_TRACK)
    }

    @Test fun differentTracksDoNotMatch() {
        val s = Fingerprint.of(song(1)).similarity(Fingerprint.of(song(2)))
        println("similarity $s"); assertTrue("similarity $s", s < Fingerprint.SAME_TRACK)
    }

    @Test fun silenceMatchesNothing() {
        val s = Fingerprint.of(FloatArray(rate * 30)).similarity(Fingerprint.of(song(3)))
        println("similarity $s"); assertTrue("similarity $s", s < Fingerprint.SAME_TRACK)
    }
}
