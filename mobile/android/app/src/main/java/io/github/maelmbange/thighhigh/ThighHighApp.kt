package io.github.maelmbange.thighhigh

import android.app.Application
import io.github.maelmbange.thighhigh.download.Downloader
import io.github.maelmbange.thighhigh.genre.GenreScanner
import io.github.maelmbange.thighhigh.library.PlaylistStore

class ThighHighApp : Application() {
    /** Lives as long as the process, so downloads continue across screens. */
    val downloader by lazy { Downloader(this) }

    val genreScanner by lazy { GenreScanner(this) }

    val playlists by lazy { PlaylistStore(this) }

    /** Broadcasting and listening go on when the screen is closed (see CastService). */
    val cast by lazy { io.github.maelmbange.thighhigh.cast.CastController(this, kotlinx.coroutines.MainScope()) }
}
