package io.github.maelmbange.thighhigh.library

/**
 * Narrowing the library down to one artist, genre or album: a port of the
 * core crate's `filter` module, with the same grouping and ordering.
 */
enum class FilterField {
    Artist, Genre, Album;

    /** The track's value for this field, or [UNKNOWN]. */
    fun value(track: Track): String = when (this) {
        Artist -> track.artist
        Genre -> track.genre
        Album -> track.album
    }?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN

    companion object {
        /** Stands for tracks whose file has no value for the field. */
        const val UNKNOWN = "\u0000unknown"
    }
}

data class Filter(val field: FilterField, val value: String) {
    fun matches(track: Track) = field.value(track).equals(value, ignoreCase = true)
}

/** Every value of [field] with its track count, alphabetically, unknown last. */
fun filterValues(tracks: List<Track>, field: FilterField): List<Pair<String, Int>> {
    val counts = sortedMapOf<String, Pair<String, Int>>()
    for (track in tracks) {
        val value = field.value(track)
        // Group case-insensitively, keeping the first spelling seen.
        val key = value.lowercase()
        val (spelling, count) = counts[key] ?: (value to 0)
        counts[key] = spelling to count + 1
    }
    val (known, unknown) = counts.values.partition { it.first != FilterField.UNKNOWN }
    return known + unknown
}
