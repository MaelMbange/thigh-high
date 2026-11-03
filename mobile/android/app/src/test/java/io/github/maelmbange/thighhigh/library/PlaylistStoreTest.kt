package io.github.maelmbange.thighhigh.library

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun keepsPlaylistsAcrossRestarts() {
        val store = PlaylistStore(context)
        val p = store.create("  Soirée ", listOf(3L))
        store.add(p.id, listOf(1L, 3L, 2L))
        store.remove(p.id, 1L)
        store.rename(p.id, "Été")

        val reloaded = PlaylistStore(context).playlists.value
        assertEquals(listOf(Playlist(p.id, "Été", listOf(3L, 2L))), reloaded)
    }

    @Test fun skipsDeletedTracksInOrder() {
        val t = { id: Long -> Track(id, "t$id", null, null, null, null, null) }
        val playlist = Playlist("x", "x", listOf(5L, 9L, 1L))
        assertEquals(listOf(5L, 1L), playlist.tracks(listOf(t(1), t(5))).map { it.id })
    }

    @Test fun favoritesAppearWithTheirFirstTrack() {
        val store = PlaylistStore(context)
        store.add(FAVORITES_ID, listOf(4L))
        store.add(FAVORITES_ID, listOf(7L, 4L))
        store.remove(FAVORITES_ID, 4L)
        assertEquals(listOf(7L), PlaylistStore(context).playlists.value.single { it.id == FAVORITES_ID }.trackIds)
    }

    @Test fun deletingAPlaylistLeavesTheOthers() {
        val store = PlaylistStore(context)
        val a = store.create("A", listOf(1L))
        val b = store.create("B", listOf(1L))
        store.delete(a.id)
        assertEquals(listOf(b), PlaylistStore(context).playlists.value)
    }
}
