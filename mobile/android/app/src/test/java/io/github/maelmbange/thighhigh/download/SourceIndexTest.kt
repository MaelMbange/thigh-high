package io.github.maelmbange.thighhigh.download

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceIndexTest {
    private val index = SourceIndex(ApplicationProvider.getApplicationContext())

    @Test fun remembersWhichTrackALinkGave() {
        assertNull(index.get("Youtube abc"))
        index.put("Youtube abc", 42)
        assertEquals(42L, index.get("Youtube abc"))
    }

    @Test fun replacingAVersionMovesItsLinks() {
        index.put("Youtube abc", 1)
        index.put("Soundcloud xyz", 1)
        index.put("Youtube other", 2)
        index.repoint(old = 1, new = 3)
        assertEquals(3L, index.get("Youtube abc"))
        assertEquals(3L, index.get("Soundcloud xyz"))
        assertEquals(2L, index.get("Youtube other"))
    }

    @Test fun keysNeedAnExtractorAndAnId() {
        assertEquals("Youtube dQw4w9WgXcQ", sourceKey(" Youtube dQw4w9WgXcQ\n"))
        assertNull(sourceKey("Youtube NA"))
        assertNull(sourceKey("garbage"))
    }
}
