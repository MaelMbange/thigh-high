package io.github.maelmbange.thighhigh.download

private val URL = Regex("""https?://\S+""")

/**
 * The links in pasted or shared text, one per line, each once, in order. A
 * line with a URL in it gives that URL (text around it is dropped); any other
 * non-empty line is taken as typed, for yt-dlp to make sense of.
 */
fun parseLinks(text: String): List<String> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { line -> URL.findAll(line).map { it.value }.toList().ifEmpty { listOf(line) } }
        .distinct()
