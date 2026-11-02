package io.github.maelmbange.thighhigh.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val track = Track(1, "Crazy in Love", "Beyoncé", "Dangerously in Love", "rnb", null, null)

    @Test fun emptyQueryMatchesEverything() = assertTrue(track.matchesSearch("  "))

    @Test fun ignoresCaseAndAccents() = assertTrue(track.matchesSearch("BEYONCE"))

    @Test fun everyWordMustMatchSomewhere() {
        assertTrue(track.matchesSearch("love beyon"))
        assertTrue(track.matchesSearch("rnb crazy"))
        assertFalse(track.matchesSearch("love rock"))
    }
}
