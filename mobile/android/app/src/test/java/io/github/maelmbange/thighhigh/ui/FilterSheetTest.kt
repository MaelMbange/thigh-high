package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.FilterField
import io.github.maelmbange.thighhigh.library.Track
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
// Robolectric does not pass injected touches into the sheet's dialog window,
// so clicks go through the semantics actions instead.
class FilterSheetTest {
    @get:Rule val rule = createComposeRule()

    private val tracks = listOf(
        Track(1, "a", "Daft Punk", "Discovery", "electronic", null, null),
        Track(2, "b", "Daft Punk", "Homework", "house", null, null),
        Track(3, "c", "Björk", "Post", "electronic", null, null),
    )

    @Test fun searchingThenPickingAValueAppliesIt() {
        var picked: Filter? = null
        rule.setContent {
            ThighHighTheme { FilterSheet(tracks, current = null, onPick = { picked = it }, onDismiss = {}) }
        }
        // Let the sheet finish rising before touching it.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("Genre · 2").performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNode(hasSetTextAction()).performTextInput("elec")
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("electronic").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(Filter(FilterField.Genre, "electronic"), picked)
    }

    @Test fun showAllRemovesTheFilter() {
        var picked: Filter? = Filter(FilterField.Artist, "Björk")
        rule.setContent {
            ThighHighTheme { FilterSheet(tracks, current = picked, onPick = { picked = it }, onDismiss = {}) }
        }
        // Let the sheet finish rising before touching it.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("Tout afficher").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(null, picked)
    }
}
