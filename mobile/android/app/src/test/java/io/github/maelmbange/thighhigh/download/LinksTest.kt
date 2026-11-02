package io.github.maelmbange.thighhigh.download

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    @Test fun oneLinkPerLineEachOnce() {
        val text = """
            https://youtu.be/a
              regarde ça : https://youtu.be/b?si=x  

            https://youtu.be/a
            soundcloud.com/x/y
        """.trimIndent()
        assertEquals(
            listOf("https://youtu.be/a", "https://youtu.be/b?si=x", "soundcloud.com/x/y"),
            parseLinks(text),
        )
    }

    @Test fun blankGivesNothing() = assertEquals(emptyList<String>(), parseLinks(" \n \n"))
}
