package io.github.maelmbange.thighhigh.library

import java.text.Normalizer

private val MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** Lower case without accents, so "beyonce" finds "Beyoncé". */
fun searchKey(text: String): String =
    MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

/**
 * Whether [track] matches what was typed in the search bar: every word must
 * appear in its title, artist, album or genre, in any order.
 */
fun Track.matchesSearch(query: String): Boolean {
    val words = searchKey(query).split(SPACES).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = searchKey(listOfNotNull(title, artist, album, genre).joinToString(" "))
    return words.all { it in text }
}
