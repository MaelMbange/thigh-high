package io.github.maelmbange.thighhigh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BassTrailTest {
    @Test fun laterControlsSeeTheBeatLater() {
        val trail = BassTrail()
        // One frame every 10 ms: silence, then a hit at 100 ms.
        for (ms in 0..200 step 10) trail.push(ms * 1_000_000L, if (ms >= 100) 1f else 0f)
        assertEquals(1f, trail.at(0), 0.001f)
        assertEquals(1f, trail.at(70), 0.001f)
        assertEquals(0f, trail.at(140), 0.001f)
        // Between two frames: halfway.
        assertEquals(0.5f, trail.at(105), 0.001f)
    }

    @Test fun emptyIsSilent() = assertEquals(0f, BassTrail().at(100), 0f)
}
