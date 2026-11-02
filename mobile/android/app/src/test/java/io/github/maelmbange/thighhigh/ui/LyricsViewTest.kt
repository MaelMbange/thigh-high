package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.LyricsState
import io.github.maelmbange.thighhigh.lyrics.LyricLine
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LyricsViewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingALineJumpsThere() {
        var seeked: Long? = null
        val lines = listOf(LyricLine(1_000, "Première ligne"), LyricLine(4_000, "Deuxième ligne"))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ThighHighTheme {
                LyricsView(
                    LyricsState.Ready(Lyrics.Synced(lines)),
                    CoverPalette.Default,
                    positionMs = { 1_500 },
                    durationMs = 10_000,
                    onSeek = { seeked = it },
                    onRetry = {},
                    modifier = Modifier.size(360.dp),
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Première ligne").assertIsDisplayed()
        rule.onNodeWithText("Deuxième ligne").performClick()
        assertEquals(4_000L, seeked)
    }

    @Test fun saysWhenNothingWasFound() {
        rule.setContent {
            ThighHighTheme {
                LyricsView(LyricsState.Ready(Lyrics.NotFound), CoverPalette.Default, { 0 }, 0, {}, {}, Modifier.size(360.dp))
            }
        }
        rule.onNodeWithText("Pas de paroles trouvées pour ce morceau.").assertIsDisplayed()
    }
}
