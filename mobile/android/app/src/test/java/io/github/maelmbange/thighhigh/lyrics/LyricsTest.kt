package io.github.maelmbange.thighhigh.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for org.json (Android's own, stubbed in plain unit tests).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsTest {
    @Test fun readsLrc() {
        val lines = parseLrc("[ar:Daft Punk]\n[00:17.12] One more time\n[00:20.5]\n[01:02.345][01:30.00] Celebrate")
        assertEquals(
            listOf(
                LyricLine(17_120, "One more time"),
                LyricLine(20_500, ""),
                LyricLine(62_345, "Celebrate"),
                LyricLine(90_000, "Celebrate"),
            ),
            lines,
        )
    }

    @Test fun findsTheSungLine() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, lines.indexAt(500))
        assertEquals(0, lines.indexAt(1_000))
        assertEquals(1, lines.indexAt(8_999))
        assertEquals(2, lines.indexAt(60_000))
    }

    @Test fun cleansVideoTitles() {
        assertEquals("One More Time" to "Daft Punk", searchTerms("Daft Punk - One More Time (Official Video)", "Daft Punk"))
        assertEquals("Get Lucky" to "Daft Punk", searchTerms("Daft Punk - Get Lucky [Official Audio] ft. Pharrell", "Parlophone Records"))
        assertEquals("Alors on danse" to "Stromae", searchTerms("Alors on danse", "Stromae - Topic"))
        assertEquals("Song" to null, searchTerms("Song (Lyrics)", null))
    }

    @Test fun picksASyncedResultOfTheSameLength() {
        val results = JSONArray()
            .put(JSONObject().put("duration", 200.0).put("syncedLyrics", "[00:01.00] wrong length"))
            .put(JSONObject().put("duration", 321.0).put("plainLyrics", "plain only"))
            .put(JSONObject().put("duration", 318.0).put("syncedLyrics", "[00:01.00] right"))
        val best = bestMatch(results, 320)!!
        assertEquals(Lyrics.Synced(listOf(LyricLine(1_000, "right"))), lyricsOf(best))
        assertNull(bestMatch(JSONArray().put(JSONObject().put("duration", 100.0).put("plainLyrics", "x")), 320))
    }

    @Test fun nullFieldsAreMissingNotText() {
        val record = JSONObject("""{"instrumental":false,"plainLyrics":null,"syncedLyrics":null}""")
        assertEquals(Lyrics.NotFound, lyricsOf(record))
        assertEquals(Lyrics.Instrumental, lyricsOf(JSONObject().put("instrumental", true)))
    }

    @Test fun keptLyricsReadBackTheSame() {
        listOf(
            Lyrics.Synced(listOf(LyricLine(1, "a"), LyricLine(2, "b"))),
            Lyrics.Plain("words"),
            Lyrics.Instrumental,
            Lyrics.NotFound,
        ).forEach { assertEquals(it, lyricsFromJson(JSONObject(it.toJson().toString()))) }
    }
}
