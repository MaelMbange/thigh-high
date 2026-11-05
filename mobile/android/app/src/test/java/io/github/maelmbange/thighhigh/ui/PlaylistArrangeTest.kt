package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.github.maelmbange.thighhigh.library.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlaylistArrangeTest {
    @get:Rule val rule = createComposeRule()

    private val arranged = mutableListOf<List<String>>()
    private val deleted = mutableListOf<String>()
    private val opened = mutableListOf<String?>()

    private fun show() {
        val playlists = listOf("A", "B", "C").map { Playlist(it.lowercase(), it, emptyList()) }
        rule.setContent {
            PlaylistsScreen(
                playlists = playlists,
                library = emptyList(),
                openId = null,
                currentId = null,
                isPlaying = false,
                onOpen = { opened += it },
                onCreate = {},
                onPlay = { _, _, _ -> },
                onShuffle = { _, _ -> },
                onTrackLongClick = { _, _ -> },
                onRemove = { _, _ -> },
                onRename = { _, _ -> },
                onDelete = { deleted += it.id },
                onAddTracks = { _, _ -> },
                onArrange = { arranged += it },
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun dragMovesAPlaylist() {
        show()
        // Grid: [new][A] / [B][C]. A, held then dragged slowly onto C, goes last.
        val tiles = rule.onAllNodesWithTag("playlistTile")
        val a = tiles[0].fetchSemanticsNode().boundsInRoot
        val c = tiles[2].fetchSemanticsNode().boundsInRoot
        rule.onAllNodesWithTag("playlistGrid")[0].performTouchInput {
            val start = a.center - androidx.compose.ui.geometry.Offset(0f, 0f)
            down(start)
            advanceEventTime(1_000)
            val end = c.center
            for (i in 1..30) {
                moveTo(start + (end - start) * (i / 30f))
                advanceEventTime(30)
            }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(listOf("b", "c", "a")), arranged)
        assertTrue(deleted.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun longPressStillOffersToDelete() {
        show()
        rule.onAllNodesWithTag("playlistTile")[1].performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(arranged.isEmpty())
        assertTrue(opened.isEmpty())
        rule.onNodeWithText("Supprimer « B » ?").assertExists()
    }

    @Test
    fun tapOpens() {
        show()
        rule.onNodeWithText("C").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf<String?>("c"), opened)
    }
}
