package io.github.maelmbange.thighhigh

import android.app.Application
import io.github.maelmbange.thighhigh.download.Downloader
import io.github.maelmbange.thighhigh.genre.GenreScanner

class ThighHighApp : Application() {
    /** Lives as long as the process, so downloads continue across screens. */
    val downloader by lazy { Downloader(this) }

    val genreScanner by lazy { GenreScanner(this) }
}
