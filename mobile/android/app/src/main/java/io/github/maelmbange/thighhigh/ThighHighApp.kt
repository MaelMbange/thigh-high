package io.github.maelmbange.thighhigh

import android.app.Application
import io.github.maelmbange.thighhigh.download.Downloader

class ThighHighApp : Application() {
    /** Lives as long as the process, so downloads continue across screens. */
    val downloader by lazy { Downloader(this) }
}
