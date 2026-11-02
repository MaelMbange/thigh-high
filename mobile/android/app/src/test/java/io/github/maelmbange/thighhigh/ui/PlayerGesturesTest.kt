package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import io.github.maelmbange.thighhigh.Cover
import io.github.maelmbange.thighhigh.PlaybackState
import io.github.maelmbange.thighhigh.library.Track
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerGesturesTest {
    @get:Rule val rule = createComposeRule()

    private var minimized = 0
    private var next = 0
    private var previous = 0
    private var listClicks = 0

    private fun show(hasPrevious: Boolean = true, hasNext: Boolean = true) {
        // The seek bar animates forever, so the clock is driven by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ThighHighTheme {
                PlayerScreen(
                    track = Track(1, "Titre", "Artiste", null, null, null, null),
                    playback = PlaybackState(currentId = 1, isPlaying = true, durationMs = 180_000, hasPrevious = hasPrevious, hasNext = hasNext),
                    cover = Cover(null, CoverPalette.Default),
                    positionMs = { 0L },
                    onMinimize = { minimized++ },
                    onTogglePlay = {},
                    
                    onNext = { next++ },
                    onPrevious = { previous++ },
                    onCycleRepeat = {},
                    onToggleShuffle = {},
                    onSeek = {},
                    upNext = listOf(io.github.maelmbange.thighhigh.QueueEntry(1, 2, "Morceau suivant", "Quelqu'un")),
                )
            }
        }
    }

    /** The player as the app shows it: sliding over a scrollable list. */
    private fun showInApp() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ThighHighTheme {
                var open by remember { mutableStateOf(true) }
                var tick by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { while (true) { delay(100); tick++ } }
                Box(Modifier.fillMaxSize()) {
                    Scaffold { padding ->
                        LazyColumn(Modifier.padding(padding)) {
                            items(50) { Text("Ligne $it $tick", Modifier.clickable { listClicks++ }.padding(16.dp)) }
                        }
                    }
                    AnimatedVisibility(visible = open, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                        PlayerScreen(
                            track = Track(1, "Titre $tick", "Artiste", null, null, null, null),
                            playback = PlaybackState(currentId = 1, isPlaying = true, durationMs = 180_000, hasPrevious = true, hasNext = true),
                            cover = Cover(null, CoverPalette.Default),
                            positionMs = { tick * 100L },
                            onMinimize = { minimized++; open = false },
                            onTogglePlay = {},
                            
                            onNext = { next++ },
                            onPrevious = { previous++ },
                            onCycleRepeat = {},
                            onToggleShuffle = {},
                            onSeek = {},
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test fun inAppSlowDragDownMinimizes() {
        showInApp()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.6f, durationMillis = 1_500) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun inAppSlowSwipeLeftPlaysNext() {
        showInApp()
        rule.onRoot().performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f, durationMillis = 1_000) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, next)
    }

    @Test fun isolatedSlowDragDownMinimizes() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.6f, durationMillis = 1_500) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun inAppFastDragDownMinimizes() {
        showInApp()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.7f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun inAppTapBesideControlsStaysInPlayer() {
        showInApp()
        // Empty space between the top bar and the cover, and beside the title.
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.08f)) }
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.95f, height * 0.5f)) }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(0, listClicks)
        assertEquals(0, minimized)
    }

    @Test fun dragStartingBelowCoverDoesNothing() {
        showInApp()
        // From the controls at the bottom, a quick flick down.
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.85f, endY = height * 0.99f, durationMillis = 80) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, minimized)
        assertEquals(0, listClicks)
    }

    @Test fun dragStartingJustBelowCoverDoesNothing() {
        showInApp()
        val coverBottom = rule.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot.bottom
        rule.onRoot().performTouchInput { swipeDown(startY = coverBottom + 20f, endY = height * 0.99f, durationMillis = 80) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, minimized)
    }

    @Test fun dragStartingJustInsideCoverMinimizes() {
        showInApp()
        val coverBottom = rule.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot.bottom
        rule.onRoot().performTouchInput { swipeDown(startY = coverBottom - 20f, endY = height * 0.99f, durationMillis = 80) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun swipeDownFromTopMinimizes() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.7f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun swipeDownOnCoverMinimizes() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.8f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, minimized)
    }

    @Test fun swipeLeftOnCoverPlaysNext() {
        show()
        rule.onRoot().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, next)
        assertEquals(0, minimized)
    }

    @Test fun swipeRightOnCoverPlaysPrevious() {
        show()
        rule.onRoot().performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.9f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, previous)
    }

    @Test fun swipeRightOnFirstTrackDoesNothing() {
        show(hasPrevious = false)
        rule.onRoot().performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.9f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, previous)
    }

    @Test fun swipeLeftOnLastTrackDoesNothing() {
        show(hasNext = false)
        rule.onRoot().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, next)
    }

    @Test fun quickFlickLeftPlaysNext() {
        show()
        rule.onRoot().performTouchInput { swipeLeft(startX = width * 0.6f, endX = width * 0.45f, durationMillis = 60) }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, next)
    }

    @Test fun slowDragUpOpensTheQueue() {
        show()
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 1_200) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Morceau suivant").assertIsDisplayed()
        assertEquals(0, minimized)
    }

    @Test fun smallDragUpFallsBack() {
        show()
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.75f, durationMillis = 800) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Morceau suivant").assertDoesNotExist()
    }
}
