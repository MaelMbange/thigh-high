package io.github.maelmbange.thighhigh.download

import android.content.Context

/**
 * Which track each downloaded link became, keyed by what yt-dlp calls the
 * video ("Youtube dQw4w9WgXcQ"): the same video reached through another URL
 * (youtu.be, music.youtube.com, a playlist link, ?t=…) still has the same key.
 */
class SourceIndex(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sources", Context.MODE_PRIVATE)

    /** The track downloaded from [source], if one was. It may since have been deleted. */
    fun get(source: String): Long? = prefs.getLong(source, -1L).takeIf { it >= 0 }

    fun put(source: String, trackId: Long) = prefs.edit().putLong(source, trackId).apply()

    /** The links that led to [old] now lead to [new] (the old version was replaced). */
    fun repoint(old: Long, new: Long) {
        val edit = prefs.edit()
        prefs.all.forEach { (key, value) -> if (value == old) edit.putLong(key, new) }
        edit.apply()
    }
}

/** yt-dlp's key for a video, from the line printed by `%(extractor_key)s %(id)s`. */
fun sourceKey(line: String): String? = line.trim().takeIf { ' ' in it && !it.endsWith(" NA") && !it.startsWith("NA ") }
