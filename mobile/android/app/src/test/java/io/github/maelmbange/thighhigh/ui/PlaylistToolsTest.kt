package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.maelmbange.thighhigh.library.Playlist
import io.github.maelmbange.thighhigh.library.Track
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlaylistToolsTest {
    @get:Rule val rule = createComposeRule()

    private val playlist = Playlist("p", "Soirée", listOf(1L, 2L))

    @Test fun deletingAsksTwice() {
        var deleted = 0
        rule.setContent { ThighHighTheme { DeletePlaylistDialog(playlist, onConfirm = { deleted++ }, onDismiss = {}) } }
        rule.onNodeWithText("Supprimer « Soirée » ?").assertExists()
        rule.onNodeWithText("Continuer").performClick()
        assertEquals(0, deleted)
        rule.onNodeWithText("Vraiment supprimer ?").assertExists()
        rule.onNodeWithText("Supprimer définitivement").performClick()
        assertEquals(1, deleted)
    }

    @Test fun cancelingKeepsThePlaylist() {
        var deleted = 0
        var dismissed = 0
        rule.setContent { ThighHighTheme { DeletePlaylistDialog(playlist, onConfirm = { deleted++ }, onDismiss = { dismissed++ }) } }
        rule.onNodeWithText("Continuer").performClick()
        rule.onNodeWithText("Annuler").performClick()
        assertEquals(0, deleted)
        assertEquals(1, dismissed)
    }

    @Test fun pickerAddsTheTickedTracksInOrder() {
        val t = { id: Long, title: String -> Track(id, title, "Artiste", null, null, null, null) }
        var added: List<Long>? = null
        rule.setContent {
            ThighHighTheme {
                TrackPickerSheet(
                    library = listOf(t(1, "Un"), t(2, "Deux"), t(3, "Trois")),
                    already = setOf(1L),
                    onAdd = { added = it },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Déjà dedans").assertExists()
        rule.onNodeWithText("Trois").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Deux").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Ajouter (2)").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(3L, 2L), added)
    }
}
