package io.github.maelmbange.thighhigh.genre

/**
 * The genre model from the Rust core (`crates/core/src/genre.rs`), through
 * the JNI bridge in `crates/android`. Same model and same answers as the
 * terminal app.
 */
object Genre {
    init {
        System.loadLibrary("thigh_high_android")
    }

    /** Sample rate the excerpt must be decoded at, mono. */
    @JvmStatic external fun sampleRate(): Int

    /** Seconds of audio the model listens to. */
    @JvmStatic external fun excerptSeconds(): Double

    /** Where the excerpt starts (the middle of the track), in seconds; 0 or less = unknown duration. */
    @JvmStatic external fun excerptStart(durationMs: Long): Double

    /** The most likely genre, or null when nothing stands out. Slow: call off the main thread. */
    @JvmStatic external fun detect(samples: FloatArray): String?

    /** Writes the genre into the file's tags; throws if the file can't be written. */
    @JvmStatic external fun saveGenre(path: String, genre: String)
}

/** Genre values that say nothing about the music (same list as the core crate). */
private val PLACEHOLDER_GENRES = setOf("music", "musique", "other", "unknown", "genre")

/** Blank and placeholder genres (YouTube's "Music" category) count as no genre. */
fun cleanGenre(genre: String?): String? =
    genre?.trim()?.takeUnless { it.isEmpty() || it.lowercase() in PLACEHOLDER_GENRES }
