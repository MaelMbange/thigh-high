package io.github.maelmbange.thighhigh.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun record(title: String, artist: String, duration: Double, synced: String? = null, plain: String? = null) =
        JSONObject().put("trackName", title).put("artistName", artist).put("duration", duration).apply {
            synced?.let { put("syncedLyrics", it) }
            plain?.let { put("plainLyrics", it) }
        }

    @Test fun picksASyncedResultOfTheSameLength() {
        val results = JSONArray()
            .put(record("Odessa", "Caribou", 200.0, synced = "[00:01.00] wrong length"))
            .put(record("Odessa", "Caribou", 321.0, plain = "plain only"))
            .put(record("Odessa", "Caribou", 318.0, synced = "[00:01.00] right"))
        val best = bestMatch(results, 320, "Odessa", "Caribou")!!
        assertEquals(Lyrics.Synced(listOf(LyricLine(1_000, "right"))), lyricsOf(best))
        assertNull(bestMatch(JSONArray().put(record("Odessa", "Caribou", 100.0, plain = "x")), 320, "Odessa", "Caribou"))
    }

    @Test fun neverTakesAnotherSongOfTheSameLength() {
        // Free-text searches bring back other songs: same length is not enough.
        val results = JSONArray()
            .put(record("Nightcall", "Kavinsky", 258.0, synced = "[00:01.00] wrong song"))
            .put(record("Odessa", "Someone Else", 258.0, synced = "[00:01.00] cover by another artist"))
        assertNull(bestMatch(results, 258, "Odessa", "Caribou"))
        assertNull(bestMatch(results, 258, "Midnight City", null))
    }

    @Test fun recognisesTheSameSongWrittenDifferently() {
        assertTrue(isSameSong(record("Alors On Danse (Radio Edit)", "Stromae", 0.0), "Alors on danse", "Stromae"))
        assertTrue(isSameSong(record("Déjà vu", "Beyoncé feat. Jay-Z", 0.0), "Deja Vu", "Beyonce"))
        assertTrue(isSameSong(record("Get Lucky", "Daft Punk", 0.0), "Get Lucky", "Parlophone", "Daft Punk - Get Lucky (Official Audio)"))
        assertFalse(isSameSong(record("Lucky", "Daft Punk", 0.0).put("trackName", "Lucky Star"), "Get Lucky", "Daft Punk"))
        assertFalse(isSameSong(record("Get Lucky", "Someone", 0.0), "Get Lucky", "Daft Punk"))
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
