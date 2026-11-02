package io.github.maelmbange.thighhigh.lyrics

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One line of synced lyrics: sung from [timeMs]. */
data class LyricLine(val timeMs: Long, val text: String)

/** What is known about a track's lyrics. */
sealed interface Lyrics {
    /** Timed lines, to follow along with the music. */
    data class Synced(val lines: List<LyricLine>) : Lyrics
    /** The words only, without timing. */
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

/** The line being sung at [positionMs], or -1 before the first one. */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (this[mid].timeMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** Reads LRC text: "[01:02.34] words", several tags on a line allowed, other tags ([ar:…]) skipped. */
fun parseLrc(text: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    for (raw in text.lineSequence()) {
        val tags = TIME_TAG.findAll(raw).toList()
        if (tags.isEmpty()) continue
        val words = raw.substring(tags.last().range.last + 1).trim()
        for (tag in tags) {
            val (m, s, f) = tag.destructured
            val fraction = when (f.length) {
                0 -> 0L
                1 -> f.toLong() * 100
                2 -> f.toLong() * 10
                else -> f.take(3).toLong()
            }
            lines += LyricLine((m.toLong() * 60 + s.toLong()) * 1000 + fraction, words)
        }
    }
    return lines.sortedBy { it.timeMs }
}

/** Words that tell a video apart from the song, never part of its title. */
private val NOISE = Regex(
    """\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|paroles|clip|visuali[sz]er|hd|hq|4k|remaster(ed)?|explicit|live session)\b[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)

/**
 * The title and artist to look a download up by. Videos are often titled
 * "Artist - Song (Official Video)" on a channel named after the label: the
 * part before the dash is then the artist.
 */
fun searchTerms(title: String, artist: String?): Pair<String, String?> {
    var t = NOISE.replace(title, "")
        .replace(Regex("""\s*\|.*$"""), "")
        .replace(Regex("""\s+(ft\.?|feat\.?)\s.*$""", RegexOption.IGNORE_CASE), "")
        .trim()
    var a = artist?.removeSuffix(" - Topic")?.removeSuffix("VEVO")?.trim()
    val dash = Regex("""\s+[-–—]\s+""").find(t)
    if (dash != null) {
        val before = t.substring(0, dash.range.first).trim()
        val after = t.substring(dash.range.last + 1).trim()
        if (after.isNotEmpty()) {
            if (a == null || !after.contains(a, ignoreCase = true)) a = before
            t = after
        }
    }
    return t to a?.takeIf { it.isNotBlank() }
}

/** Reads one LRCLIB record. */
fun lyricsOf(record: JSONObject): Lyrics {
    if (record.optBoolean("instrumental")) return Lyrics.Instrumental
    val synced = record.optString("syncedLyrics").takeIf { !record.isNull("syncedLyrics") && it.isNotBlank() }
    if (synced != null) parseLrc(synced).takeIf { it.isNotEmpty() }?.let { return Lyrics.Synced(it) }
    val plain = record.optString("plainLyrics").takeIf { !record.isNull("plainLyrics") && it.isNotBlank() }
    return plain?.let { Lyrics.Plain(it.trim()) } ?: Lyrics.NotFound
}

/**
 * The best of LRCLIB's search results for a track of [durationSec]: within
 * a few seconds of it (another length is another version), synced first.
 */
fun bestMatch(results: JSONArray, durationSec: Long?): JSONObject? {
    val records = (0 until results.length()).map { results.getJSONObject(it) }
    return records
        .filter { durationSec == null || abs(it.optDouble("duration", 0.0) - durationSec) <= 5 }
        .filter { lyricsOf(it) != Lyrics.NotFound }
        .minWithOrNull(
            compareBy<JSONObject> { if (lyricsOf(it) is Lyrics.Synced) 0 else 1 }
                .thenBy { durationSec?.let { d -> abs(it.optDouble("duration", 0.0) - d) } ?: 0.0 }
        )
}

/** How a result is kept on the phone, so a track is looked up once. */
fun Lyrics.toJson(): JSONObject = JSONObject().apply {
    when (this@toJson) {
        is Lyrics.Synced -> put("synced", JSONArray(lines.map { JSONObject().put("t", it.timeMs).put("w", it.text) }))
        is Lyrics.Plain -> put("plain", text)
        Lyrics.Instrumental -> put("instrumental", true)
        Lyrics.NotFound -> put("none", true)
    }
}

fun lyricsFromJson(o: JSONObject): Lyrics = when {
    o.has("synced") -> o.getJSONArray("synced").let { a ->
        Lyrics.Synced((0 until a.length()).map { a.getJSONObject(it).let { l -> LyricLine(l.getLong("t"), l.getString("w")) } })
    }
    o.has("plain") -> Lyrics.Plain(o.getString("plain"))
    o.optBoolean("instrumental") -> Lyrics.Instrumental
    else -> Lyrics.NotFound
}
