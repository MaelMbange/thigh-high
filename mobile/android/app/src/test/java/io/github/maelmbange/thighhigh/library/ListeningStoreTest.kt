package io.github.maelmbange.thighhigh.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListeningStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun recentIsLastPlayedFirstEachOnceAndKept() {
        val store = ListeningStore(context)
        listOf(1L, 2L, 3L, 2L).forEach(store::played)
        assertEquals(listOf(2L, 3L, 1L), store.recent.value)
        assertEquals(listOf(2L, 3L, 1L), ListeningStore(context).recent.value)
    }

    @Test fun playlistsGoLastPlayedFirstAndUnplayedKeepTheirOrder() {
        val store = ListeningStore(context)
        val p = { id: String -> Playlist(id, id, emptyList()) }
        store.playlistPlayed("b", at = 10)
        store.playlistPlayed("d", at = 20)
        val order = listOf(p("a"), p("b"), p("c"), p("d")).byLastPlayed(ListeningStore(context).playlistPlayed.value)
        assertEquals(listOf("d", "b", "a", "c"), order.map { it.id })

        store.forgetPlaylist("d")
        assertEquals(setOf("b"), ListeningStore(context).playlistPlayed.value.keys)
    }
}
